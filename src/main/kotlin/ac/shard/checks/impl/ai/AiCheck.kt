/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
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
package ac.shard.checks.impl.ai

import ac.shard.Shard
import ac.shard.ai.AiResult
import ac.shard.ai.AiService
import ac.shard.ai.AiServiceException
import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.event.AiPredictionEvent
import ac.shard.checks.AbstractCheck
import ac.shard.checks.CheckData
import ac.shard.checks.CheckFactory
import ac.shard.checks.Reloadable
import ac.shard.checks.type.PacketCheck
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionCheckMode
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServer
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import java.util.concurrent.atomic.AtomicReference

@CheckData(name = "AI (Aim)")
@Suppress("TooManyFunctions")
class AiCheck(
  shardPlayer: ShardPlayer,
  private val plugin: Shard,
  private val aiService: AiService,
  private val configManager: ConfigManager,
  private val regionProvider: RegionProvider,
  private val alertManager: AlertManager,
  private val damageProcessor: DamageProcessor,
  private val debugManager: DebugManager,
  private val scheduler: SchedulerService,
) : AbstractCheck(shardPlayer), PacketCheck, Reloadable {
  private var aiEnabled = false

  @Volatile private var ring: TickRingBuffer = TickRingBuffer(configManager.aiSequence)
  private val snapshotBuffer: AtomicReference<FloatArray?> = AtomicReference()

  @Volatile
  var buffer: Double = 0.0
    private set

  fun restoreBuffer(value: Double) {
    val sanitized = kotlin.math.max(0.0, value)
    buffer = kotlin.math.max(buffer, sanitized)
  }

  @Volatile
  var lastProbability: Double = 0.0
    private set

  @Volatile var prob90: Int = 0

  private var flag = 0.0
  private var bufferResetOnFlag = 0.0
  private var bufferMultiplier = 0.0
  private var bufferDecrease = 0.0
  private var suspiciousAlertBuffer = 0.0

  init {
    reload()
  }

  interface Factory : CheckFactory {
    override fun create(player: ShardPlayer): AiCheck
  }

  override fun reload() {
    aiEnabled = aiService.isEnabled

    if (ring.capacity != maxOf(configManager.aiSequence, TickRingBuffer.MIN_SEQUENCE)) {
      ring = TickRingBuffer(configManager.aiSequence)
    }

    flag = configManager.aiFlag
    bufferResetOnFlag = configManager.aiResetOnFlag
    bufferMultiplier = configManager.aiBufferMultiplier
    bufferDecrease = configManager.aiBufferDecrease
    suspiciousAlertBuffer = configManager.suspiciousAlertsBuffer
  }

  override fun onPacketReceive(event: PacketReceiveEvent) {
    if (!aiEnabled) return
    if (!WrapperPlayClientPlayerFlying.isFlying(event.packetType)) return
    val shardPlayer = shardPlayer
    val r = ring

    val packetStateData = shardPlayer.packetStateData
    if (packetStateData.shouldIgnoreFlyingTick) {
      if (
        packetStateData.lastPacketWasOnePointSeventeenDuplicate &&
          debugManager.isEnabled(DebugCategory.PACKET_DUPLICATION)
      ) {
        debugManager.log(
          DebugCategory.PACKET_DUPLICATION,
          "Mojang failed IQ Test for: ${shardPlayer.player.name}.",
        )
      }
      return
    }

    if (shardPlayer.compensatedEntities.self.riding != null) {
      r.reset()
      return
    }

    if (!configManager.aiContinuous && shardPlayer.combat.ticksSinceAttack > r.capacity) {
      r.reset()
      return
    }

    val movement = shardPlayer.movement
    r.push(movement.yaw - movement.lastYaw, movement.pitch - movement.lastPitch)

    if (r.canSend(configManager.aiStep)) {
      trySendWindow(r)
      r.markSent()
    }
  }

  private fun trySendWindow(r: TickRingBuffer) {
    if (configManager.regionCheckMode == RegionCheckMode.SKIP_DETECTION && inDisabledRegion()) {
      debugManager.log(
        DebugCategory.WORLDGUARD,
        "Player ${shardPlayer.player.name} is in a disabled region. Skipping AI check.",
      )
      return
    }
    sendData(r)
  }

  private fun inDisabledRegion(): Boolean =
    configManager.isAiWorldGuardEnabled() &&
      regionProvider.isPlayerInDisabledRegion(shardPlayer.player)

  private fun sendData(r: TickRingBuffer) {
    val count = r.count
    if (count == 0 || !aiEnabled) {
      return
    }

    val floatCount = count * FEATURES_PER_TICK
    val snapshot = borrowSnapshot(floatCount)
    r.snapshotInto(snapshot)

    val shardPlayer = shardPlayer
    val player = shardPlayer.player
    val playerName = player.name

    scheduler.runAsync {
      try {
        aiService.request(snapshot, count).whenCompleteAsync({ parsed, error ->
          if (error != null) onError(error) else onResponse(parsed)
        }) { runnable ->
          scheduler.runSync(player, runnable)
        }
      } catch (e: Exception) {
        plugin.logger.warning("[AiCheck] Failed to send data for $playerName: ${e.message}")
      } finally {
        releaseSnapshot(snapshot)
      }
    }
  }

  private fun onResponse(parsed: AiResult) {
    val shardPlayer = shardPlayer
    if (parsed.disabled) {
      lastProbability = 0.0
      damageProcessor.reset(shardPlayer)
      return
    }

    if (parsed.hasParseError()) {
      plugin.logger.warning(
        "[AiCheck] Error parsing API response: ${parsed.parseError?.message}. Response Body: ${parsed.raw}"
      )
      lastProbability = 0.0
      damageProcessor.reset(shardPlayer)
      return
    }

    val apiResponse = parsed.response

    if (apiResponse == null) {
      plugin.logger.warning(
        "[AiCheck] API response is missing probability. Response: ${parsed.raw}"
      )
      lastProbability = 0.0
      damageProcessor.reset(shardPlayer)
      return
    }

    val probability = apiResponse.probability
    lastProbability = probability
    damageProcessor.applyProbability(shardPlayer, probability)

    if (probability > 0.9) {
      prob90++
    }

    val oldBuffer = buffer

    if (probability > CHEAT_PROBABILITY) {
      buffer += (probability - CHEAT_PROBABILITY) * bufferMultiplier
    } else if (probability < LEGIT_PROBABILITY) {
      buffer = kotlin.math.max(0.0, buffer - bufferDecrease)
    }

    if (buffer > suspiciousAlertBuffer && oldBuffer <= suspiciousAlertBuffer) {
      alertManager.send(
        MessageUtil.getMessage(
          Message.SUSPICIOUS_ALERT_TRIGGERED,
          "player",
          shardPlayer.player.name,
          "buffer",
          formatAiBuffer(buffer),
        ),
        AlertType.SUSPICIOUS,
      )
    }

    if (debugManager.isEnabled(DebugCategory.AI_PROBABILITY)) {
      debugManager.log(
        DebugCategory.AI_PROBABILITY,
        buildAiProbabilityDebugMessage(
          playerName = "${shardPlayer.player.name} | ${shardPlayer.user.clientVersion.releaseName}",
          probability = probability,
          oldBuffer = oldBuffer,
          newBuffer = buffer,
          damageMultiplier = shardPlayer.combat.damageMultiplier,
        ),
      )
    }

    var flagged = false
    if (buffer > flag) {
      if (configManager.regionCheckMode == RegionCheckMode.SKIP_PUNISHMENT && inDisabledRegion()) {
        debugManager.log(
          DebugCategory.WORLDGUARD,
          "Player ${shardPlayer.player.name} is in a disabled region. Skipping punishment.",
        )
      } else {
        flagged = true
        flag(buildAiFlagDebug(probability, buffer))
      }
      buffer = bufferResetOnFlag
    }

    shardPlayer.eventBus.post(
      AiPredictionEvent(
        shardPlayer.uuid,
        shardPlayer.player.name,
        checkName,
        probability,
        oldBuffer,
        buffer,
        shardPlayer.combat.damageMultiplier,
        prob90,
        flagged,
      )
    )
  }

  private fun onError(error: Throwable): Void? {
    lastProbability = 0.0
    val shardPlayer = shardPlayer
    damageProcessor.reset(shardPlayer)

    val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error

    val ex = cause as? AiServiceException
    if (ex != null && (ex.newSequence != null || ex.newStep != null)) {
      configManager.updateAiParams(ex.newSequence, ex.newStep)
      if (ring.capacity != maxOf(configManager.aiSequence, TickRingBuffer.MIN_SEQUENCE)) {
        ring = TickRingBuffer(configManager.aiSequence)
      }
      return null
    }

    if (cause is AIServer.RequestException) {
      if (cause.code == AIServer.ResponseCode.WAITING) {
        return null
      }

      val reason = cause.serverMessage ?: cause.message
      val logMessage =
        "[AiCheck] API Error ${cause.serverCode ?: cause.code} for player " +
          "${shardPlayer.player.name}: $reason"

      val transientCategory = transientCategoryFor(cause.code)
      if (transientCategory != null) {
        debugManager.log(transientCategory, logMessage)
      } else {
        plugin.logger.warning(logMessage)
      }
    } else {
      plugin.logger.warning(
        "[AiCheck] Unknown API Error for ${shardPlayer.player.name}: ${cause.message}"
      )
    }
    return null
  }

  private fun transientCategoryFor(code: AIServer.ResponseCode): DebugCategory? =
    when (code) {
      AIServer.ResponseCode.TIMEOUT -> DebugCategory.AI_API_TIMEOUT
      AIServer.ResponseCode.NETWORK_ERROR -> DebugCategory.AI_API_NETWORK
      AIServer.ResponseCode.RATE_LIMITED -> DebugCategory.AI_API_RATE_LIMITED
      AIServer.ResponseCode.SERVICE_UNAVAILABLE -> DebugCategory.AI_API_SERVICE_UNAVAILABLE
      else -> null
    }

  private fun borrowSnapshot(size: Int): FloatArray {
    val buffer = snapshotBuffer.getAndSet(null)
    if (buffer == null || buffer.size < size) {
      return FloatArray(size)
    }
    return buffer
  }

  private fun releaseSnapshot(buffer: FloatArray) {
    snapshotBuffer.set(buffer)
  }

  companion object {
    private const val CHEAT_PROBABILITY = 0.90
    private const val LEGIT_PROBABILITY = 0.10
    private const val FEATURES_PER_TICK = 2
  }
}
