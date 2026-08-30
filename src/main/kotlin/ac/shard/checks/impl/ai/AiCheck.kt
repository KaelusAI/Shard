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
import ac.shard.ai.label.LabelKey
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelledVerdict
import ac.shard.ai.label.VerdictResolver
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
import ac.shard.mitigation.MitigationScorer
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
@Suppress("TooManyFunctions", "LongParameterList")
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
  private val mitigationScorer: MitigationScorer,
) : AbstractCheck(shardPlayer), TickCheck, Reloadable {
  private var aiEnabled = false

  private val window = AttackWindowTracker()
  private var ticksSinceLastInference = Int.MAX_VALUE / 2

  private val labelBuffers = ConcurrentHashMap<String, ViolationBuffer>()

  private val verdicts =
    VerdictResolver(
      settings = {
        VerdictResolver.Settings(
          labels = configManager.aiLabels,
          mode = configManager.effectiveLabelMode,
          split = configManager.aiLabelSplit,
          maxTracked = configManager.aiLabelMaxTracked,
          legitClasses = configManager.aiLegitLabels,
          thresholdedLabels = configManager.aiLabelThresholds.keys,
        )
      },
      warn = { plugin.logger.warning(it) },
      severe = { plugin.logger.severe(it) },
    )

  val buffer: Double
    get() = labelBuffers.values.maxOfOrNull { it.value } ?: 0.0

  fun labelBuffer(label: String): Double = labelBuffers[label]?.value ?: 0.0

  fun labelBufferSnapshot(): Map<String, Double> =
    labelBuffers.entries.associate { entry -> entry.key to entry.value.value }

  val declaredLabels: List<String>
    get() {
      val labels = configManager.aiLabels
      val mode =
        configManager.effectiveLabelMode
          ?: if (labels.size > 1) LabelMode.MULTI_LABEL else LabelMode.SINGLE
      if (!configManager.aiLabelSplit || mode != LabelMode.MULTI_LABEL) return emptyList()
      return labels.filterNot { it in configManager.aiLegitLabels }
    }

  fun restoreBuffer(value: Double) {
    restoreLabelBuffer(UNATTRIBUTED_LABEL, value)
  }

  fun trackedLabels(): Set<String> = labelBuffers.keys.toSet()

  fun clearBuffers(): Map<String, Double> {
    val cleared = labelBufferSnapshot()
    labelBuffers.clear()
    return cleared
  }

  fun clearBuffer(label: String): Double? {
    val removed = labelBuffers.remove(label) ?: return null
    return removed.value
  }

  fun restoreLabelBuffer(label: String, value: Double) {
    bufferFor(label)?.restore(value)
  }

  private fun bufferFor(label: String): ViolationBuffer? {
    val existing = labelBuffers[label]
    return when {
      existing != null -> existing
      labelBuffers.size >= configManager.aiLabelMaxTracked.coerceAtLeast(1) && !evictWeakest() ->
        null
      else -> labelBuffers.getOrPut(label) { ViolationBuffer() }
    }
  }

  private fun evictWeakest(): Boolean {
    val weakest = labelBuffers.entries.minByOrNull { it.value.value }
    if (weakest == null || weakest.value.value >= flag) {
      if (weakest != null && evictionWarned.compareAndSet(false, true)) {
        plugin.logger.warning(
          "[AiCheck] Tracking ${labelBuffers.size} labels and every one is near its flag; " +
            "refusing new ones. Check what the inference server is sending."
        )
      }
      return false
    }
    labelBuffers.remove(weakest.key)
    return true
  }

  fun feedBuffers(verdict: LabelledVerdict): Map<String, Double> {
    val settings = bufferSettings()
    val crossed = mutableMapOf<String, Double>()
    for ((label, probability) in verdict.values) {
      val labelBuffer = bufferFor(label) ?: continue
      labelBuffer.feed(probability, bufferSettings(label))
      if (labelBuffer.value > flag) {
        crossed[label] = labelBuffer.value
        labelBuffer.consumeFlag(bufferResetOnFlag)
      }
    }
    if (verdict.attributed) retireAbsent(verdict, settings.decrease)
    return crossed
  }

  private fun retireAbsent(verdict: LabelledVerdict, decrease: Double) {
    val declared = configManager.aiLabels.toSet()
    val iterator = labelBuffers.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key in verdict.values) continue
      val undeclared =
        declared.isNotEmpty() && entry.key !in declared && !LabelKey.isReserved(entry.key)
      if (undeclared) {
        iterator.remove()
        debugManager.log(
          DebugCategory.AI_PERSISTENT_BUFFER,
          "${shardPlayer.player.name} dropped ${entry.key}=${"%.1f".format(entry.value.value)}, " +
            "the model no longer has that label",
        )
      } else if (entry.value.decay(decrease) <= 0.0) {
        iterator.remove()
      }
    }
  }

  private fun leadingLabelName(): String {
    val catalog = configManager.labelCatalog
    return catalog.leading(labelBufferSnapshot())?.let(catalog::displayName) ?: ""
  }

  private fun bufferSettings(label: String? = null): ViolationBuffer.Settings {
    val own = label?.let { configManager.aiLabelThresholds[it] }
    return ViolationBuffer.Settings(
      own?.cheat ?: CHEAT_PROBABILITY,
      own?.legit ?: LEGIT_PROBABILITY,
      bufferMultiplier,
      bufferDecrease,
    )
  }

  @Volatile
  var lastProbability: Double = 0.0
    private set

  @Volatile
  var lastCheatProbability: Double = 0.0
    private set

  @Volatile
  var lastLabelProbabilities: Map<String, Double> = emptyMap()
    private set

  @Volatile var prob90: Int = 0

  val trail = ProbabilityTrail()

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
  private val evictionWarned = java.util.concurrent.atomic.AtomicBoolean(false)

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

    if (shardPlayer.compensatedEntities.self.riding != null) {
      mitigationScorer.freeze(shardPlayer)
      return
    }
    mitigationScorer.thaw(shardPlayer)

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
      lastCheatProbability = 0.0
      lastLabelProbabilities = emptyMap()
      damageProcessor.reset(shardPlayer)
      return
    }

    if (parsed.hasParseError()) {
      plugin.logger.warning(
        "[AiCheck] Error parsing API response: ${parsed.parseError?.message}. Response Body: ${parsed.raw}"
      )
      lastProbability = 0.0
      lastCheatProbability = 0.0
      lastLabelProbabilities = emptyMap()
      damageProcessor.reset(shardPlayer)
      return
    }

    val apiResponse = parsed.response

    if (apiResponse == null) {
      plugin.logger.warning(
        "[AiCheck] API response is missing probability. Response: ${parsed.raw}"
      )
      lastProbability = 0.0
      lastCheatProbability = 0.0
      lastLabelProbabilities = emptyMap()
      damageProcessor.reset(shardPlayer)
      return
    }

    applyServerCorrections(apiResponse)

    val probability = apiResponse.probability
    lastProbability = probability
    trail.record(probability)

    val verdict =
      verdicts.resolve(apiResponse.probabilities, probability, apiResponse.namedProbabilities)
    val cheatProbability = verdict.values.values.maxOrNull() ?: 0.0
    if (verdict.attributed) {
      lastCheatProbability = cheatProbability
      lastLabelProbabilities = verdict.values
      mitigationScorer.record(shardPlayer, cheatProbability, verdict.values)
      damageProcessor.applyProbability(shardPlayer, cheatProbability)
      if (cheatProbability > CHEAT_PROBABILITY) {
        prob90++
      }
    }

    val oldBuffer = buffer
    val crossed = feedBuffers(verdict)

    if (buffer > suspiciousAlertBuffer && oldBuffer <= suspiciousAlertBuffer) {
      alertManager.send(
        MessageUtil.getMessage(
          Message.SUSPICIOUS_ALERT_TRIGGERED,
          "player",
          shardPlayer.player.name,
          "buffer",
          formatAiBuffer(buffer),
          "label",
          leadingLabelName(),
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
        flag(buildAiFlagDebug(probability, crossed, labelBufferSnapshot()), crossed.keys)
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
        verdict.values,
      )
    )
  }

  private fun applyServerCorrections(response: ac.shard.server.AIResponse) {
    val carriesCorrections =
      response.expectedColumns != null ||
        response.labels != null ||
        response.labelTitles != null ||
        response.legitLabels != null ||
        response.labelMode != null ||
        response.modelTitle != null ||
        response.labelThresholds != null
    if (!carriesCorrections) return
    configManager.updateAiParams(
      null,
      null,
      null,
      columns = response.expectedColumns,
      labels = response.labels,
      labelNames = response.labelTitles,
      legitLabels = response.legitLabels,
      modelTitle = response.modelTitle,
      labelMode = response.labelMode,
      labelThresholds = response.labelThresholds,
    )
  }

  @Suppress("ReturnCount")
  internal fun onError(error: Throwable): Void? {
    lastProbability = 0.0
    lastCheatProbability = 0.0
    lastLabelProbabilities = emptyMap()
    val shardPlayer = shardPlayer
    damageProcessor.reset(shardPlayer)

    val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error

    val ex = cause as? AiServiceException
    if (ex != null && ex.hasNewParams) {
      val moved =
        configManager.updateAiParams(
          ex.newPreWindow,
          ex.newPostWindow,
          ex.newStep,
          model = ex.newModel,
          columns = ex.newColumns,
          labels = ex.newLabels,
          labelNames = ex.newLabelNames,
          legitLabels = ex.newLegitLabels,
          labelMode = ex.newLabelMode,
          labelThresholds = ex.newLabelThresholds,
          modelTitle = ex.newModelTitle,
        )
      if (moved) {
        stuckReconfigures.set(0)
        stuckReported.set(false)
      } else {
        noteStuckReconfigure(ex)
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

  private fun noteStuckReconfigure(ex: AiServiceException) {
    if (stuckReconfigures.incrementAndGet() < STUCK_RECONFIGURES) return
    if (!stuckReported.compareAndSet(false, true)) return
    plugin.logger.severe(
      "[AiCheck] Stuck after $STUCK_RECONFIGURES rejected windows, nothing is being scored. " +
        "Asked for ${describeAsk(ex)}; holding ${configManager.describeModelConfig()}. " +
        "Labels are folded to lowercase with underscores here, and a threshold outside 0..1 or " +
        "a cheat mark below its legit mark is dropped, so either can differ silently."
    )
  }

  private fun describeAsk(ex: AiServiceException): String = buildString {
    append("model=").append(ex.newModel ?: "-")
    append(" window=").append(ex.newPreWindow ?: "-").append('/').append(ex.newPostWindow ?: "-")
    append(" step=").append(ex.newStep ?: "-")
    append(" labels=").append(ex.newLabels?.joinToString(",") ?: "-")
    append(" legit=").append(ex.newLegitLabels?.joinToString(",") ?: "-")
    append(" mode=").append(ex.newLabelMode ?: "-")
    append(" thresholds=").append(ex.newLabelThresholds?.keys?.sorted()?.joinToString(",") ?: "-")
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
    const val UNATTRIBUTED_LABEL = LabelKey.UNATTRIBUTED

    private const val CHEAT_PROBABILITY = 0.90
    private const val LEGIT_PROBABILITY = 0.10

    private const val STUCK_RECONFIGURES = 3

    private val stuckReconfigures = java.util.concurrent.atomic.AtomicInteger(0)
    private val stuckReported = java.util.concurrent.atomic.AtomicBoolean(false)

    fun forgetStuckReconfigures() {
      stuckReconfigures.set(0)
      stuckReported.set(false)
    }
  }
}
