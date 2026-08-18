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
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigManager
import ac.shard.config.LocaleManager
import ac.shard.config.MitigationsFile
import ac.shard.database.DatabaseManager
import ac.shard.database.InMemoryViolationDatabase
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import ac.shard.scheduler.SchedulerService
import ac.shard.utils.MessageUtil
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MitigationRuntimeLogTest {

  private val scoring = MitigationsFile.DEFAULT_SCORE

  @BeforeEach
  fun setUp() {
    val localeManager = mockk<LocaleManager>(relaxed = true)
    every { localeManager.getRawMessage(any()) } returns ""
    MessageUtil.init(
      localeManager,
      mockk<BukkitAudiences>(relaxed = true),
      Logger.getLogger("test"),
    )
  }

  private class Ticker(var now: Long = 1_775_000_000_000L)

  @Suppress("LongParameterList")
  private class Fixture(
    val runtime: MitigationRuntime,
    val logStore: MitigationLogStore,
    val state: MitigationState,
    val player: ShardPlayer,
    val database: InMemoryViolationDatabase,
    val uuid: UUID,
    val ticker: Ticker,
  ) {
    fun climbTo(windows: Int, contribution: Double) {
      repeat(windows) { state.record(contribution, 0.95, ticker.now, scoring) }
    }

    fun drop(contribution: Double) {
      state.record(contribution, 0.02, ticker.now, scoring)
    }

    fun waitSeconds(seconds: Long) {
      ticker.now += seconds * 1_000L
    }

    private val scoring = MitigationsFile.DEFAULT_SCORE
  }

  private fun blatant(jitterMillis: Long = 0L) =
    MitigationRule(
      id = "blatant",
      order = 0,
      level = MitigationTier.HIGH,
      enabled = true,
      entry = RuleCondition.Threshold(Fact.SCORE, above = 30.0),
      until = RuleCondition.Threshold(Fact.SCORE, below = 20.0),
      effects = RuleEffects.Flat(mapOf(MitigationSettings.MELEE to 0.2)),
      timing =
        RuleTiming(
          delayMinMillis = 0L,
          delayMaxMillis = 0L,
          startsInCombat = true,
          holdMillis = 0L,
          releaseJitterMaxMillis = jitterMillis,
          maxMillis = 0L,
          maxAnswers = 0L,
        ),
    )

  private fun fixture(
    jitterMillis: Long = 0L,
    asyncRuns: Boolean = true,
    logEnabled: Boolean = true,
  ): Fixture {
    val ticker = Ticker()
    val clock = { ticker.now }
    val settings =
      MitigationSettings(
        enabled = true,
        logEnabled = logEnabled,
        score = scoring,
        skip = SkipSettings(bedrock = true, followAiRegions = true),
        rules = listOf(blatant(jitterMillis)),
      )

    val uuid = UUID.randomUUID()
    val state = MitigationState()
    val player =
      mockk<ShardPlayer>(relaxed = true) {
        every { mitigation } returns state
        every { this@mockk.uuid } returns uuid
        every { player.name } returns "zamik"
        every { joinTime } returns ticker.now
        every { checkManager.getCheck(AiCheck::class.java) } returns null
      }

    val playerDataManager =
      mockk<PlayerDataManager>(relaxed = true) { every { getPlayers() } returns listOf(player) }

    val scheduler =
      mockk<SchedulerService>(relaxed = true) {
        every { runAsync(any()) } answers
          {
            if (asyncRuns) firstArg<Runnable>().run()
            mockk(relaxed = true)
          }
      }

    val database = InMemoryViolationDatabase(mockk(relaxed = true))
    val databaseManager =
      mockk<DatabaseManager>(relaxed = true) { every { this@mockk.database } returns database }
    val logStore = MitigationLogStore(databaseManager, mockk(relaxed = true), { settings }, clock)

    val runtime =
      MitigationRuntime(
        plugin = mockk<Shard>(relaxed = true),
        playerDataManager = playerDataManager,
        configManager = mockk<ConfigManager>(relaxed = true),
        alertManager = mockk(relaxed = true),
        skip = mockk<MitigationSkip>(relaxed = true) { every { skipReason(any()) } returns null },
        engine = RuleEngine({ settings }, clock, Random(1)),
        damageProcessor = mockk(relaxed = true),
        stamps = HitStamps(),
        debugManager = mockk(relaxed = true),
        scheduler = scheduler,
        logStore = logStore,
        settings = { settings },
        clock = clock,
      )

    return Fixture(runtime, logStore, state, player, database, uuid, ticker)
  }

  @Test
  fun `the log keeps the highest suspicion, not the one left when the rule let go`() {
    val fixture = fixture()

    fixture.climbTo(windows = 6, contribution = 6.0)
    fixture.runtime.tick()
    assertNotNull(fixture.state.applied, "the rule must be applied before it can be logged")
    val peak = fixture.state.score

    fixture.drop(-20.0)
    fixture.runtime.tick()
    assertNull(fixture.state.applied, "the rule releases once the score falls under its until")

    val entries = fixture.database.getMitigationLog(fixture.uuid, limit = 10)
    assertEquals(1, entries.size)
    assertEquals(
      peak,
      entries.single().score,
      "the score at release is the until threshold, not news",
    )
    assertEquals("blatant", entries.single().rule)
    assertEquals("high", entries.single().tier)
    assertEquals("zamik", entries.single().playerName)
  }

  @Test
  fun `a player who quits while mitigated still lands in the log`() {
    val fixture = fixture()

    fixture.climbTo(windows = 6, contribution = 6.0)
    fixture.runtime.tick()
    val peak = fixture.state.score
    fixture.drop(-20.0)

    fixture.logStore.saveOnQuit(fixture.player)

    val entries = fixture.database.getMitigationLog(fixture.uuid, limit = 10)
    assertEquals(1, entries.size)
    assertEquals(peak, entries.single().score, "the quit path must keep the peak too")
    assertEquals("blatant", entries.single().rule)
    assertEquals("high", entries.single().tier)
  }

  @Test
  fun `the log switch stops the writing, and the mitigation still runs`() {
    val fixture = fixture(logEnabled = false)

    fixture.climbTo(windows = 6, contribution = 6.0)
    fixture.runtime.tick()
    assertNotNull(fixture.state.applied, "switching the log off must not disarm the rule")

    fixture.drop(-20.0)
    fixture.runtime.tick()
    fixture.logStore.saveOnQuit(fixture.player)

    assertEquals(emptyList(), fixture.database.getMitigationLog(fixture.uuid, limit = 10))
  }

  @Test
  fun `quitting without a mitigation writes nothing`() {
    val fixture = fixture()

    fixture.climbTo(windows = 6, contribution = 1.0)
    fixture.runtime.tick()
    assertNull(fixture.state.applied, "the score is far below the rule's entry")

    fixture.logStore.saveOnQuit(fixture.player)

    assertEquals(emptyList(), fixture.database.getMitigationLog(fixture.uuid, limit = 10))
  }

  @Test
  fun `the entry spans from the moment the rule applied to the moment it let go`() {
    val fixture = fixture()

    fixture.climbTo(windows = 6, contribution = 6.0)
    fixture.runtime.tick()
    val startedAt = fixture.state.appliedAtMillis

    fixture.waitSeconds(90)
    fixture.drop(-20.0)
    fixture.runtime.tick()

    val entry = fixture.database.getMitigationLog(fixture.uuid, limit = 10).single()
    assertEquals(startedAt, entry.startedAt)
    assertEquals(startedAt + 90_000L, entry.endedAt)
  }

  @Test
  fun `shutting the plugin down writes the open mitigation without the scheduler`() {
    val fixture = fixture(asyncRuns = false)

    fixture.climbTo(windows = 6, contribution = 6.0)
    fixture.runtime.tick()
    assertNotNull(fixture.state.applied)

    fixture.runtime.disable()

    val entries = fixture.database.getMitigationLog(fixture.uuid, limit = 10)
    assertEquals(1, entries.size, "a shutdown write must not go through runAsync")
    assertEquals("blatant", entries.single().rule)
  }

  @Test
  fun `nothing is written while the rule is still holding`() {
    val fixture = fixture()

    fixture.climbTo(windows = 6, contribution = 6.0)
    fixture.runtime.tick()
    fixture.runtime.tick()
    fixture.runtime.tick()

    assertEquals(emptyList(), fixture.database.getMitigationLog(fixture.uuid, limit = 10))
  }
}
