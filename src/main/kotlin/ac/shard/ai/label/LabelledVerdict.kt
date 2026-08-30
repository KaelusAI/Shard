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
package ac.shard.ai.label

import java.util.Locale

enum class LabelMode(val wire: String) {
  SINGLE("single"),
  MULTI_LABEL("multilabel"),
  MULTI_CLASS("multiclass");

  companion object {
    fun fromConfig(value: String?): LabelMode? =
      when (value?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')) {
        "single" -> SINGLE
        "multilabel",
        "multi_label" -> MULTI_LABEL
        "multiclass",
        "multi_class" -> MULTI_CLASS
        else -> null
      }
  }
}

data class LabelledVerdict(val values: Map<String, Double>, val attributed: Boolean)

data class LabelThresholds(val cheat: Double, val legit: Double) {
  companion object {
    fun parse(cheat: Double?, legit: Double?): LabelThresholds? {
      val c = cheat?.takeIf { it > 0.0 && it <= 1.0 }
      val l = legit?.takeIf { c != null && it >= 0.0 && it < c }
      return if (c != null && l != null) LabelThresholds(c, l) else null
    }
  }
}

class VerdictResolver(
  private val settings: () -> Settings,
  private val warn: (String) -> Unit,
  private val severe: (String) -> Unit = warn,
) {
  @Suppress("LongParameterList")
  class Settings(
    val labels: List<String>,
    val mode: LabelMode?,
    val split: Boolean,
    val maxTracked: Int,
    val legitClasses: Set<String>,
    val thresholdedLabels: Set<String> = emptySet(),
    val attributionMargin: Double = DEFAULT_ATTRIBUTION_MARGIN,
  ) {
    val multiClassReady: Boolean
      get() =
        labels.isNotEmpty() &&
          labels.any { it in legitClasses } &&
          labels.all { it in legitClasses || it in thresholdedLabels }
  }

  @Suppress("ReturnCount", "CyclomaticComplexMethod")
  fun resolve(
    probabilities: List<Double>?,
    overall: Double,
    named: Map<String, Double>? = null,
  ): LabelledVerdict {
    val current = settings()
    val labels = current.labels
    val usable = ordered(named, labels) ?: probabilities?.takeIf { it.isNotEmpty() }

    val declared = current.mode ?: if (labels.size > 1) LabelMode.MULTI_LABEL else LabelMode.SINGLE
    if (declared == LabelMode.MULTI_CLASS && !current.multiClassReady) {
      reportSevere("multiclass-not-ready") {
        "ai.labels.mode is multiclass, but no label is named a clean class, or one still runs " +
          "on the shared cheat threshold. Either breaks a softmax model. Falling back to a " +
          "single merged buffer."
      }
      return unsplit(labels, usable, current, overall, declared)
    }
    val collapse = !current.split || (declared == LabelMode.SINGLE && labels.size > 1)
    if (labels.isEmpty() || collapse) {
      return unsplit(labels, usable, current, overall, declared)
    }
    if (usable == null && declared == LabelMode.SINGLE && labels.size == 1) {
      return LabelledVerdict(mapOf(labels[0] to sanitize(overall)), attributed = true)
    }
    if (usable == null) {
      report("missing:${labels.size}") {
        "Model declares ${labels.size} label(s) but the response carries no probabilities. " +
          "Dropping the window and freezing the buffers rather than scoring on the scalar."
      }
      return LabelledVerdict(emptyMap(), attributed = false)
    }
    if (usable.size != labels.size) {
      report("size:${labels.size}:${usable.size}") {
        "Model declares ${labels.size} label(s) but the response carries ${usable.size} " +
          "probabilities; dropping the window rather than scoring it on the scalar"
      }
      return LabelledVerdict(emptyMap(), attributed = false)
    }

    val paired = labels.zip(usable.map(::sanitize)).take(current.maxTracked)
    if (paired.size < labels.size) {
      report("cap:${labels.size}") {
        "Model declares ${labels.size} labels, more than the ${current.maxTracked} this server " +
          "tracks; the rest are ignored. Raise ai.labels.max-tracked if that is wrong."
      }
    }
    return when (declared) {
      LabelMode.MULTI_CLASS -> multiClass(paired, current)
      else -> LabelledVerdict(paired.filterNot { it.first in current.legitClasses }.toMap(), true)
    }
  }

  @Suppress("ReturnCount")
  private fun ordered(named: Map<String, Double>?, labels: List<String>): List<Double>? {
    if (named.isNullOrEmpty() || labels.isEmpty()) return null
    val canonical = named.mapNotNull { (k, v) -> LabelKey.canonical(k)?.let { it to v } }.toMap()
    val missing = labels.filterNot { it in canonical }
    if (missing.isEmpty()) return labels.map { canonical.getValue(it) }
    report("named:${missing.sorted()}") {
      "Model sent probabilities by name but left out $missing; falling back to the merged verdict"
    }
    return null
  }

  @Suppress("ReturnCount")
  private fun unsplit(
    labels: List<String>,
    probabilities: List<Double>?,
    current: Settings,
    overall: Double,
    declared: LabelMode,
  ): LabelledVerdict {
    if (declared == LabelMode.MULTI_CLASS && labels.none { it in current.legitClasses }) {
      report("multiclass-merge-blind") {
        "Model declares multiclass but no label is named a clean class, so the loudest one may " +
          "be the one that means the player is fine. Dropping the window."
      }
      return LabelledVerdict(emptyMap(), attributed = false)
    }
    if (probabilities != null && probabilities.size == labels.size && labels.isNotEmpty()) {
      val merged =
        labels
          .zip(probabilities)
          .filterNot { it.first in current.legitClasses }
          .maxOfOrNull { it.second } ?: 0.0
      return LabelledVerdict(mapOf(LabelKey.UNATTRIBUTED to sanitize(merged)), attributed = true)
    }
    if (declared != LabelMode.SINGLE) {
      report("scalar:${declared.wire}") {
        "Model declares ${declared.wire} but this answer has no usable per-label probabilities. " +
          "Dropping the window rather than scoring it on the scalar."
      }
      return LabelledVerdict(emptyMap(), attributed = false)
    }
    return LabelledVerdict(mapOf(LabelKey.UNATTRIBUTED to sanitize(overall)), attributed = true)
  }

  private fun multiClass(
    paired: List<Pair<String, Double>>,
    current: Settings,
  ): LabelledVerdict {
    val cheats =
      paired.filterNot { it.first in current.legitClasses }.sortedByDescending { it.second }
    if (cheats.isEmpty()) return LabelledVerdict(emptyMap(), true)
    val cleanClasses = (paired.size - cheats.size).coerceAtLeast(1)
    val clean =
      paired
        .filter { it.first in current.legitClasses }
        .sumOf { it.second }
        .coerceIn(MIN_CLEAN, 1.0)
    val evidence = (1.0 - clean).coerceAtLeast(0.0) * cleanClasses
    val strength = evidence / (evidence + cheats.size * clean)
    val decisive =
      cheats.size == 1 || cheats[0].second - cheats[1].second >= current.attributionMargin
    val key = if (decisive) cheats[0].first else LabelKey.UNATTRIBUTED
    return LabelledVerdict(mapOf(key to sanitize(strength)), attributed = true)
  }

  private fun sanitize(value: Double): Double =
    when {
      value.isNaN() -> {
        report("nan") { "Model returned a probability that is not a number; treating it as zero" }
        0.0
      }
      value < 0.0 || value > 1.0 -> {
        report("range") {
          "Model returned a probability outside 0..1 ($value); clamping it. A value above one " +
            "would otherwise flag a player from a single response."
        }
        value.coerceIn(0.0, 1.0)
      }
      else -> value
    }

  private inline fun report(signature: String, message: () -> String) {
    if (reported.add(signature)) warn("[AiCheck] ${message()}")
  }

  private inline fun reportSevere(signature: String, message: () -> String) {
    if (reported.add(signature)) severe("[AiCheck] ${message()}")
  }

  companion object {
    const val DEFAULT_ATTRIBUTION_MARGIN = 0.15

    private const val MIN_CLEAN = 1e-9

    private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun forgetReports() {
      reported.clear()
    }
  }
}
