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
package ac.shard.monitor.view

import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.fillTemplate
import ac.shard.monitor.core.formatDecimal
import kotlin.math.roundToInt
import org.bukkit.entity.Player

internal class ViewTagRenderer(private val sampler: MonitorSampler) {
  fun render(target: Player, pingDisplay: String, config: ViewRuntimeConfig): RenderedTag {
    val sample = sampler.sample(target)
    val probabilityValue =
      if (sample.aiActive) {
        formatDecimal(sample.probability * PERCENT_MULTIPLIER, config.probDecimals)
      } else {
        config.fallbackProb
      }
    val bufferValue =
      if (sample.aiActive) {
        formatDecimal(sample.buffer, config.bufferDecimals)
      } else {
        config.fallbackBuffer
      }
    val belowScore =
      if (sample.aiActive) {
        (sample.probability * PERCENT_MULTIPLIER).roundToInt().coerceAtLeast(ZERO_BELOW_SCORE)
      } else {
        ZERO_BELOW_SCORE
      }

    val values = mapOf("prob" to probabilityValue, "buffer" to bufferValue, "ping" to pingDisplay)

    return RenderedTag(
      applyTemplate(config.prefixTemplate, values),
      applyTemplate(config.suffixTemplate, values),
      applyTemplate(config.belowTemplate, values),
      belowScore,
    )
  }

  private fun applyTemplate(template: String, values: Map<String, String>): String {
    return fillTemplate(template, values)
  }

  private companion object {
    const val ZERO_BELOW_SCORE = 0
    const val PERCENT_MULTIPLIER = 100.0
  }
}
