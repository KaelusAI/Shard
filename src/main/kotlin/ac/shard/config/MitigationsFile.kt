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
@file:Suppress("MagicNumber", "TooManyFunctions", "ReturnCount")

package ac.shard.config

import ac.shard.mitigation.Fact
import ac.shard.mitigation.MitigationRule
import ac.shard.mitigation.MitigationSettings
import ac.shard.mitigation.MitigationTier
import ac.shard.mitigation.RuleCondition
import ac.shard.mitigation.RuleEffects
import ac.shard.mitigation.RuleTiming
import ac.shard.mitigation.ScoreSettings
import ac.shard.mitigation.SkipSettings
import org.spongepowered.configurate.ConfigurationNode

private const val SECOND = 1_000L
private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L
private const val FOLLOW_AI = "follow-ai"
private const val MAX_FORGET_RATE = 10.0

object MitigationsFile {

  const val LATEST_VERSION = 1

  val DEFAULT_SCORE =
    ScoreSettings(
      neutral = 0.20,
      clampLow = 0.02,
      clampHigh = 0.96,
      halfLifeMillis = 20 * MINUTE,
      floor = -6.0,
      ceiling = 40.0,
      minAnswers = 5,
      forgetRate = 0.5,
      persistEnabled = true,
      persistTtlMillis = 72 * HOUR,
      capOnRestore = 6.0,
    )

  private val DEFAULT_SKIP =
    SkipSettings(
      bedrock = true,
      followAiRegions = true,
    )

  val OFF =
    MitigationSettings(
      enabled = false,
      score = DEFAULT_SCORE,
      skip = DEFAULT_SKIP,
      rules = emptyList(),
    )

  fun read(root: ConfigurationNode, complaints: MutableList<String>): MitigationSettings =
    MitigationSettings(
      enabled = root.node("enabled").getBoolean(false),
      score = readScore(root.node("score"), complaints),
      skip = readSkip(root.node("skip")),
      rules = readRules(root.node("rules"), complaints),
    )

  private fun readRules(
    node: ConfigurationNode,
    complaints: MutableList<String>,
  ): List<MitigationRule> {
    if (node.virtual() || !node.isList) return emptyList()
    val seen = mutableSetOf<String>()

    return node.childrenList().mapIndexedNotNull { index, child ->
      val id = child.node("id").string?.trim()
      if (id.isNullOrEmpty()) {
        complaints += "rules[$index] has no id and was dropped"
        return@mapIndexedNotNull null
      }
      if (!seen.add(id)) {
        complaints += "rules[$index] repeats the id $id and was dropped"
        return@mapIndexedNotNull null
      }
      val level = MitigationTier.parse(child.node("level").string)
      if (level == null) {
        complaints += "rule $id must set level to none, low, mid or high and was dropped"
        return@mapIndexedNotNull null
      }
      val effects = readEffects(child.node("then"), id, complaints)
      val entry = condition(child.node("when"), "rule $id", complaints)
      if (entry == null) {
        complaints += "rule $id has no readable when block and was dropped"
        return@mapIndexedNotNull null
      }
      MitigationRule(
        id = id,
        order = index,
        level = level,
        enabled = child.node("enabled").getBoolean(true),
        entry = entry,
        until = condition(child.node("until"), "rule $id", complaints),
        effects = effects,
        timing = readTiming(child.node("timing")),
      )
    }
  }

  private fun readEffects(
    node: ConfigurationNode,
    id: String,
    complaints: MutableList<String>,
  ): RuleEffects {
    if (node.virtual() || !node.isMap) return RuleEffects.Flat(emptyMap())
    val scale = node.node("scale")
    return if (scale.virtual()) {
      RuleEffects.Flat(readFlat(node, id, complaints))
    } else {
      readScale(scale, id, complaints) ?: RuleEffects.Flat(emptyMap())
    }
  }

