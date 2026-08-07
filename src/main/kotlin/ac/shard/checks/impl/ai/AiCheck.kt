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
import ac.shard.ai.TickSerializer
import ac.shard.alert.AlertManager
import ac.shard.alert.AlertType
import ac.shard.api.event.AiPredictionEvent
import ac.shard.checks.AbstractCheck
import ac.shard.checks.CheckData
import ac.shard.checks.CheckFactory
import ac.shard.checks.Reloadable
import ac.shard.checks.type.TickCheck
import ac.shard.config.ConfigManager
import ac.shard.damage.DamageProcessor
import ac.shard.data.AttackWindowTracker
import ac.shard.data.TickData
import ac.shard.debug.DebugCategory
import ac.shard.debug.DebugManager
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionCheckMode
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServer
import ac.shard.utils.Message
import ac.shard.utils.MessageUtil
import com.github.retrooper.packetevents.PacketEvents
import java.util.concurrent.ConcurrentHashMap

@CheckData(name = "AI", legacyNames = ["AI (Aim)"])
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
) : AbstractCheck(shardPlayer), TickCheck, Reloadable {
  private var aiEnabled = false

  private val window = AttackWindowTracker()
  private var ticksSinceLastInference = Int.MAX_VALUE / 2

  private val labelBuffers = ConcurrentHashMap<String, ViolationBuffer>()

  val buffer: Double
    get() = labelBuffers.values.maxOfOrNull { it.value } ?: 0.0

  fun labelBuffer(label: String): Double = labelBuffers[label]?.value ?: 0.0

  fun labelBufferSnapshot(): Map<String, Double> =
    labelBuffers.entries.associate { entry -> entry.key to entry.value.value }

  fun restoreBuffer(value: Double) {
    restoreLabelBuffer(UNATTRIBUTED_LABEL, value)
  }

  fun restoreLabelBuffer(label: String, value: Double) {
    labelBuffers.getOrPut(label) { ViolationBuffer() }.restore(value)
  }

  fun feedBuffers(probabilities: Map<String, Double>): Map<String, Double> {
    val settings = bufferSettings()
    val crossed = mutableMapOf<String, Double>()
    for ((label, probability) in probabilities) {
      val labelBuffer = labelBuffers.getOrPut(label) { ViolationBuffer() }
      labelBuffer.feed(probability, settings)
      if (labelBuffer.value > flag) {
        crossed[label] = labelBuffer.value
        labelBuffer.consumeFlag(bufferResetOnFlag)
      }
    }
    return crossed
  }

  private fun labelledProbabilities(
    probabilities: List<Double>?,
    probability: Double,
  ): Map<String, Double> {
    val labels = configManager.aiLabels
    if (probabilities != null && labels.isNotEmpty() && probabilities.size != labels.size) {
      plugin.logger.warning(
        "[AiCheck] Model declares ${labels.size} label(s) but the response carries " +
          "${probabilities.size} probabilities; falling back to the overall verdict"
      )
    }
    return if (probabilities != null && probabilities.size == labels.size && labels.isNotEmpty()) {
      labels.zip(probabilities).toMap()
    } else {
      mapOf(UNATTRIBUTED_LABEL to probability)
    }
  }

  private fun bufferSettings() =
    ViolationBuffer.Settings(
      CHEAT_PROBABILITY,
      LEGIT_PROBABILITY,
      bufferMultiplier,
      bufferDecrease,
    )

  @Volatile
  var lastProbability: Double = 0.0
    private set

  @Volatile var prob90: Int = 0

  val inferenceTicks: Int
    get() = window.ticksSinceAttack

  val inferencePostWindow: Int
    get() = configManager.aiPostWindow

  val inferenceProgress: IntArray?
    get() {
      val anchored = window.ticksSinceAttack >= 0
      val stepThrottles = configManager.aiStep > configManager.aiPostWindow
      val leftWindow = if (anchored) configManager.aiPostWindow - window.ticksSinceAttack else -1
      val leftStep = configManager.aiStep - ticksSinceLastInference
      return when {
        stepThrottles && leftStep > leftWindow && leftStep > 0 ->
          intArrayOf(ticksSinceLastInference, configManager.aiStep)
        anchored -> intArrayOf(window.ticksSinceAttack, configManager.aiPostWindow)
        else -> null
      }
    }

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
    flag = configManager.aiFlag
    bufferResetOnFlag = configManager.aiResetOnFlag
    bufferMultiplier = configManager.aiBufferMultiplier
    bufferDecrease = configManager.aiBufferDecrease
    suspiciousAlertBuffer = configManager.suspiciousAlertsBuffer
  }

  override fun onDataTick(player: ShardPlayer) {
    if (!aiEnabled) return

    if (ticksSinceLastInference < Int.MAX_VALUE) ticksSinceLastInference++

    window.onTick(
      player.tickBuffer,
      player.tracking.windowStartThisTick,
      player.tracking.windowStartKind,
      configManager.aiPostWindow,
    ) { ticks, attackIndex, kind ->
      if (kind == TickData.START_MELEE_PLAYER && ticksSinceLastInference >= configManager.aiStep) {
        sendInference(ticks, attackIndex)
        ticksSinceLastInference = 0
      }
    }
  }

  private fun inDisabledRegion(): Boolean =
    configManager.isAiWorldGuardEnabled() &&
      regionProvider.isPlayerInDisabledRegion(shardPlayer.player)

  private fun sendInference(ticksSinceAttack: Int, attackIndex: Int) {
    val window =
      shardPlayer.tickBuffer.extractWindow(
        configManager.aiPreWindow,
        configManager.aiPostWindow,
        ticksSinceAttack,
        attackIndex,
      ) ?: return

    if (configManager.regionCheckMode == RegionCheckMode.SKIP_DETECTION && inDisabledRegion()) {
      debugManager.log(
        DebugCategory.WORLDGUARD,
        "Player ${shardPlayer.player.name} is in a disabled region. Skipping AI check.",
      )
      return
    }

    val bytes =
      TickSerializer.serialize(
        window,
        shardPlayer.user.clientVersion.protocolVersion,
        PacketEvents.getAPI().serverManager.version.protocolVersion,
        configManager.aiColumnMask,
      )
    val player = shardPlayer.player
    val playerName = player.name

    scheduler.runAsync {
      try {
        aiService.request(bytes).whenCompleteAsync({ parsed, error ->
          if (error != null) onError(error) else onResponse(parsed)
        }) { runnable ->
          scheduler.runSync(player, runnable)
        }
      } catch (e: Exception) {
        plugin.logger.warning("[AiCheck] Failed to send data for $playerName: ${e.message}")
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

    if (apiResponse.expectedColumns != null || apiResponse.labels != null) {
      configManager.updateAiParams(
        null,
        null,
        null,
        columns = apiResponse.expectedColumns,
        labels = apiResponse.labels,
      )
    }

    val probability = apiResponse.probability
    lastProbability = probability
    damageProcessor.applyProbability(shardPlayer, probability)

    if (probability > 0.9) {
      prob90++
    }

    val oldBuffer = buffer
    val crossed = feedBuffers(labelledProbabilities(apiResponse.probabilities, probability))

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
    if (crossed.isNotEmpty()) {
      if (configManager.regionCheckMode == RegionCheckMode.SKIP_PUNISHMENT && inDisabledRegion()) {
        debugManager.log(
          DebugCategory.WORLDGUARD,
          "Player ${shardPlayer.player.name} is in a disabled region. Skipping punishment.",
        )
      } else {
        flagged = true
        flag(buildAiFlagDebug(probability, crossed.values.max()), crossed.keys)
      }
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
        labelBufferSnapshot(),
      )
    )
  }

  private fun onError(error: Throwable): Void? {
    lastProbability = 0.0
    val shardPlayer = shardPlayer
    damageProcessor.reset(shardPlayer)

    val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error

    val ex = cause as? AiServiceException
    if (ex != null && ex.hasNewParams) {
      configManager.updateAiParams(
        ex.newPreWindow,
        ex.newPostWindow,
        ex.newStep,
        columns = ex.newColumns,
      )
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

  companion object {
    const val UNATTRIBUTED_LABEL = "_unattributed"

    private const val CHEAT_PROBABILITY = 0.90
    private const val LEGIT_PROBABILITY = 0.10
  }
}
