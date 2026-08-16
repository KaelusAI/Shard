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
package ac.shard.config

import java.io.File

internal object ConfigMigrations {
  const val LATEST_VERSION = 4
  const val MONITOR_LATEST_VERSION = 2
  const val MITIGATIONS_LATEST_VERSION = 1

  private val DEBUG_CATEGORIES_UNMUTED_BY_DROPPING_THE_SWITCH =
    listOf("debug/categories/api-error/timeout", "debug/categories/api-error/service-unavailable")

  private const val VERSION_WITH_ATTACK_WINDOWS = 4

  private val LATEST_BY_FILE =
    mapOf(
      "config.yml" to LATEST_VERSION,
      "monitor.yml" to MONITOR_LATEST_VERSION,
      "mitigations.yml" to MITIGATIONS_LATEST_VERSION,
    )

  private val VERSION_RE = Regex("""^\s*config-version:\s*(\d+)""", RegexOption.MULTILINE)

  private val LEGACY_V1_URL_RE =
    Regex("""^\s*server:\s*"https://api\.shard\.ac/v1/inference"\s*$""", RegexOption.MULTILINE)

  fun latestVersion(fileName: String): Int = LATEST_BY_FILE[fileName] ?: LATEST_VERSION

  fun readVersion(file: File, fileName: String = "config.yml"): Int {
    val text =
      runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return latestVersion(fileName)
    return VERSION_RE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
  }

  // yaml-config-updater uses `/` as the path separator because `.` is valid inside key names.
  fun forcedDropsForUpgradeFrom(
    currentVersion: Int,
    fileName: String = "config.yml",
    file: File? = null,
  ): List<String> {
    if (currentVersion >= latestVersion(fileName)) return emptyList()
    val drops = mutableListOf("config-version")
    if (currentVersion < VERSION_WITH_ATTACK_WINDOWS) {
      drops += "ai/continuous"
      if (file != null && holdsLegacyInferenceUrl(file)) drops += "ai/server"
    }
    if (fileName == "config.yml") {
      drops += "debug/enabled"
      drops += DEBUG_CATEGORIES_UNMUTED_BY_DROPPING_THE_SWITCH
      drops += "ai/damage-reduction"
      drops += "mitigation"
    }
    return drops
  }

  private fun holdsLegacyInferenceUrl(file: File): Boolean {
    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
    return LEGACY_V1_URL_RE.containsMatchIn(text)
  }
}