  private fun readFlat(
    node: ConfigurationNode,
    id: String,
    complaints: MutableList<String>,
  ): Map<String, Double> {
    val effects = linkedMapOf<String, Double>()
    node.childrenMap().forEach { (key, child) ->
      val channel = key.toString().lowercase()
      if (channel !in MitigationSettings.CHANNELS) {
        complaints += "rule $id sets an unknown channel $channel"
        return@forEach
      }
      effects[channel] = clampChannel(channel, child.getDouble(1.0))
    }
    return effects
  }

  private fun readScale(
    node: ConfigurationNode,
    id: String,
    complaints: MutableList<String>,
  ): RuleEffects.Scale? {
    val fact = Fact.of(node.node("fact").string)
    if (fact == null) {
      complaints += "rule $id scales on ${node.node("fact").string}, which is not a fact"
      return null
    }
    val from = node.node("from").getDouble(0.0)
    val to = node.node("to").getDouble(1.0)
    val ranges = linkedMapOf<String, Pair<Double, Double>>()
    node.childrenMap().forEach { (key, child) ->
      val channel = key.toString().lowercase()
      if (channel in MitigationSettings.CHANNELS) {
        val pair = numbers(child)
        if (pair.size != 2) {
          complaints += "rule $id needs two numbers for $channel, one for each end of the scale"
        } else {
          ranges[channel] = clampChannel(channel, pair[0]) to clampChannel(channel, pair[1])
        }
      }
    }
    if (ranges.isEmpty()) {
      complaints += "rule $id scales nothing and was dropped"
      return null
    }
    return RuleEffects.Scale(fact, from, to, ranges)
  }

  private fun clampChannel(channel: String, value: Double): Double =
    if (channel == MitigationSettings.INCOMING) {
      value.coerceIn(1.0, MitigationSettings.MAX_INCOMING)
    } else {
      value.coerceIn(0.0, 1.0)
    }

  private fun readTiming(node: ConfigurationNode): RuleTiming {
    val delay = numbers(node.node("delay-seconds"))
    val low = delay.getOrElse(0) { 0.0 }
    val high = delay.getOrElse(1) { low }
    return RuleTiming(
      delayMinMillis = (low * SECOND).toLong(),
      delayMaxMillis = (high * SECOND).toLong(),
      startsInCombat = node.node("starts-in-combat").getBoolean(false),
      holdMillis = node.node("hold-seconds").getLong(0L) * SECOND,
      releaseJitterMaxMillis = node.node("release-jitter-seconds").getLong(0L) * SECOND,
      maxMillis = node.node("max-seconds").getLong(0L) * SECOND,
      maxAnswers = node.node("max-answers").getLong(0L),
    )
  }

  private fun condition(
    node: ConfigurationNode,
    where: String,
    complaints: MutableList<String>,
  ): RuleCondition? {
    if (node.virtual()) return null
    if (!node.isMap) {
      complaints += "$where has a condition that is not a block"
      return null
    }

    val parts =
      node.childrenMap().mapNotNull { (key, child) ->
        when (val name = key.toString().lowercase()) {
          "all" -> group(child, where, complaints)?.let { RuleCondition.All(it) }
          "any" -> group(child, where, complaints)?.let { RuleCondition.Any(it) }
          "not" -> condition(child, where, complaints)?.let { RuleCondition.Not(it) }
          else -> threshold(name, child, where, complaints)
        }
      }

    return when (parts.size) {
      0 -> null
      1 -> parts.first()
      else -> RuleCondition.All(parts)
    }
  }

  private fun group(
    node: ConfigurationNode,
    where: String,
    complaints: MutableList<String>,
  ): List<RuleCondition>? {
    if (!node.isList) {
      complaints += "$where uses all/any with something that is not a list"
      return null
    }
    val parts = node.childrenList().mapNotNull { condition(it, where, complaints) }
    return parts.ifEmpty { null }
  }

