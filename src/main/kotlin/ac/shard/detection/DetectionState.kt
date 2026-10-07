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
package ac.shard.detection

import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelKey
import ac.shard.ai.label.LabelMode
import ac.shard.ai.label.LabelledVerdict
import ac.shard.ai.stream.BufferSpec
import ac.shard.ai.stream.ModelRole
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.Schedule
import ac.shard.ai.stream.StreamProfile
import ac.shard.config.ConfigManager

@Suppress("TooManyFunctions")
class DetectionState(private val configManager: ConfigManager) {
  private val detections = DetectionBuffers()
  private val restoreBlocks = java.util.concurrent.CopyOnWriteArrayList<(DetectionKey) -> Boolean>()

  @Volatile private var reconciled: Pair<Long, Long>? = null

  val trail = ProbabilityTrail()

  @Volatile var prob90: Int = 0

  @Volatile
  var lastProbability: Double = 0.0
    private set

  @Volatile
  var lastCheatProbability: Double = 0.0
    private set

  @Volatile
  var lastLabelProbabilities: Map<String, Double> = emptyMap()
    private set

  @Volatile private var lastModelProbabilities: Map<String, Double> = emptyMap()

  @Volatile
  var lastVerdicts: Map<String, ModelVerdict> = emptyMap()
    private set

  fun profile(): StreamProfile = configManager.streamProfile ?: FALLBACK_PROFILE

  fun currentProfile(): StreamProfile? = configManager.streamProfile

  fun address(key: DetectionKey): String =
    if (key.model == profile().primary.id) key.label else key.address()

  fun keyOf(address: String): DetectionKey =
    DetectionKey.parseAddress(address) ?: DetectionKey(profile().primary.id, address)

  fun reconcile() {
    val p = configManager.streamProfile ?: return
    val local = configManager.settings.localAi
    val stamp = p.crc to local.generation
    if (reconciled == stamp) return
    detections.reconcile(p, { local.models[it]?.enabled != false }, FALLBACK_MODEL.id)
    reconciled = stamp
  }

  val buffer: Double
    get() = detections.activeMax(profile())

  val shownModelTitle: String
    get() = profile().let { if (it.active.size > 1) it.primary.displayTitle else "" }

  val primaryBuffer: Double
    get() = detections.modelMax(profile().primary.id)

  fun labelBuffer(label: String): Double = detections.snapshot()[keyOf(label)] ?: 0.0

  fun labelBufferSnapshot(): Map<String, Double> =
    detections.snapshot().entries.associate { address(it.key) to it.value }

  fun bufferSnapshot(): Map<DetectionKey, Double> = detections.snapshot()

  fun trackedLabels(): Set<String> = labelBufferSnapshot().keys

  val declaredLabels: List<String>
    get() {
      if (!configManager.settings.localAi.split) return emptyList()
      return profile().active.flatMap { m ->
        m.labels.filterNot { it in m.legitLabels }.map { address(DetectionKey(m.id, it)) }
      }
    }

  fun modelCards(): List<ModelCard> {
    val p = profile()
    val buffers = detections.snapshot()
    val probabilities = lastLabelProbabilities
    val latest = lastModelProbabilities
    return p.active.map { m ->
      val primary = m.id == p.primary.id
      val rows =
        buffers
          .filterKeys { it.model == m.id }
          .map { (key, value) ->
            val at = address(key)
            CardRow(at, value, probabilities[at] ?: if (primary) lastProbability else 0.0)
          }
          .sortedByDescending { it.buffer }
      ModelCard(
        m.displayTitle,
        m.buffer.flag,
        detections.modelMax(m.id),
        rows,
        primary,
        latest[m.id] ?: 0.0,
        m.id,
      )
    }
  }

  fun leadingLabelName(model: ModelSpec): String {
    val catalog = configManager.labelCatalog
    val own = detections.snapshot().filterKeys { it.model == model.id }.mapKeys { address(it.key) }
    return catalog.leading(own)?.let(catalog::displayName) ?: ""
  }

