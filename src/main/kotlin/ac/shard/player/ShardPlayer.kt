/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
 *
 * Shard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.player

import ac.shard.config.ConfigManager
import ac.shard.config.PacketSettings
import ac.shard.data.AimProcessor
import ac.shard.data.CrystalTracker
import ac.shard.data.TickBuffer
import ac.shard.detection.DetectionState
import ac.shard.detection.InferenceServices
import ac.shard.detection.PlayerInference
import ac.shard.entity.CompensatedEntities
import ac.shard.entity.CompensatedFireworks
import ac.shard.mitigation.MitigationState
import ac.shard.mitigation.NO_MITIGATE_PERMISSION
import ac.shard.platform.scheduler.TaskHandle
import ac.shard.player.state.CombatState
import ac.shard.player.state.MovementState
import ac.shard.player.state.TrackingState
import ac.shard.player.state.TransactionTracker
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Messages
import ac.shard.utils.data.HeadRotation
import ac.shard.utils.data.PacketStateData
import ac.shard.utils.latency.ILatencyUtils
import ac.shard.utils.latency.LatencyUtils
import ac.shard.utils.update.RotationUpdate
import ac.shard.world.CompensatedWorld
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.util.Vector3d
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player

class ShardPlayer
@Suppress("LongParameterList")
constructor(
  val user: User,
  private val logger: Logger,
  private val configManager: ConfigManager,
  val exemptManager: ExemptManager,
  private val scheduler: SchedulerService,
  messages: Messages,
  aiServices: InferenceServices,
) {
  val uuid: UUID = user.uuid
  val name: String = user.profile?.name ?: uuid.toString()

  @Volatile private var attachedPlayer: Player? = null

  val playerOrNull: Player?
    get() = attachedPlayer

  val isAttached: Boolean
    get() = attachedPlayer != null

  val player: Player
    get() = attachedPlayer ?: error("Bukkit player for $name is not attached yet")

  fun attach(bukkitPlayer: Player) {
    val now = System.currentTimeMillis()
    transactions.lastTransSentTime.set(now)
    transactions.lastTransReceivedTime.set(now)
    attachedPlayer = bukkitPlayer
  }

  val packetStateData: PacketStateData = PacketStateData()
  val rotationUpdate: RotationUpdate = RotationUpdate(HeadRotation(), HeadRotation(), 0f, 0f)
  val joinTime: Long = System.currentTimeMillis()

  @Volatile var buffersRestored: Boolean = false

  @Volatile var buffersLoaded: Boolean = false

  @Volatile var scoreLoaded: Boolean = false

  @Volatile var sessionEnded: Boolean = false

  val sessionLock = Any()

  var sessionStarted: Boolean = false

  @Volatile
  var disabledByPermission: Boolean = false
    private set

  @Volatile
  var exemptByPermission: Boolean = false
    private set

  @Volatile
  var noMitigateByPermission: Boolean = false
    private set

  var playerThreadRefresh: TaskHandle? = null

  fun refreshOnPlayerThread() {
    val bukkitPlayer = attachedPlayer ?: return
    disabledByPermission = bukkitPlayer.hasPermission(ExemptManager.DISABLE_PERMISSION)
    exemptByPermission = bukkitPlayer.hasPermission(ExemptManager.EXEMPT_PERMISSION)
    noMitigateByPermission = bukkitPlayer.hasPermission(NO_MITIGATE_PERMISSION)
    ai.onPlayerThread()
  }

  val detectionDisabled: Boolean
    get() = exemptManager.isDisabled(this) || isBedrockExempt

  var entityId: Int = 0
  var gameMode: GameMode = GameMode.SURVIVAL
  @Volatile var brand: String = "vanilla"
  @Volatile var brandReceived: Boolean = false
  var isBedrock: Boolean = false

  val isBedrockExempt: Boolean
    get() = configManager.settings.bedrockExempt && isBedrock

  val movement: MovementState = MovementState()
  val combat: CombatState = CombatState()
  val tracking: TrackingState = TrackingState()
  val mitigation: MitigationState = MitigationState()
  val transactions: TransactionTracker = TransactionTracker()
  @Volatile
  var tickBuffer: TickBuffer = TickBuffer(requiredBufferCapacity())
    private set

  val pendingTeleports: Queue<TeleportData> = ConcurrentLinkedQueue()
  val pendingRotations: Queue<RotationData> = ConcurrentLinkedQueue()

  val crystalTracker: CrystalTracker = CrystalTracker()
  val compensatedEntities: CompensatedEntities = CompensatedEntities(this)
  val compensatedFireworks: CompensatedFireworks = CompensatedFireworks()
  val compensatedWorld: CompensatedWorld = CompensatedWorld(this)
  val latencyUtils: ILatencyUtils = LatencyUtils(this, logger, messages)
  val aim: AimProcessor = AimProcessor()
  val ai: PlayerInference = PlayerInference(this, aiServices)
  val detection: DetectionState
    get() = ai.state

  private val hasDisconnected = java.util.concurrent.atomic.AtomicBoolean(false)
  val persisted = java.util.concurrent.atomic.AtomicBoolean(false)

  val packets: PacketSettings
    get() = configManager.settings.packets

  init {
    tracking.enabledWindowStarts = { configManager.enabledWindowStarts }
  }

  fun isPointThree(): Boolean = user.clientVersion.isOlderThan(ClientVersion.V_1_18_2)

  fun getMovementThreshold(): Double = if (isPointThree()) 0.03 else 0.0002

  fun sendTransaction() {
    transactions.sendTransaction(user)
  }

  fun pollData() {
    val now = System.currentTimeMillis()
    if (user.encoderState != com.github.retrooper.packetevents.protocol.ConnectionState.PLAY) {
      transactions.lastTransReceivedTime.set(now)
      return
    }
    if (now - transactions.lastTransSentTime.get() > TRANSACTION_KEEPALIVE_MS) {
      sendTransaction()
    }
    if (now - transactions.lastTransReceivedTime.get() > TRANSACTION_TIMEOUT_MS) {
      disconnect(Component.text("Transaction timeout"))
    }
  }

  @Suppress("TooGenericExceptionCaught")
  fun disconnect(reason: Component) {
    if (!hasDisconnected.compareAndSet(false, true)) {
      return
    }
    try {
      user.sendPacket(
        com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect(reason)
      )
    } catch (e: Exception) {
      logger.warning("[Shard] Disconnect packet for $name failed: ${e.message}")
    }
    user.closeConnection()

    val bukkitPlayer = attachedPlayer ?: return
    val text = PlainTextComponentSerializer.plainText().serialize(reason)
    @Suppress("DEPRECATION") scheduler.runSync(bukkitPlayer) { bukkitPlayer.kickPlayer(text) }
  }

  fun ensureTickBufferCapacity() {
    val required = requiredBufferCapacity()
    if (required > tickBuffer.capacity()) {
      tickBuffer = tickBuffer.resizedTo(required)
    }
  }

  private fun requiredBufferCapacity(): Int {
    val window = configManager.settings.collect
    return maxOf(window.pre + window.post + 1, configManager.streamProfile?.wire?.maxChunkRows ?: 0)
  }

  class TeleportData(
    val location: Vector3d,
    val yaw: Float,
    val pitch: Float,
    val flags: RelativeFlag,
    val transactionId: Int,
  ) {
    fun isRelativeX(): Boolean = flags.has(RelativeFlag.X)

    fun isRelativeY(): Boolean = flags.has(RelativeFlag.Y)

    fun isRelativeZ(): Boolean = flags.has(RelativeFlag.Z)

    fun isRelativePos(): Boolean = isRelativeX() || isRelativeY() || isRelativeZ()

    fun rotationMatches(actualYaw: Float, actualPitch: Float): Boolean =
      (flags.has(RelativeFlag.YAW) || angleMatches(actualYaw, yaw, yaw % FULL_TURN)) &&
        (flags.has(RelativeFlag.PITCH) ||
          angleMatches(actualPitch, pitch, pitch.coerceIn(MIN_PITCH, MAX_PITCH)))

    // Some versions wrap yaw and clamp pitch client-side, others apply the raw value, and the
    // server
    // sends it raw either way, so both forms have to be accepted.
    private fun angleMatches(actual: Float, raw: Float, adjusted: Float): Boolean =
      !raw.isFinite() || actual == raw || actual == adjusted

    private companion object {
      const val FULL_TURN = 360f
      const val MIN_PITCH = -90f
      const val MAX_PITCH = 90f
    }
  }

  class RotationData(
    val yaw: Float,
    val pitch: Float,
    val relativeYaw: Boolean,
    val relativePitch: Boolean,
    val transactionId: Int,
  )

  private companion object {
    const val TRANSACTION_KEEPALIVE_MS = 80L
    const val TRANSACTION_TIMEOUT_MS = 60_000L
  }
}