  private fun threshold(
    name: String,
    node: ConfigurationNode,
    where: String,
    complaints: MutableList<String>,
  ): RuleCondition? {
    val fact = Fact.of(name)
    if (fact == null) {
      complaints += "$where asks about $name, which is not something the rules can see"
      return null
    }
    if (!node.isMap) {
      return RuleCondition.Threshold(fact, above = node.double)
    }
    val above = node.node("above").takeUnless { it.virtual() }?.double
    val below = node.node("below").takeUnless { it.virtual() }?.double
    if (above == null && below == null) {
      complaints += "$where asks about $name without an above or a below"
      return null
    }
    val held = node.node("for-seconds").getLong(0L) * SECOND
    if (held > 0L && (fact != Fact.PROBABILITY || above == null)) {
      complaints +=
        "$where sets for-seconds on $name, which only counts alongside probability above"
    }
    return RuleCondition.Threshold(fact = fact, above = above, below = below, heldMillis = held)
  }

  private fun readScore(node: ConfigurationNode, complaints: MutableList<String>): ScoreSettings {
    val fallback = DEFAULT_SCORE
    val clamp = numbers(node.node("clamp"))
    val range = numbers(node.node("range"))
    val score =
      ScoreSettings(
        neutral = node.node("neutral").getDouble(fallback.neutral),
        clampLow = clamp.getOrElse(0) { fallback.clampLow },
        clampHigh = clamp.getOrElse(1) { fallback.clampHigh },
        halfLifeMillis = node.node("half-life-minutes").getLong(20L) * MINUTE,
        floor = range.getOrElse(0) { fallback.floor },
        ceiling = range.getOrElse(1) { fallback.ceiling },
        minAnswers = node.node("min-answers").getLong(fallback.minAnswers),
        forgetRate =
          node.node("forget-rate").getDouble(fallback.forgetRate).coerceIn(0.0, MAX_FORGET_RATE),
        persistEnabled = node.node("persist", "enabled").getBoolean(fallback.persistEnabled),
        persistTtlMillis = node.node("persist", "ttl-hours").getLong(72L) * HOUR,
        capOnRestore = node.node("persist", "cap-on-restore").getDouble(fallback.capOnRestore),
      )
    return sanitise(score, complaints)
  }

  private fun sanitise(score: ScoreSettings, complaints: MutableList<String>): ScoreSettings {
    var result = score
    if (result.neutral !in 0.01..0.99) {
      complaints += "score.neutral must sit between 0.01 and 0.99"
      result = result.copy(neutral = DEFAULT_SCORE.neutral)
    }
    if (result.clampLow >= result.clampHigh || result.clampLow <= 0.0 || result.clampHigh >= 1.0) {
      complaints += "score.clamp must be two rising values inside 0 and 1"
      result = result.copy(clampLow = DEFAULT_SCORE.clampLow, clampHigh = DEFAULT_SCORE.clampHigh)
    }
    if (result.floor >= result.ceiling) {
      complaints += "score.range must rise"
      result = result.copy(floor = DEFAULT_SCORE.floor, ceiling = DEFAULT_SCORE.ceiling)
    }
    if (result.halfLifeMillis <= 0L) {
      complaints += "score.half-life-minutes must be positive"
      result = result.copy(halfLifeMillis = DEFAULT_SCORE.halfLifeMillis)
    }
    return result
  }

  private fun readSkip(node: ConfigurationNode): SkipSettings {
    return SkipSettings(
      bedrock = node.node("bedrock").getBoolean(DEFAULT_SKIP.bedrock),
      followAiRegions = node.node("regions").getString(FOLLOW_AI) == FOLLOW_AI,
    )
  }

  private fun numbers(node: ConfigurationNode): List<Double> =
    when {
      node.isList -> node.childrenList().map { it.getDouble(0.0) }
      node.virtual() || node.isMap -> emptyList()
      else -> listOf(node.getDouble(0.0))
    }
}
