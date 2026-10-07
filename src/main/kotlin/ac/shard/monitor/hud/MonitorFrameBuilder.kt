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

import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelCatalog
import ac.shard.ai.label.LabelKey
import ac.shard.monitor.core.LabelFocus
import ac.shard.monitor.core.ModelFilter
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
import ac.shard.utils.WallClock
import kotlin.math.abs

data class MonitorFrameRequest(
  val sample: MonitorSample,
  val settings: MonitorSettings,
  val pingValue: Int,
  val trend: Double,
  val selfView: Boolean,
  val unavailableHeadline: String,
  val collectVisible: Boolean = false,
  val trendSettled: Boolean = false,
)

@Suppress("TooManyFunctions")
class MonitorFrameBuilder(
  private val labelCatalog: LabelCatalog,
  private val clock: WallClock = WallClock.SYSTEM,
) {
  private fun focusOf(
    labels: List<MonitorFrameLabel>,
    config: MonitorHudRuntimeConfig,
    pinned: String,
  ): MonitorFrameLabel? {
    if (labels.isEmpty()) return null
    val period = config.behavior.labelRotateMillis
    val rotates = period > 0L && LabelFocus.rotates(pinned)
    val at = if (rotates) ((clock() / period) % labels.size).toInt() else 0
    return labels.firstOrNull { it.key == pinned } ?: labels[at]
  }

  private fun showsAll(settings: MonitorSettings, labels: List<MonitorFrameLabel>): Boolean =
    settings.labelFocus == LabelFocus.ALL && labels.isNotEmpty()

  fun build(original: MonitorFrameRequest, config: MonitorHudRuntimeConfig): MonitorFrame {
    val request = withSelectedLead(original)
    val sample = request.sample
    val available = sample.dataPresent && sample.aiActive
    val labels = frameLabels(sample, config)
    val focus = focusOf(labels, config, request.settings.labelFocus)
    val raw = rawValues(request, config, labels, focus)
    val model = modelOf(sample, focus)
    val prefix = modelPrefix(model, config.themes.separator(request.settings.theme))
    val extra = extraPlaceholders(sample, config, labels, focus, prefix)
    val themed = raw.mapValues { (token, value) ->
      if (token in VANISH_WHEN_EMPTY && value.isEmpty()) {
        ""
      } else {
        dropEmptyTags(
          fillTemplate(templateFor(token, request, config)) { key ->
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
      placeholders = placeholdersOf(raw, themed, headline, model, prefix),
      progress =
        if (available) config.bossBar.progressFor(sample.probability, sample.buffer) else 0f,
      severity =
        if (available) config.bossBar.severityFor(sample.probability) else MonitorSeverity.CALM,
      dataPresent = sample.dataPresent,
      aiActive = sample.aiActive,
      labels = labels,
      allLabels = showsAll(request.settings, labels),
      others = if (available) otherModels(request, config) else emptyList(),
    )
  }

  private fun otherModels(
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
  ): List<Map<String, String>> {
    val format = config.format
    val theme = request.settings.theme
    return request.sample.models
      .filter { !it.primary && ModelFilter.shows(request.settings.models, it.id, primary = false) }
      .map { card ->
        val prob = formatDecimal(card.probability * PERCENT_SCALE, format.probDecimals)
        val buffer = formatDecimal(card.buffer, format.bufferDecimals)
        mapOf(
          MODEL to card.title,
          MonitorToken.PROB.key to prob,
          MonitorToken.PROB.key + THEMED_SUFFIX to
            themedValue(config.themes.template(theme, MonitorToken.PROB), MonitorToken.PROB, prob),
          MonitorToken.BUFFER.key to buffer,
          MonitorToken.BUFFER.key + THEMED_SUFFIX to
            themedValue(
              config.themes.template(theme, MonitorToken.BUFFER),
              MonitorToken.BUFFER,
              buffer,
            ),
        )
      }
  }

  private fun themedValue(template: String, token: MonitorToken, value: String): String =
    dropEmptyTags(
      fillTemplate(template) { key ->
        when (key) {
          token.key -> value
          "model_prefix",
          "label_suffix" -> ""
          else -> null
        }
      }
    )

  private fun templateFor(
    token: MonitorToken,
    request: MonitorFrameRequest,
    config: MonitorHudRuntimeConfig,
  ): String {
    val theme = request.settings.theme
    val inference = request.sample.inference.takeIf { token == MonitorToken.INFERENCE }
    return when {
      token == MonitorToken.INFERENCE_ERRORS || inference?.fault == true ->
        config.themes.inferenceError(theme)
      inference?.named == true -> config.themes.inferenceModels(theme)
      else -> config.themes.template(theme, token)
    }
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
      MonitorToken.MODELS to othersText(request, config),
      MonitorToken.LABEL to steadyName(focus, labels, config.behavior),
      MonitorToken.LABELS to labelsText(labels),
      MonitorToken.PING to ping,
      MonitorToken.DMG to formatDecimal(sample.damageMultiplier, format.dmgDecimals),
      MonitorToken.PROB90 to sample.prob90.toString(),
      MonitorToken.COLLECT to (sample.collect?.status ?: ""),
      MonitorToken.INFERENCE to (sample.inference?.status ?: ""),
      MonitorToken.INFERENCE_ERRORS to (sample.inference?.status ?: ""),
      MonitorToken.TIER to tierText(sample.tier, format.tierUppercase),
      MonitorToken.SCORE to formatDecimal(sample.score, format.scoreDecimals),
      MonitorToken.RULE to ruleText(sample),
    )
  }

  private fun othersText(request: MonitorFrameRequest, config: MonitorHudRuntimeConfig): String {
    val format = config.format
    val theme = request.settings.theme
    val separator = config.themes.separator(theme)
    return request.sample.models
      .filter { !it.primary && ModelFilter.shows(request.settings.models, it.id, primary = false) }
      .joinToString(separator) { card ->
        val probability = formatDecimal(card.probability * PERCENT_SCALE, format.probDecimals)
        val template = config.themes.template(theme, MonitorToken.PROB)
        val title = if (MODEL_PREFIX_KEY in template) "" else modelPrefix(card.title, separator)
        val prob =
          title +
            fillTemplate(template) { key ->
              when (key) {
                MonitorToken.PROB.key -> probability
                "model_prefix" -> modelPrefix(card.title, separator)
                "label_suffix" -> ""
                else -> null
              }
            }
        val buffer =
          fillTemplate(config.themes.template(theme, MonitorToken.BUFFER)) { key ->
            if (key == MonitorToken.BUFFER.key) formatDecimal(card.buffer, format.bufferDecimals)
            else null
          }
        dropEmptyTags(prob) + separator + dropEmptyTags(buffer)
      }
  }

  private fun written(label: MonitorFrameLabel, behavior: MonitorBehaviorConfig): String {
    val bare = DetectionKey.parseAddress(label.key)?.label ?: label.key
    if (LabelKey.isReserved(bare)) return ""
    val own = if (behavior.labelUsesKey) bare else labelCatalog.ownName(label.key)
    return shorten(own, behavior.labelMaxLength, behavior.nameTruncateSuffix)
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
    val name = focus?.let { steadyName(it, labels, behavior) }.orEmpty()
    return if (name.isBlank()) "" else " $name"
  }

  private fun labelsText(labels: List<MonitorFrameLabel>): String =
    labels.joinToString(" · ") { "${it.name} ${it.probability}% ◆${it.buffer}" }

  private fun extraPlaceholders(
    sample: MonitorSample,
    config: MonitorHudRuntimeConfig,
    labels: List<MonitorFrameLabel>,
    focus: MonitorFrameLabel?,
    prefix: String,
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
        MODEL_PREFIX to prefix,
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
    sample.inference?.let {
      out[MonitorToken.INFERENCE] = mapOf("status" to it.status)
      out[MonitorToken.INFERENCE_ERRORS] = mapOf("status" to it.status)
    }
    return out
  }

  private fun withSelectedLead(request: MonitorFrameRequest): MonitorFrameRequest {
    val sample = request.sample
    val filter = request.settings.models
    val primaryHidden = sample.models.any { it.primary && !ModelFilter.shows(filter, it.id, true) }
    val lead =
      sample.models
        .firstOrNull { !it.primary && ModelFilter.shows(filter, it.id, false) }
        ?.takeIf { primaryHidden } ?: return request
    return request.copy(
      sample =
        sample.copy(
          probability = lead.probability,
          buffer = lead.buffer,
          prob90 = 0,
          leadingLabel = null,
          labelBuffers = emptyMap(),
          labelProbabilities = emptyMap(),
          declaredLabels = emptyList(),
          model = lead.title,
          models = sample.models.filter { it !== lead },
        )
    )
  }

  private fun modelOf(sample: MonitorSample, focus: MonitorFrameLabel?): String =
    focus?.let { labelCatalog.ownerTitle(it.key) } ?: sample.model

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
    val wanted = token != MonitorToken.INFERENCE_ERRORS || sample.inference?.fault == true
    return themed[token].takeIf { wanted && recordingVisible(request, enabled, info) }
  }

  private fun focusPart(
    token: MonitorToken,
    all: Boolean,
    themed: Map<MonitorToken, String>,
    prefix: String,
  ): String? {
    val prefixed = if (prefix.isEmpty()) "" else "<gray>$prefix</gray>"
    return when {
      !all -> themed[token]
      token == MonitorToken.PROB -> themed[MonitorToken.LABELS]?.let { prefixed + it }
      else -> null
    }
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
      MonitorToken.PROB,
      MonitorToken.BUFFER ->
        focusPart(
          token,
          showsAll(settings, labels),
          themed,
          modelPrefix(request.sample.model, config.themes.separator(settings.theme)),
        )
      MonitorToken.TREND ->
        quietPart(
          settings.showTrend,
          config.format.trendHideWhenSettled && request.trendSettled,
          themed[token],
          behavior.neutralTrend,
          behavior,
        )
      MonitorToken.PING ->
        if (settings.showPing) themed[token] else neutralFor(behavior.neutralPing, behavior)
      MonitorToken.DMG ->
        quietPart(
          settings.showDmg,
          dmgDefault(request.sample.damageMultiplier, config),
          themed[token],
          behavior.neutralDmg,
          behavior,
        )
      MonitorToken.LABEL,
      MonitorToken.MODELS -> themed[token]?.takeIf { it.isNotEmpty() }
      MonitorToken.LABELS -> themed[token].takeIf { labels.isNotEmpty() }
      MonitorToken.COLLECT,
      MonitorToken.INFERENCE,
      MonitorToken.INFERENCE_ERRORS -> recordingPart(token, request, themed)
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

  private fun quietPart(
    shown: Boolean,
    atRest: Boolean,
    value: String?,
    neutral: String,
    behavior: MonitorBehaviorConfig,
  ): String? =
    when {
      !shown -> neutralFor(neutral, behavior)
      atRest -> null
      else -> value
    }

  private fun dmgDefault(multiplier: Double, config: MonitorHudRuntimeConfig): Boolean =
    config.format.dmgHideWhenDefault &&
      abs(multiplier - DEFAULT_DMG_MULTIPLIER) < MULTIPLIER_EPSILON

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
    model: String,
    prefix: String,
  ): Map<String, String> {
    val values = HashMap<String, String>()
    for ((token, value) in raw) {
      values[token.key] = value
      values[token.key + THEMED_SUFFIX] = themed.getValue(token)
    }
    values[PLACEHOLDER_HEADLINE] = headline
    values[MODEL] = model
    values[MODEL_PREFIX] = prefix
    return values
  }

  private companion object {
    val VANISH_WHEN_EMPTY = setOf(MonitorToken.LABEL, MonitorToken.LABELS, MonitorToken.MODELS)

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

    const val MODEL = "model"
    const val MODEL_PREFIX = "model_prefix"

    fun modelPrefix(model: String, separator: String): String =
      if (model.isEmpty()) "" else model + separator

    const val PERCENT_SCALE = 100.0
    const val MODEL_PREFIX_KEY = "{model_prefix}"
    const val NO_TIER = "NONE"
    const val MILLIS_PER_SECOND = 1000L
    const val SECONDS_PER_MINUTE = 60L
    const val DEFAULT_DMG_MULTIPLIER = 1.0
    const val MULTIPLIER_EPSILON = 0.0001
  }
}
