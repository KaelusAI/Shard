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
package ac.shard.monitor.core

import ac.shard.config.ConfigView
import kotlin.math.pow

data class MonitorFormatConfig(
  val probDecimals: Int,
  val trendDecimals: Int,
  val trendThreshold: Double,
  val bufferDecimals: Int,
  val pingMinWidth: Int,
  val dmgDecimals: Int,
  val dmgHideWhenDefault: Boolean,
) {
  companion object {
    fun from(config: ConfigView): MonitorFormatConfig {
      val trendDecimals =
        config.getInt("format.trend.decimals", DEFAULT_TREND_DECIMALS).coerceIn(0, MAX_DECIMALS)
      val configuredThreshold = config.getDouble("format.trend.threshold", 0.0)
      return MonitorFormatConfig(
        probDecimals =
          config.getInt("format.prob.decimals", DEFAULT_PROB_DECIMALS).coerceIn(0, MAX_DECIMALS),
        trendDecimals = trendDecimals,
        trendThreshold =
          if (configuredThreshold > 0.0) configuredThreshold else autoTrendThreshold(trendDecimals),
        bufferDecimals =
          config
            .getInt("format.buffer.decimals", DEFAULT_BUFFER_DECIMALS)
            .coerceIn(0, MAX_DECIMALS),
        pingMinWidth =
          config
            .getInt("format.ping.min-width", DEFAULT_PING_MIN_WIDTH)
            .coerceIn(0, MAX_PING_WIDTH),
        dmgDecimals =
          config.getInt("format.dmg.decimals", DEFAULT_DMG_DECIMALS).coerceIn(0, MAX_DECIMALS),
        dmgHideWhenDefault = config.getBoolean("format.dmg.hide-when-default", false),
      )
    }

    private fun autoTrendThreshold(decimals: Int): Double =
      AUTO_TREND_THRESHOLD_SCALE * TEN.pow(-decimals.toDouble())
  }
}

internal const val DEFAULT_PROB_DECIMALS = 0
internal const val DEFAULT_TREND_DECIMALS = 2
internal const val DEFAULT_BUFFER_DECIMALS = 2
internal const val DEFAULT_DMG_DECIMALS = 2
internal const val DEFAULT_PING_MIN_WIDTH = 2
internal const val MAX_DECIMALS = 6
internal const val MAX_PING_WIDTH = 5
internal const val AUTO_TREND_THRESHOLD_SCALE = 0.5
private const val TEN = 10.0
