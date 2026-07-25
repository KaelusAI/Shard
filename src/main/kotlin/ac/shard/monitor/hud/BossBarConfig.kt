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
package ac.shard.monitor.hud

import ac.shard.config.ConfigView
import java.util.Locale
import java.util.logging.Logger
import net.kyori.adventure.bossbar.BossBar

enum class BossBarProgressSource {
  PROB,
  BUFFER,
  NONE,
}

data class BossBarConfig(
  val enabled: Boolean,
  val title: String,
  val overlay: BossBar.Overlay,
  val progressSource: BossBarProgressSource,
  val bufferMax: Double,
  val fixedColor: BossBar.Color?,
  val maxBars: Int,
  val overflowTemplate: String,
  val yellowThreshold: Double,
  val redThreshold: Double,
) {
  fun progressFor(probability: Double, buffer: Double): Float {
    val value =
      when (progressSource) {
        BossBarProgressSource.PROB -> probability
        BossBarProgressSource.BUFFER -> buffer / bufferMax
        BossBarProgressSource.NONE -> 1.0
      }
    if (value.isNaN() || value.isInfinite()) {
      return 0f
    }
    return value.toFloat().coerceIn(0f, 1f)
  }

  fun severityFor(probability: Double): MonitorSeverity =
    when {
      probability >= redThreshold -> MonitorSeverity.ALERT
      probability >= yellowThreshold -> MonitorSeverity.WATCH
      else -> MonitorSeverity.CALM
    }

  companion object {
    fun from(config: ConfigView, logger: Logger): BossBarConfig {
      val bufferMax =
        config.getDouble("outputs.bossbar.progress-buffer-max", DEFAULT_BOSSBAR_BUFFER_MAX)
      val thresholds = readThresholds(config, logger)
      return BossBarConfig(
        enabled = config.getBoolean("outputs.bossbar.enabled", false),
        title = config.getString("outputs.bossbar.title", DEFAULT_BOSSBAR_TITLE),
        overlay = parseOverlay(config.getString("outputs.bossbar.overlay", ""), logger),
        progressSource = parseProgress(config.getString("outputs.bossbar.progress", ""), logger),
        bufferMax = if (bufferMax > 0.0) bufferMax else DEFAULT_BOSSBAR_BUFFER_MAX,
        fixedColor = parseColor(config.getString("outputs.bossbar.color", ""), logger),
        maxBars =
          config
            .getInt("outputs.bossbar.max-bars", DEFAULT_MAX_BOSS_BARS)
            .coerceIn(1, MAX_BOSS_BARS),
        overflowTemplate =
          config.getString("outputs.bossbar.overflow-template", DEFAULT_BOSSBAR_OVERFLOW),
        yellowThreshold = thresholds.first,
        redThreshold = thresholds.second,
      )
    }

    private fun parseOverlay(raw: String, logger: Logger): BossBar.Overlay {
      val normalized = raw.trim().uppercase(Locale.ROOT)
      val parsed =
        BossBar.Overlay.entries.firstOrNull { it.name == normalized }
          ?: BossBar.Overlay.PROGRESS.takeIf { normalized.isEmpty() }
      if (parsed == null) {
        logger.warning("[Monitor] unknown outputs.bossbar.overlay '$raw'; using PROGRESS.")
      }
      return parsed ?: BossBar.Overlay.PROGRESS
    }

    private fun parseProgress(raw: String, logger: Logger): BossBarProgressSource {
      val normalized = raw.trim().uppercase(Locale.ROOT)
      val parsed =
        BossBarProgressSource.entries.firstOrNull { it.name == normalized }
          ?: BossBarProgressSource.PROB.takeIf { normalized.isEmpty() }
      if (parsed == null) {
        logger.warning("[Monitor] unknown outputs.bossbar.progress '$raw'; using prob.")
      }
      return parsed ?: BossBarProgressSource.PROB
    }

    private fun parseColor(raw: String, logger: Logger): BossBar.Color? {
      val normalized = raw.trim().uppercase(Locale.ROOT)
      if (normalized.isEmpty() || normalized == AUTO_COLOR) {
        return null
      }
      val parsed = BossBar.Color.entries.firstOrNull { it.name == normalized }
      if (parsed == null) {
        logger.warning("[Monitor] unknown outputs.bossbar.color '$raw'; using auto.")
      }
      return parsed
    }

    private fun readThresholds(config: ConfigView, logger: Logger): Pair<Double, Double> {
      val yellow =
        config
          .getDouble("outputs.bossbar.color-thresholds.yellow", DEFAULT_THRESHOLD_YELLOW)
          .coerceIn(0.0, 1.0)
      val red =
        config
          .getDouble("outputs.bossbar.color-thresholds.red", DEFAULT_THRESHOLD_RED)
          .coerceIn(0.0, 1.0)
      if (yellow > red) {
        logger.warning(
          "[Monitor] outputs.bossbar.color-thresholds.yellow ($yellow) is above red ($red); swapping."
        )
      }
      return if (yellow > red) red to yellow else yellow to red
    }
  }
}

internal const val AUTO_COLOR = "AUTO"
internal const val MAX_BOSS_BARS = 6
internal const val DEFAULT_MAX_BOSS_BARS = 3
internal const val DEFAULT_BOSSBAR_BUFFER_MAX = 50.0
internal const val DEFAULT_THRESHOLD_YELLOW = 0.40
internal const val DEFAULT_THRESHOLD_RED = 0.75
internal const val DEFAULT_BOSSBAR_TITLE = "{headline}"
internal const val DEFAULT_BOSSBAR_OVERFLOW = "<gray>… and {count} more monitored</gray>"
