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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.player

import ac.shard.Shard
import ac.shard.alert.AlertManager
import ac.shard.api.event.ShardEventBus
import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.DataCollectorManager
import ac.shard.config.ConfigManager
import ac.shard.entity.CompensatedEntities
import ac.shard.player.state.CombatState
import ac.shard.player.state.MovementState
import ac.shard.player.state.TransactionTracker
import ac.shard.punishment.PunishmentManager
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import ac.shard.utils.data.PacketStateData
import ac.shard.utils.latency.ILatencyUtils
import ac.shard.utils.latency.LatencyUtils
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.util.Vector3d
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

class ShardPlayer
@Suppress("LongParameterList")
constructor(
  val player: Player,
  val user: User,
  private val plugin: Shard,
  private val configManager: ConfigManager,
  aiSequence: Int,
  alertManager: AlertManager,
  dataCollectorManager: DataCollectorManager,
  aiServerProvider: AIServerProvider,
  val exemptManager: ExemptManager,
  private val scheduler: SchedulerService,
  checkManagerFactory: CheckManager.Factory,
  punishmentManagerFactory: PunishmentManager.Factory,
  val eventBus: ShardEventBus,
) {
  val uuid: UUID = player.uniqueId
  val packetStateData: PacketStateData = PacketStateData()
  val joinTime: Long = System.currentTimeMillis()

  var entityId: Int = 0
  var gameMode: GameMode = GameMode.SURVIVAL
  var brand: String = "vanilla"
  var isBedrock: Boolean = false

  val isBedrockExempt: Boolean
    get() = configManager.isBedrockExemptEnabled() && isBedrock

  val movement: MovementState = MovementState()
  val combat: CombatState = CombatState(aiSequence + 1)
  val transactions: TransactionTracker = TransactionTracker()

  val pendingTeleports: Queue<TeleportData> = ConcurrentLinkedQueue()
  val pendingRotations: Queue<RotationData> = ConcurrentLinkedQueue()

  val compensatedEntities: CompensatedEntities = CompensatedEntities(this)
  val latencyUtils: ILatencyUtils = LatencyUtils(this, plugin)
  val checkManager: CheckManager = checkManagerFactory.create(this)
  val punishmentManager: PunishmentManager = punishmentManagerFactory.create(this)

  private var cancelDuplicatePacket = true
  private var forceCancelDuplicatePacket = false
  private var ignoreDuplicatePacketRotation = true

  init {
    refreshDuplicatePacketSettings()
  }

  fun isPointThree(): Boolean = user.clientVersion.isOlderThan(ClientVersion.V_1_18_2)

  fun getMovementThreshold(): Double = if (isPointThree()) 0.03 else 0.0002

  fun isCancelDuplicatePacket(): Boolean = cancelDuplicatePacket

  fun isForceCancelDuplicatePacket(): Boolean = forceCancelDuplicatePacket

  fun isIgnoreDuplicatePacketRotation(): Boolean = ignoreDuplicatePacketRotation

  fun sendTransaction() {
    transactions.sendTransaction(user)
  }

  fun disconnect(reason: Component) {
    user.sendPacket(
      com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect(reason)
    )
    user.closeConnection()

    scheduler.runSync(player) { player.kick(reason) }
  }

  fun reload() {
    refreshDuplicatePacketSettings()
    punishmentManager.reload()
    checkManager.reloadChecks()
  }

  private fun refreshDuplicatePacketSettings() {
    cancelDuplicatePacket = configManager.cancelDuplicatePacket
    forceCancelDuplicatePacket = configManager.forceCancelDuplicatePacket
    ignoreDuplicatePacketRotation = configManager.ignoreDuplicatePacketRotation
  }

  class TeleportData(val location: Vector3d, val flags: RelativeFlag, val transactionId: Int) {
    fun isRelativeX(): Boolean = flags.has(RelativeFlag.X)

    fun isRelativeY(): Boolean = flags.has(RelativeFlag.Y)

    fun isRelativeZ(): Boolean = flags.has(RelativeFlag.Z)
  }

  class RotationData(
    val yaw: Float,
    val pitch: Float,
    val relativeYaw: Boolean,
    val relativePitch: Boolean,
    val transactionId: Int,
  )
}
