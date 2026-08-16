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
@file:Suppress("MagicNumber")

package ac.shard.editor

sealed interface Verdict {
  data object Allowed : Verdict

  data class Refused(val reason: String) : Verdict
}

sealed interface FieldRule {
  fun check(value: String): Verdict
}

data object BoolRule : FieldRule {
  override fun check(value: String): Verdict =
    if (value == "true" || value == "false") Verdict.Allowed
    else Verdict.Refused("expected true or false, got $value")
}

data class NumberRule(val min: Double, val max: Double) : FieldRule {
  override fun check(value: String): Verdict {
    val parsed = value.toDoubleOrNull()
    return when {
      parsed == null -> Verdict.Refused("expected a number, got $value")
      !parsed.isFinite() -> Verdict.Refused("$value is not a real number")
      parsed < min || parsed > max -> Verdict.Refused("$value is outside $min..$max")
      else -> Verdict.Allowed
    }
  }
}

data class IntRule(val min: Long, val max: Long) : FieldRule {
  override fun check(value: String): Verdict {
    val parsed = value.toLongOrNull()
    return when {
      parsed == null -> Verdict.Refused("expected a whole number, got $value")
      parsed < min || parsed > max -> Verdict.Refused("$value is outside $min..$max")
      else -> Verdict.Allowed
    }
  }
}

data class EnumRule(val allowed: Set<String>) : FieldRule {
  override fun check(value: String): Verdict =
    if (unquote(value) in allowed) Verdict.Allowed
    else Verdict.Refused("expected one of ${allowed.sorted().joinToString("/")}, got $value")
}

data class TextRule(val maxLength: Int, val pattern: Regex? = null) : FieldRule {
  override fun check(value: String): Verdict {
    val body = unquote(value)
    return when {
      body.length > maxLength -> Verdict.Refused("longer than $maxLength characters")
      pattern != null && !pattern.matches(body) -> Verdict.Refused("does not look like a $pattern")
      else -> Verdict.Allowed
    }
  }
}

class PairRule(
  private val min: Double,
  private val max: Double,
  private val holds: (Double, Double) -> Boolean,
) : FieldRule {
  override fun check(value: String): Verdict {
    val parts = unquote(value).removeSurrounding("[", "]").split(",").map { it.trim() }
    if (parts.size != 2) return Verdict.Refused("expected two numbers in brackets, got $value")
    val low = parts[0].toDoubleOrNull()
    val high = parts[1].toDoubleOrNull()
    return when {
      low == null || high == null -> Verdict.Refused("expected two numbers, got $value")
      low < min || high > max -> Verdict.Refused("$value is outside $min..$max")
      !holds(low, high) -> Verdict.Refused("$value must rise from left to right")
      else -> Verdict.Allowed
    }
  }
}

internal fun unquote(value: String): String =
  value.trim().removeSurrounding("\"").removeSurrounding("'")

private val SHARE = NumberRule(0.0, 1.0)
private val BUFFER = NumberRule(0.0, 100_000.0)