  fun restoreBuffer(value: Double) = restoreLabelBuffer(LabelKey.UNATTRIBUTED, value)

  fun restoreLabelBuffer(label: String, value: Double) {
    val key = keyOf(label)
    if (restoreBlocks.any { it(key) }) return
    detections.restore(listOf(key to value))
  }

  fun blockRestore(match: (DetectionKey) -> Boolean) {
    restoreBlocks += match
  }

  fun clearWhere(match: (DetectionKey) -> Boolean): Map<DetectionKey, Double> =
    detections.clear(match)

  fun feedBuffers(verdict: LabelledVerdict): Map<String, Double> {
    val p = profile()
    return detections
      .feed(p.primary, verdict, EffectiveSettings.of(p.primary, configManager.settings.localAi))
      .crossed
  }

  fun feed(
    model: ModelSpec,
    owner: ModelSpec?,
    verdict: LabelledVerdict,
    settings: EffectiveSettings,
    ownerSettings: EffectiveSettings,
  ): FeedResult =
    if (owner == null) {
      detections.feed(model, verdict, settings)
    } else {
      detections.feed(owner, sharedInto(owner, verdict), ownerSettings, model.buffer.weight, true)
    }

  private fun sharedInto(owner: ModelSpec, verdict: LabelledVerdict): LabelledVerdict {
    val values = LinkedHashMap<String, Double>()
    for ((label, probability) in verdict.values) {
      val target = if (label in owner.labels) label else LabelKey.UNATTRIBUTED
      values[target] = maxOf(values[target] ?: 0.0, probability)
    }
    return LabelledVerdict(values, verdict.attributed)
  }

  fun note(
    model: ModelSpec,
    primary: Boolean,
    verdict: LabelledVerdict,
    settings: EffectiveSettings,
    probability: Double,
  ) {
    val cheat = verdict.values.values.maxOrNull() ?: 0.0
    lastModelProbabilities = lastModelProbabilities + (model.id to probability)
    val record =
      if (verdict.attributed) {
        ModelVerdict(model.id, System.currentTimeMillis(), cheat, verdict.values)
      } else {
        ModelVerdict(model.id, System.currentTimeMillis(), probability, emptyMap())
      }
    lastVerdicts = lastVerdicts + (model.id to record)
    if (primary) {
      lastProbability = probability
      trail.record(probability)
    }
    if (!verdict.attributed) return
    val own = verdict.values.mapKeys { address(DetectionKey(model.id, it.key)) }
    val prefix = "${model.id}/"
    lastLabelProbabilities =
      lastLabelProbabilities.filterKeys { if (primary) '/' in it else !it.startsWith(prefix) } + own
    if (primary) {
      lastCheatProbability = cheat
      val top = verdict.values.maxByOrNull { it.value }?.key.orEmpty()
      if (cheat > settings.thresholds(top).cheat) prob90++
    }
  }

  private companion object {
    const val FALLBACK_FLAG = 50.0
    const val FALLBACK_RESET = 25.0
    const val FALLBACK_MULTIPLIER = 100.0

    val FALLBACK_MODEL =
      ModelSpec(
        "primary",
        "Shard",
        null,
        Schedule.ATTACK,
        1,
        1,
        1,
        0,
        0,
        setOf(0),
        emptyList(),
        LabelMode.SINGLE,
        emptySet(),
        emptyMap(),
        emptyMap(),
        BufferSpec(FALLBACK_FLAG, FALLBACK_RESET, FALLBACK_MULTIPLIER, 1.0, 1),
        1.0,
        ModelRole.ALL,
      )
    val FALLBACK_PROFILE =
      StreamProfile(0, emptyList(), 1, 2, listOf(FALLBACK_MODEL), FALLBACK_MODEL, 0, "")
  }
}
