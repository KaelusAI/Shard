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

import ac.shard.ai.label.LabelCatalog
import ac.shard.monitor.core.LabelFocus
import ac.shard.monitor.core.MonitorLabelInfo
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
  val collectVisible: Boolean = false,
)

@Suppress("TooManyFunctions")
class MonitorFrameBuilder(
  private val labelCatalog: LabelCatalog,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private fun focusOf(
    labels: List<MonitorFrameLabel>,
    config: MonitorHudRuntimeConfig,
    pinned: String,
  ): MonitorFrameLabel? {
    if (labels.isEmpty()) return null
    val period = config.behavior.labelRotateMillis
    val rotates = period > 0L && pinned == LabelFocus.AUTO
    val at = if (rotates) ((clock() / period) % labels.size).toInt() else 0
    return labels.firstOrNull { it.key == pinned } ?: labels[at]
  }

  fun build(request: MonitorFrameRequest, config: MonitorHudRuntimeConfig): MonitorFrame {
    val sample = request.sample
    val available = sample.dataPresent && sample.aiActive
    val labels = frameLabels(sample, config)
    val focus = focusOf(labels, config, request.settings.labelFocus)
    val raw = rawValues(request, config, labels, focus)
    val extra = extraPlaceholders(sample, config, labels, focus)
    val themed = raw.mapValues { (token, value) ->
      if (token in VANISH_WHEN_EMPTY && value.isEmpty()) {
        ""
      } else {
        dropEmptyTags(
          fillTemplate(config.themes.template(request.settings.theme, token)) { key ->
            if (key == token.key) value else extra[token]?.get(key)
          }
        )
      }
    }
    val headline =
      if (available) headlineOf(request, config, themed, labels) else request.unavailableHeadline
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
      labels = labels,
    )
  }

  private fun frameLabels(
    sample: MonitorSample,
    config: MonitorHudRuntimeConfig,
  ): List<MonitorFrameLabel> =
    MonitorLabelInfo.tracked(sample)
      .map {
        MonitorFrameLabel(
          it.label,
          shorten(
            labelCatalog.displayName(it.label),
            config.behavior.labelMaxLength,
            config.behavior.nameTruncateSuffix,
          ),
          formatDecimal(it.buffer, config.format.bufferDecimals),
          formatDecimal(
            (sample.labelProbabilities[it.label] ?: 0.0) * PERCENT_SCALE,
            config.format.probDecimals,
          ),
        )
      }
      .map { it.copy(written = written(it, config.behavior)) }

  private fun rawValues(
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
    labels: List<MonitorFrameLabel>,
    focus: MonitorFrameLabel?,
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
      MonitorToken.PROB to
        steadyProbability(focus, labels, config.behavior).ifEmpty {
          formatDecimal(sample.probability * PERCENT_SCALE, format.probDecimals)
        },
      MonitorToken.TREND to formatSigned(request.trend, format.trendDecimals),
      MonitorToken.BUFFER to
        steadyBuffer(focus, labels, config.behavior).ifEmpty {
          formatDecimal(sample.buffer, format.bufferDecimals)
        },
      MonitorToken.LABEL to steadyName(focus, labels, config.behavior),
      MonitorToken.LABELS to labelsText(labels),
      MonitorToken.PING to ping,
      MonitorToken.DMG to formatDecimal(sample.damageMultiplier, format.dmgDecimals),
      MonitorToken.PROB90 to sample.prob90.toString(),
      MonitorToken.COLLECT to (sample.collect?.status ?: ""),
      MonitorToken.INFERENCE to (sample.inference?.status ?: ""),
      MonitorToken.TIER to tierText(sample.tier, format.tierUppercase),
      MonitorToken.SCORE to formatDecimal(sample.score, format.scoreDecimals),
      MonitorToken.RULE to ruleText(sample),
    )
  }

  private fun written(label: MonitorFrameLabel, behavior: MonitorBehaviorConfig): String =
    if (behavior.labelUsesKey) {
      shorten(label.key, behavior.labelMaxLength, behavior.nameTruncateSuffix)
    } else {
      label.name
    }

  private fun steadyName(
    focus: MonitorFrameLabel?,
    labels: List<MonitorFrameLabel>,
    behavior: MonitorBehaviorConfig,
  ): String {
    val name = focus?.let { written(it, behavior) } ?: return ""
    return if (behavior.labelKeepWidth) {
      name.padEnd(labels.maxOf { written(it, behavior).length })
    } else {
      name
    }
  }

  private fun steadyProbability(
    focus: MonitorFrameLabel?,
    labels: List<MonitorFrameLabel>,
    behavior: MonitorBehaviorConfig,
  ): String {
    val written = focus?.probability ?: return ""
    return if (behavior.labelKeepWidth) {
      written.padStart(labels.maxOf { it.probability.length })
    } else {
      written
    }
  }

  private fun steadyBuffer(
    focus: MonitorFrameLabel?,
    labels: List<MonitorFrameLabel>,
    behavior: MonitorBehaviorConfig,
  ): String {
    val written = focus?.buffer ?: return ""
    return if (behavior.labelKeepWidth) written.padStart(labels.maxOf { it.buffer.length })
    else written
  }

  private fun steadySuffix(
    focus: MonitorFrameLabel?,
    labels: List<MonitorFrameLabel>,
    behavior: MonitorBehaviorConfig,
  ): String {
    if (focus == null) return ""
    return " " + steadyName(focus, labels, behavior)
  }

  private fun labelsText(labels: List<MonitorFrameLabel>): String =
    labels.joinToString(" · ") { "${it.name} ${it.probability}% ◆${it.buffer}" }

  private fun extraPlaceholders(
    sample: MonitorSample,
    config: MonitorHudRuntimeConfig,
    labels: List<MonitorFrameLabel>,
    focus: MonitorFrameLabel?,
  ): Map<MonitorToken, Map<String, String>> {
    val out = mutableMapOf<MonitorToken, Map<String, String>>()
    val at = labels.indexOf(focus) + 1
    val name = steadyName(focus, labels, config.behavior)
    val position = if (labels.size <= 1) "" else " $at/${labels.size}"
    val more = if (labels.size <= 1) "" else " +${labels.size - 1}"
    out[MonitorToken.BUFFER] = mapOf("label" to name)
    out[MonitorToken.PROB] =
      mapOf(
        "label" to name,
        "label_suffix" to steadySuffix(focus, labels, config.behavior),
        "position" to position,
      )
    out[MonitorToken.LABEL] =
      mapOf(
        "label" to name,
        "more" to more,
        "position" to position,
        "count" to labels.size.toString(),
      )
    sample.collect?.let {
      out[MonitorToken.COLLECT] =
        mapOf(
          "status" to it.status,
          "label" to it.label,
          "windows" to it.windows.toString(),
          "elapsed" to it.elapsed,
        )
    }
    sample.inference?.let { out[MonitorToken.INFERENCE] = mapOf("status" to it.status) }
    return out
  }

  private fun headlineOf(
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
    themed: Map<MonitorToken, String>,
    labels: List<MonitorFrameLabel>,
  ): String =
    config
      .tokens(request.settings.mode)
      .mapNotNull { token -> partFor(token, request, config, themed, labels) }
      .joinToString(config.themes.separator(request.settings.theme))

  private fun mitigationPart(
    token: MonitorToken,
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
    themed: Map<MonitorToken, String>,
  ): String? =
    when (token) {
      MonitorToken.TIER ->
        if (tierVisible(request.sample.tier, config)) themed[token]
        else neutralFor(config.behavior.neutralTier, config.behavior)
      MonitorToken.SCORE ->
        themed[token].takeIf { !config.format.scoreHideWhenIdle || request.sample.score > 0.0 }
      else -> themed[token].takeIf { request.sample.rule.isNotBlank() }
    }

  private fun recordingPart(
    token: MonitorToken,
    request: MonitorFrameRequest,
    themed: Map<MonitorToken, String>,
  ): String? {
    val settings = request.settings
    val sample = request.sample
    val (enabled, info) =
      if (token == MonitorToken.COLLECT) settings.showCollect to sample.collect
      else settings.showInference to sample.inference
    return themed[token].takeIf { recordingVisible(request, enabled, info) }
  }

  @Suppress("LongParameterList")
  private fun partFor(
    token: MonitorToken,
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
    themed: Map<MonitorToken, String>,
    labels: List<MonitorFrameLabel>,
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
      MonitorToken.LABEL -> themed[token]?.takeIf { it.isNotEmpty() }
      MonitorToken.LABELS -> themed[token].takeIf { labels.isNotEmpty() }
      MonitorToken.COLLECT,
      MonitorToken.INFERENCE -> recordingPart(token, request, themed)
      MonitorToken.TIER,
      MonitorToken.SCORE,
      MonitorToken.RULE -> mitigationPart(token, request, config, themed)
      else -> themed[token]
    }
  }

  private fun recordingVisible(
    request: MonitorFrameRequest,
    enabled: Boolean,
    value: Any?,
  ): Boolean = enabled && request.collectVisible && value != null

  private fun nameVisible(mode: MonitorNameMode, selfView: Boolean): Boolean =
    when (mode) {
      MonitorNameMode.NEVER -> false
      MonitorNameMode.AUTO -> !selfView
      MonitorNameMode.ALWAYS -> true
    }

  private fun ruleText(sample: MonitorSample): String {
    if (sample.rule.isBlank()) return ""
    val since = sample.appliedForMillis
    return if (since <= 0L) sample.rule else "${sample.rule} ${compactDuration(since)}"
  }

  private fun compactDuration(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    return if (minutes <= 0L) "${seconds}s" else "${minutes}m${seconds % SECONDS_PER_MINUTE}s"
  }

  private fun tierText(tier: String, uppercase: Boolean): String =
    if (uppercase) tier else tier.lowercase(java.util.Locale.US)

  private fun tierVisible(tier: String, config: MonitorHudRuntimeConfig): Boolean =
    !(config.format.tierHideWhenNone && tier == NO_TIER)

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

  private fun truncateName(name: String, behavior: MonitorBehaviorConfig): String =
    shorten(name, behavior.nameMaxLength, behavior.nameTruncateSuffix)

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
    val VANISH_WHEN_EMPTY = setOf(MonitorToken.LABEL, MonitorToken.LABELS)

    fun shorten(text: String, max: Int, suffix: String): String {
      if (max <= 0 || text.length <= max) return text
      return text.substring(0, maxOf(1, max - suffix.length)) + suffix
    }

    val EMPTY_TAG_PAIR = Regex("<([a-z_]+)(?::[^<>]*)?></\\1>")

    fun dropEmptyTags(rendered: String): String {
      var out = rendered
      while (true) {
        val next = EMPTY_TAG_PAIR.replace(out, "")
        if (next == out) return out
        out = next
      }
    }

    const val PERCENT_SCALE = 100.0
    const val NO_TIER = "NONE"
    const val MILLIS_PER_SECOND = 1000L
    const val SECONDS_PER_MINUTE = 60L
    const val DEFAULT_DMG_MULTIPLIER = 1.0
    const val MULTIPLIER_EPSILON = 0.0001
  }
}