private val CONFIG_RULES: Map<String, FieldRule> =
  mapOf(
    "locale" to EnumRule(setOf("en", "ru")),
    "ai/enabled" to BoolRule,
    "ai/continuous" to BoolRule,
    "ai/buffer/flag" to NumberRule(1.0, 100_000.0),
    "ai/buffer/reset-on-flag" to BUFFER,
    "ai/buffer/multiplier" to NumberRule(1.0, 10_000.0),
    "ai/buffer/decrease" to NumberRule(0.0, 1_000.0),
    "ai/worldguard/enabled" to BoolRule,
    "ai/worldguard/mode" to EnumRule(setOf("skip-detection", "skip-punishment")),
    "ai/worldguard/flag-overrides-list" to BoolRule,
    "ai/backoff/initial-duration" to IntRule(1, 60),
    "ai/backoff/max-duration" to IntRule(1, 3_600),
    "ai/backoff/multiplier" to NumberRule(1.0, 100.0),
    "ai/batch/enabled" to BoolRule,
    "ai/batch/max-size" to IntRule(1, 256),
    "ai/batch/max-delay-ms" to IntRule(0, 10_000),
    "ai/retry/max-attempts" to IntRule(1, 10),
    "ai/retry/initial-delay-ms" to IntRule(0, 60_000),
    "ai/retry/max-delay-ms" to IntRule(0, 600_000),
    "ai/retry/multiplier" to NumberRule(1.0, 100.0),
    "ai/retry/jitter" to SHARE,
    "ai/persistent-buffer/enabled" to BoolRule,
    "ai/persistent-buffer/ttl-hours" to IntRule(0, 8_760),
    "ai/persistent-buffer/cap-on-restore" to BUFFER,
    "ai/persistent-buffer/decay-rate-per-hour" to NumberRule(0.0, 1_000.0),
    "ai/persistent-buffer/disconnect-window-seconds" to IntRule(0, 86_400),
    "ai/persistent-buffer/save-threshold" to BUFFER,
    "exemptions/bedrock" to BoolRule,
    "telemetry/enabled" to BoolRule,
    "client-brand/disconnect-blacklisted-forge-versions" to BoolRule,
    "alerts/print-to-console" to BoolRule,
    "history/enabled" to BoolRule,
    "redis/enabled" to BoolRule,
    "network/enabled" to BoolRule,
    "network/name" to TextRule(32, Regex("[A-Za-z0-9 _.:-]+")),
    "network/channel" to TextRule(64, Regex("[A-Za-z0-9_.:-]+")),
    "network/share/alerts" to BoolRule,
    "network/share/suspicious" to BoolRule,
    "network/suspicious-sync/ttl-seconds" to IntRule(1, 86_400),
    "network/suspicious-sync/refresh-seconds" to IntRule(1, 86_400),
    "suspicious/alerts/buffer" to BUFFER,
    "cancel-duplicate-packet" to BoolRule,
    "force-cancel-duplicate-packet" to BoolRule,
    "ignore-duplicate-packet-rotation" to BoolRule,
    "debug/categories/probability" to BoolRule,
    "debug/categories/api-error/timeout" to BoolRule,
    "debug/categories/api-error/network" to BoolRule,
    "debug/categories/api-error/rate-limited" to BoolRule,
    "debug/categories/api-error/service-unavailable" to BoolRule,
    "debug/categories/persistent-buffer" to BoolRule,
    "debug/categories/rate-limit" to BoolRule,
    "debug/categories/worldguard" to BoolRule,
    "debug/categories/packet-duplication" to BoolRule,
  )

private val MONITOR_RULES: Map<String, FieldRule> =
  mapOf(
    "update" to IntRule(1, 200),
    "storage/per-player" to BoolRule,
    "limits/max-sessions" to IntRule(1, 1_000),
    "limits/max-viewers-per-target" to IntRule(0, 1_000),
    "auto/suspicious-buffer" to NumberRule(-1.0, 100_000.0),
    "auto/exit-ratio" to SHARE,
    "auto/refresh-ticks" to IntRule(1, 12_000),
    "auto/linger-ticks" to IntRule(0, 12_000),
    "auto/combat-ticks" to IntRule(1, 12_000),
  )

private val MITIGATION_RULES: Map<String, FieldRule> =
  mapOf(
    "enabled" to BoolRule,
    "skip/bedrock" to BoolRule,
    "skip/regions" to EnumRule(setOf("follow-ai", "ignore")),
    "score/neutral" to SHARE,
    "score/clamp" to PairRule(0.0, 1.0) { low, high -> low < high },
    "score/range" to PairRule(-1_000.0, 1_000.0) { low, high -> low < high },
    "score/half-life-minutes" to NumberRule(0.1, 10_080.0),
    "score/min-answers" to IntRule(0, 1_000_000),
    "score/forget-rate" to NumberRule(0.0, 10.0),
    "score/persist/enabled" to BoolRule,
    "score/persist/ttl-hours" to IntRule(0, 8_760),
    "score/persist/cap-on-restore" to NumberRule(-1_000.0, 1_000.0),
  )

