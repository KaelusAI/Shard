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
package ac.shard.mitigation

import ac.shard.Shard
import ac.shard.api.event.ShardEvent
import ac.shard.api.event.mitigation.MitigationChangeEvent
import ac.shard.api.impl.event.ShardEvents
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.config.MitigationsFile
import ac.shard.database.DatabaseManager
import ac.shard.database.InMemoryViolationDatabase
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.Messages
import ac.shard.utils.WallClock
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import org.junit.jupiter.api.Test

class MitigationRuntimeReloadTest {
  private val scoring = MitigationsFile.DEFAULT_SCORE
  private val now = 1_775_000_000_000L
  private val published = mutableListOf<ShardEvent>()
  private val state = MitigationState()
  private var settings = settingsWith(rule(MitigationTier.HIGH))

  private val runtime = run {
    val clock = WallClock { now }
    val player =
      mockk<ShardPlayer>(relaxed = true) {
        every { mitigation } returns state
        every { uuid } returns UUID.randomUUID()
        every { joinTime } returns now
      }
    val databaseManager =
      mockk<DatabaseManager>(relaxed = true) {
        every { database } returns InMemoryViolationDatabase()
      }
    MitigationRuntime(
      messages =
        Messages(
          mockk<LocaleManager>(relaxed = true) { every { getRawMessage(any()) } returns "" },
          mockk<BukkitAudiences>(relaxed = true),
          Logger.getLogger("test"),
        ),
      plugin = mockk<Shard>(relaxed = true),
      playerDataManager =
        mockk<PlayerDataManager>(relaxed = true) { every { getPlayers() } returns listOf(player) },
      configManager = mockk<ConfigManager>(relaxed = true),
      alertManager = mockk(relaxed = true),
      skip = mockk<MitigationSkip>(relaxed = true) { every { skipReason(any()) } returns null },
      engine = RuleEngine({ settings }, clock, Random(1)),
      stamps = HitStamps(clock),
      debugManager = mockk(relaxed = true),
      scheduler = mockk<SchedulerService>(relaxed = true),
      logStore = MitigationLogStore(databaseManager, mockk(relaxed = true), { settings }, clock),
      settings = { settings },
      clock = clock,
      events =
        mockk<ShardEvents>(relaxed = true) {
          every { publish(any()) } answers { published += firstArg<ShardEvent>() }
        },
      sessions = mockk(relaxed = true),
    )
  }

  private fun rule(level: MitigationTier) =
    MitigationRule(
      id = "a",
      order = 0,
      level = level,
      enabled = true,
      entry = RuleCondition.Threshold(Fact.SCORE, above = 30.0),
      until = RuleCondition.Threshold(Fact.SCORE, below = 20.0),
      effects = RuleEffects.Flat(mapOf(EffectChannel.MELEE to 0.2)),
      timing =
        RuleTiming(
          delayMinMillis = 0L,
          delayMaxMillis = 0L,
          startsInCombat = true,
          holdMillis = 0L,
          releaseJitterMaxMillis = 0L,
          maxMillis = 0L,
          maxAnswers = 0L,
        ),
    )

  private fun settingsWith(vararg rules: MitigationRule) =
    MitigationSettings(
      enabled = true,
      logEnabled = false,
      score = scoring,
      skip = SkipSettings(bedrock = true, followAiRegions = true),
      rules = rules.toList(),
    )

  private fun applyRule() {
    repeat(6) { state.record(6.0, 0.95, now, scoring) }
    runtime.tick()
    assertNotNull(state.applied)
    published.clear()
  }

  private fun changes() = published.filterIsInstance<MitigationChangeEvent>()

  @Test
  fun `a rule removed by a reload announces that the player is no longer mitigated`() {
    applyRule()
    settings = settingsWith()

    runtime.reload()

    assertNull(state.applied)
    val change = changes().single()
    assertEquals("a", change.previousRuleId())
    assertNull(change.ruleId())
    assertEquals(ac.shard.api.mitigation.MitigationTier.NONE, change.tier())
  }

  @Test
  fun `a reload that changes the tier of the running rule announces the new tier`() {
    applyRule()
    settings = settingsWith(rule(MitigationTier.LOW))

    runtime.reload()

    val change = changes().single()
    assertEquals("a", change.ruleId())
    assertEquals(ac.shard.api.mitigation.MitigationTier.HIGH, change.previousTier())
    assertEquals(ac.shard.api.mitigation.MitigationTier.LOW, change.tier())
  }

  @Test
  fun `a reload that removes the applied rule while another one waits releases the player`() {
    applyRule()
    val waiting = rule(MitigationTier.LOW).copy(id = "b")
    state.updatePhase { it.copy(matched = waiting) }
    settings = settingsWith(waiting)

    runtime.reload()

    assertNull(state.applied)
    assertEquals("a", changes().single().previousRuleId())
  }

  @Test
  fun `a reload that removes only the waiting rule keeps the applied one`() {
    applyRule()
    state.updatePhase { it.copy(matched = rule(MitigationTier.LOW).copy(id = "b")) }

    runtime.reload()

    assertEquals("a", state.applied?.id)
    assertNull(state.matched)
    assertEquals(emptyList(), changes())
  }

  @Test
  fun `a reload that leaves the running rule as it was announces nothing`() {
    applyRule()

    runtime.reload()

    assertEquals(emptyList(), changes())
  }
}
