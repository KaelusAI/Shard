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

import ac.shard.checks.impl.ai.AiCheck
import ac.shard.monitor.core.fillTemplate
import ac.shard.monitor.core.formatDecimal
import ac.shard.player.PlayerDataManager
import kotlin.math.roundToInt
import org.bukkit.entity.Player

internal class ViewTagRenderer(private val playerDataManager: PlayerDataManager) {
  fun render(target: Player, state: TargetTeamState, config: ViewRuntimeConfig): RenderedTag {
    val shardTarget = playerDataManager.getPlayer(target)
    if (shardTarget == null) {
      val fallbackValues =
        mapOf(
          "prob" to config.fallbackProb,
          "buffer" to config.fallbackBuffer,
          "ping" to state.resolvePingDisplay(target.ping, config),
        )
      return RenderedTag(
        applyTemplate(config.prefixTemplate, fallbackValues),
        applyTemplate(config.suffixTemplate, fallbackValues),
        applyTemplate(config.belowTemplate, fallbackValues),
        ZERO_BELOW_SCORE,
      )
    }
    val aiCheck = shardTarget.checkManager.getCheck(AiCheck::class.java)

    val probabilityValue =
      if (aiCheck == null) {
        config.fallbackProb
      } else {
        formatDecimal(aiCheck.lastProbability * PERCENT_MULTIPLIER, config.probDecimals)
      }
    val belowScore =
      if (aiCheck == null) {
        ZERO_BELOW_SCORE
      } else {
        (aiCheck.lastProbability * PERCENT_MULTIPLIER).roundToInt().coerceAtLeast(ZERO_BELOW_SCORE)
      }

    val bufferValue =
      if (aiCheck == null) {
        config.fallbackBuffer
      } else {
        formatDecimal(aiCheck.buffer, config.bufferDecimals)
      }

    val values =
      mapOf(
        "prob" to probabilityValue,
        "buffer" to bufferValue,
        "ping" to state.resolvePingDisplay(target.ping, config),
      )

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