private val RULES_BY_FILE =
  mapOf(
    "config.yml" to CONFIG_RULES,
    "monitor.yml" to MONITOR_RULES,
    "mitigations.yml" to MITIGATION_RULES,
  )

private val WORLD_NAME = Regex("""\*|[A-Za-z0-9_.-]{1,64}""")
private val REGION_NAME = Regex("""[A-Za-z0-9_-]{1,64}""")
private const val MAX_WORLDS = 128
private const val MAX_REGIONS_PER_WORLD = 512
private const val GLOBAL_REGION = "__global__"

private class PairedRule(
  val file: String,
  val lower: String,
  val higher: String,
  val complaint: String,
  val holds: (Double, Double) -> Boolean,
)

private val PAIRED =
  listOf(
    PairedRule(
      "config.yml",
      "ai/buffer/reset-on-flag",
      "ai/buffer/flag",
      "ai/buffer/reset-on-flag must stay below ai/buffer/flag, or every answer flags again",
    ) { low, high ->
      low < high
    },
    PairedRule(
      "config.yml",
      "ai/backoff/initial-duration",
      "ai/backoff/max-duration",
      "ai/backoff/initial-duration must not exceed ai/backoff/max-duration",
    ) { low, high ->
      low <= high
    },
  )

enum class Loosening {
  WHEN_HIGHER,
  WHEN_LOWER,
  WHEN_OFF,
  WHEN_ON,
  WHEN_SKIPPING_DETECTION,
}

private val LOOSENS: Map<Pair<String, String>, Loosening> =
  mapOf(
    ("config.yml" to "ai/enabled") to Loosening.WHEN_OFF,
    ("config.yml" to "ai/continuous") to Loosening.WHEN_OFF,
    ("config.yml" to "ai/worldguard/enabled") to Loosening.WHEN_ON,
    ("config.yml" to "ai/worldguard/mode") to Loosening.WHEN_SKIPPING_DETECTION,
    ("config.yml" to "ai/buffer/flag") to Loosening.WHEN_HIGHER,
    ("config.yml" to "ai/buffer/multiplier") to Loosening.WHEN_LOWER,
    ("config.yml" to "ai/buffer/decrease") to Loosening.WHEN_HIGHER,
    ("config.yml" to "ai/persistent-buffer/enabled") to Loosening.WHEN_OFF,
    ("config.yml" to "ai/persistent-buffer/ttl-hours") to Loosening.WHEN_LOWER,
    ("config.yml" to "ai/persistent-buffer/decay-rate-per-hour") to Loosening.WHEN_HIGHER,
    ("config.yml" to "exemptions/bedrock") to Loosening.WHEN_ON,
    ("config.yml" to "suspicious/alerts/buffer") to Loosening.WHEN_HIGHER,
    ("config.yml" to "history/enabled") to Loosening.WHEN_OFF,
    ("config.yml" to "alerts/print-to-console") to Loosening.WHEN_OFF,
    ("config.yml" to "ai/worldguard/flag-overrides-list") to Loosening.WHEN_ON,
    ("config.yml" to "network/share/alerts") to Loosening.WHEN_OFF,
    ("config.yml" to "network/share/suspicious") to Loosening.WHEN_OFF,
    ("config.yml" to "ai/persistent-buffer/cap-on-restore") to Loosening.WHEN_LOWER,
    ("config.yml" to "ai/persistent-buffer/save-threshold") to Loosening.WHEN_HIGHER,
    ("config.yml" to "ai/persistent-buffer/disconnect-window-seconds") to Loosening.WHEN_LOWER,
    ("monitor.yml" to "limits/max-sessions") to Loosening.WHEN_LOWER,
    ("monitor.yml" to "limits/max-viewers-per-target") to Loosening.WHEN_LOWER,
    ("monitor.yml" to "auto/suspicious-buffer") to Loosening.WHEN_HIGHER,
    ("mitigations.yml" to "enabled") to Loosening.WHEN_OFF,
    ("mitigations.yml" to "skip/bedrock") to Loosening.WHEN_ON,
    ("mitigations.yml" to "score/neutral") to Loosening.WHEN_HIGHER,
    ("mitigations.yml" to "score/half-life-minutes") to Loosening.WHEN_LOWER,
    ("mitigations.yml" to "score/min-answers") to Loosening.WHEN_HIGHER,
    ("mitigations.yml" to "score/forget-rate") to Loosening.WHEN_HIGHER,
    ("mitigations.yml" to "score/persist/enabled") to Loosening.WHEN_OFF,
  )

