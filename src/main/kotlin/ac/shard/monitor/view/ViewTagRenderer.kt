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

import ac.shard.ai.label.LabelCatalog
import ac.shard.monitor.core.LabelFocus
import ac.shard.monitor.core.MonitorLabelInfo
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSampler
import ac.shard.monitor.core.fillTemplate
import ac.shard.monitor.core.formatDecimal
import kotlin.math.roundToInt
import org.bukkit.entity.Player

internal class ViewTagRenderer(
  private val sampler: MonitorSampler,
  private val labelCatalog: LabelCatalog,
  private val labelFocus: (Player) -> String = { LabelFocus.AUTO },
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun render(
    viewer: Player,
    target: Player,
    pingDisplay: String,
    config: ViewRuntimeConfig,
  ): RenderedTag {
    val sample = sampler.sample(target)
    val pinned = labelFocus(viewer)
    val focus = if (sample.aiActive) focusOf(sample, config, pinned) else null

    val probabilityValue =
      if (sample.aiActive) {
        formatDecimal(probabilityOf(sample, focus) * PERCENT_MULTIPLIER, config.probDecimals)
      } else {
        config.fallbackProb
      }
    val bufferValue =
      if (sample.aiActive) {
        formatDecimal(focus?.buffer ?: sample.buffer, config.bufferDecimals)
      } else {
        config.fallbackBuffer
      }
    val belowScore =
      if (sample.aiActive) {
        (probabilityOf(sample, focus) * PERCENT_MULTIPLIER)
          .roundToInt()
          .coerceAtLeast(ZERO_BELOW_SCORE)
      } else {
        ZERO_BELOW_SCORE
      }

    val labels = MonitorLabelInfo.tracked(sample)
    val widest = labels.maxOfOrNull { shortName(it.label, config).length } ?: 0
    val name = focus?.let { shortName(it.label, config) }?.padEnd(widest).orEmpty()
    val values =
      mapOf(
        "prob" to probabilityValue,
        "buffer" to bufferValue,
        "ping" to pingDisplay,
        "tier" to sample.tier,
        "label" to name,
        "label_tag" to
          if (name.isEmpty()) "" else fillTemplate(config.labelTemplate, mapOf("label" to name)),
      )

    return RenderedTag(
      applyTemplate(config.prefixTemplate, values),
      applyTemplate(config.suffixTemplate, values),
      applyTemplate(config.belowTemplate, values),
      belowScore,
    )
  }

  private fun focusOf(
    sample: MonitorSample,
    config: ViewRuntimeConfig,
    pinned: String,
  ): MonitorLabelInfo? {
    val labels = MonitorLabelInfo.tracked(sample)
    if (labels.isEmpty()) return null
    val period = config.labelRotateMillis
    val rotates = period > 0L && pinned == LabelFocus.AUTO
    val at = if (rotates) ((clock() / period) % labels.size).toInt() else 0
    return labels.firstOrNull { it.label == pinned } ?: labels[at]
  }

  private fun shortName(label: String, config: ViewRuntimeConfig): String {
    val name = if (config.labelUsesKey) label else labelCatalog.displayName(label)
    val max = config.labelMaxLength
    if (max <= 0 || name.length <= max) return name
    return name.substring(0, maxOf(1, max - 1)) + "…"
  }

  private fun probabilityOf(sample: MonitorSample, focus: MonitorLabelInfo?): Double =
    if (focus == null) sample.probability else sample.labelProbabilities[focus.label] ?: 0.0

  private fun applyTemplate(template: String, values: Map<String, String>): String {
    return fillTemplate(template, values)
  }

  private companion object {
    const val ZERO_BELOW_SCORE = 0
    const val PERCENT_MULTIPLIER = 100.0
  }
}
