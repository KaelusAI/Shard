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

import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorToken
import ac.shard.monitor.core.PING_UNAVAILABLE
import ac.shard.monitor.core.fillTemplate
import ac.shard.monitor.core.formatDecimal
import ac.shard.monitor.core.formatSigned
import ac.shard.monitor.core.padInt
import kotlin.math.abs

data class MonitorFrameRequest(
  val sample: MonitorSample,
  val settings: MonitorSettings,
  val pingValue: Int,
  val trend: Double,
  val selfView: Boolean,
  val unavailableHeadline: String,
)

class MonitorFrameBuilder {
  fun build(request: MonitorFrameRequest, config: MonitorHudRuntimeConfig): MonitorFrame {
    val sample = request.sample
    val available = sample.dataPresent && sample.aiActive
    val raw = rawValues(request, config)
    val themed = raw.mapValues { (token, value) ->
      fillTemplate(config.themes.template(request.settings.theme, token)) { key ->
        if (key == token.key) value else null
      }
    }
    val headline =
      if (available) headlineOf(request, config, themed) else request.unavailableHeadline
    return MonitorFrame(
      targetId = sample.targetId,
      targetName = sample.targetName,
      headline = headline,
      placeholders = placeholdersOf(raw, themed, headline),
      progress =
        if (available) config.bossBar.progressFor(sample.probability, sample.buffer) else 0f,
      severity =
        if (available) config.bossBar.severityFor(sample.probability) else MonitorSeverity.CALM,
      dataPresent = sample.dataPresent,
      aiActive = sample.aiActive,
    )
  }

  private fun rawValues(
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
  ): Map<MonitorToken, String> {
    val sample = request.sample
    val format = config.format
    val ping =
      if (request.pingValue == PING_UNAVAILABLE) {
        config.chat.unknownPing
      } else {
        padInt(request.pingValue, format.pingMinWidth)
      }
    return mapOf(
      MonitorToken.NAME to truncateName(sample.targetName, config.behavior),
      MonitorToken.PROB to formatDecimal(sample.probability * PERCENT_SCALE, format.probDecimals),
      MonitorToken.TREND to formatSigned(request.trend, format.trendDecimals),
      MonitorToken.BUFFER to formatDecimal(sample.buffer, format.bufferDecimals),
      MonitorToken.PING to ping,
      MonitorToken.DMG to formatDecimal(sample.damageMultiplier, format.dmgDecimals),
      MonitorToken.PROB90 to sample.prob90.toString(),
    )
  }

  private fun headlineOf(
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
    themed: Map<MonitorToken, String>,
  ): String =
    config
      .tokens(request.settings.mode)
      .mapNotNull { token -> partFor(token, request, config, themed) }
      .joinToString(config.themes.separator(request.settings.theme))

  private fun partFor(
    token: MonitorToken,
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
    themed: Map<MonitorToken, String>,
  ): String? {
    val settings = request.settings
    val behavior = config.behavior
    return when (token) {
      MonitorToken.NAME -> themed[token].takeIf { nameVisible(settings.showName, request.selfView) }
      MonitorToken.TREND ->
        if (settings.showTrend) themed[token] else neutralFor(behavior.neutralTrend, behavior)
      MonitorToken.PING ->
        if (settings.showPing) themed[token] else neutralFor(behavior.neutralPing, behavior)
      MonitorToken.DMG ->
        if (dmgVisible(settings.showDmg, request.sample.damageMultiplier, config)) themed[token]
        else neutralFor(behavior.neutralDmg, behavior)
      else -> themed[token]
    }
  }

  private fun nameVisible(mode: MonitorNameMode, selfView: Boolean): Boolean =
    when (mode) {
      MonitorNameMode.NEVER -> false
      MonitorNameMode.AUTO -> !selfView
      MonitorNameMode.ALWAYS -> true
    }

  private fun dmgVisible(
    showDmg: Boolean,
    multiplier: Double,
    config: MonitorHudRuntimeConfig,
  ): Boolean {
    val hidden =
      config.format.dmgHideWhenDefault &&
        abs(multiplier - DEFAULT_DMG_MULTIPLIER) < MULTIPLIER_EPSILON
    return showDmg && !hidden
  }

  private fun neutralFor(template: String, behavior: MonitorBehaviorConfig): String? {
    if (!behavior.keepLength || !behavior.showNeutralWhenHidden) {
      return null
    }
    return template.ifBlank { null }
  }

  private fun truncateName(name: String, behavior: MonitorBehaviorConfig): String {
    val max = behavior.nameMaxLength
    if (max <= 0 || name.length <= max) {
      return name
    }
    val cut = maxOf(1, max - behavior.nameTruncateSuffix.length)
    return name.substring(0, cut) + behavior.nameTruncateSuffix
  }

  private fun placeholdersOf(
    raw: Map<MonitorToken, String>,
    themed: Map<MonitorToken, String>,
    headline: String,
  ): Map<String, String> {
    val values = HashMap<String, String>(raw.size * 2 + 1)
    for ((token, value) in raw) {
      values[token.key] = value
      values[token.key + THEMED_SUFFIX] = themed.getValue(token)
    }
    values[PLACEHOLDER_HEADLINE] = headline
    return values
  }

  private companion object {
    const val PERCENT_SCALE = 100.0
    const val DEFAULT_DMG_MULTIPLIER = 1.0
    const val MULTIPLIER_EPSILON = 0.0001
  }
}