internal object EditorSchema {

  fun editablePaths(file: String): Set<String> = RULES_BY_FILE[file].orEmpty().keys

  fun loosenedPaths(file: String): Set<String> =
    LOOSENS.keys.filter { it.first == file }.map { it.second }.toSet()

  fun loosens(file: String, path: String, was: String, now: String): Boolean {
    val direction = LOOSENS[file to path] ?: return false
    val before = unquote(was)
    val after = unquote(now)
    return when (direction) {
      Loosening.WHEN_OFF -> before == "true" && after == "false"
      Loosening.WHEN_ON -> before == "false" && after == "true"
      Loosening.WHEN_SKIPPING_DETECTION -> after == "skip-detection" && before != after
      Loosening.WHEN_HIGHER -> compare(before, after) { a, b -> b > a }
      Loosening.WHEN_LOWER -> compare(before, after) { a, b -> b < a }
    }
  }

  private fun compare(was: String, now: String, worse: (Double, Double) -> Boolean): Boolean {
    val before = was.toDoubleOrNull()
    val after = now.toDoubleOrNull()
    return before != null && after != null && worse(before, after)
  }

  fun check(file: String, path: String, value: String): Verdict {
    val rule = RULES_BY_FILE[file]?.get(path)
    return rule?.check(value) ?: Verdict.Refused("$file:$path is not editable")
  }

  fun pairedPaths(file: String): Set<String> =
    PAIRED.filter { it.file == file }.flatMap { listOf(it.lower, it.higher) }.toSet()

  fun checkTogether(file: String, merged: Map<String, String>): Verdict {
    val broken =
      PAIRED.filter { it.file == file }
        .firstOrNull { pair ->
          val low = merged[pair.lower]?.toDoubleOrNull()
          val high = merged[pair.higher]?.toDoubleOrNull()
          low != null && high != null && !pair.holds(low, high)
        }
    return if (broken == null) Verdict.Allowed else Verdict.Refused(broken.complaint)
  }

  fun checkRegions(entries: Map<String, List<String>>): Verdict {
    val problem =
      when {
        entries["*"].orEmpty().contains(GLOBAL_REGION) ->
          "$GLOBAL_REGION under * would switch the check off on every world"
        entries.size > MAX_WORLDS -> "more than $MAX_WORLDS worlds"
        entries.keys.any { !WORLD_NAME.matches(it) } ->
          "a world name that is not a world name: " + entries.keys.first { !WORLD_NAME.matches(it) }
        entries.values.any { it.size > MAX_REGIONS_PER_WORLD } ->
          "more than $MAX_REGIONS_PER_WORLD regions in one world"
        else ->
          entries.values
            .flatten()
            .firstOrNull { !REGION_NAME.matches(it) }
            ?.let { "a region name that is not a region name: $it" }
      }
    return if (problem == null) Verdict.Allowed else Verdict.Refused(problem)
  }
}
