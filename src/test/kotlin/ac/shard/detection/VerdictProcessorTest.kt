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
package ac.shard.detection

import ac.shard.Shard
import ac.shard.ai.label.LabelMode
import ac.shard.ai.stream.BufferSpec
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.RoleSpec
import ac.shard.ai.stream.Schedule
import ac.shard.ai.stream.StreamProfile
import ac.shard.ai.stream.roles
import ac.shard.alert.AlertManager
import ac.shard.config.ConfigManager
import ac.shard.config.LocalAiSettings
import ac.shard.config.ModelOverride
import ac.shard.config.ShardSettings
import ac.shard.debug.DebugManager
import ac.shard.mitigation.MitigationScorer
import ac.shard.player.ShardPlayer
import ac.shard.player.state.CombatState
import ac.shard.punishment.PunishmentManager
import ac.shard.region.RegionProvider
import ac.shard.scheduler.SchedulerService
import ac.shard.server.AIServerProvider
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test

class VerdictProcessorTest {

  @Suppress("LongParameterList")
  private fun model(
    id: String,
    labels: List<String> = emptyList(),
    mode: LabelMode? = if (labels.isEmpty()) LabelMode.SINGLE else LabelMode.MULTI_LABEL,
    legit: Set<String> = emptySet(),
    mitigate: Boolean = true,
    punish: Boolean = true,
  ) =
    ModelSpec(
      id,
      id.uppercase(),
      null,
      Schedule.ATTACK,
      10,
      10,
      5,
      0,
      0,
      setOf(0),
      labels,
      mode,
      legit,
      emptyMap(),
      emptyMap(),
      BufferSpec(1_000.0, 0.0, 1.0, 0.25, 32),
      1.0,
      roles(mitigate = mitigate, punish = punish),
    )

  private val a = model("a", listOf("x", "y"))
  private val b = model("b")
  private val d = model("d", listOf("x"), mitigate = false)
  private val softmax = model("s", listOf("clean", "x"), LabelMode.MULTI_CLASS, setOf("clean"))

  private fun profile(primary: ModelSpec, vararg others: ModelSpec) =
    StreamProfile(1, emptyList(), 1, 512, listOf(primary, *others), primary, 0, "")

  private class Fixture(val check: PlayerInference, val scorer: MitigationScorer) {
    val state: DetectionState
      get() = check.state
  }

  private fun fixture(
    profile: StreamProfile,
    overrides: Map<String, ModelOverride> = emptyMap(),
  ): Fixture {
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.logger } returns mockk<Logger>(relaxed = true)
    val configManager = mockk<ConfigManager>(relaxed = true)
    every { configManager.streamProfile } returns profile
    every { configManager.settings } returns
      ShardSettings.DEFAULT.copy(localAi = LocalAiSettings(true, emptyMap(), models = overrides))
    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "TestPlayer"
    every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000002")
    val shardPlayer = mockk<ShardPlayer>(relaxed = true)
    every { shardPlayer.player } returns player
    every { shardPlayer.uuid } returns player.uniqueId
    every { shardPlayer.combat } returns CombatState()
    every { shardPlayer.user } returns
      mockk<User>(relaxed = true).also { every { it.clientVersion } returns ClientVersion.V_1_21_4 }
    val scorer = mockk<MitigationScorer>(relaxed = true)
    val check =
      PlayerInference(
        shardPlayer,
        InferenceServices(
          logger = plugin.logger,
          configManager = configManager,
          regionProvider = mockk<RegionProvider>(relaxed = true),
          alertManager = mockk<AlertManager>(relaxed = true),
          debugManager = DebugManager(plugin.logger, configManager),
          scheduler = mockk<SchedulerService>(relaxed = true),
          mitigationScorer = scorer,
          serverProvider = mockk<AIServerProvider>(relaxed = true),
          punishments = mockk<PunishmentManager>(relaxed = true),
          messages = mockk(relaxed = true),
          events = mockk(relaxed = true),
          predictions = Predictions(),
          mitigationSkip = mockk(relaxed = true),
        ),
      )
    return Fixture(check, scorer)
  }

  @Test
  fun `mitigation follows the strongest cheat label of the model`() {
    val p = profile(a)
    val f = fixture(p)

    f.check.onVerdict(p, a, doubleArrayOf(0.35, 0.96))

    assertEquals(0.96, f.state.lastCheatProbability, 1e-9)
    verify(exactly = 1) {
      f.scorer.record(any(), p, a, 0.96, mapOf("x" to 0.35, "y" to 0.96), any())
    }
  }

  @Test
  fun `a softmax model that decided the player is clean must not feed mitigation`() {
    val p = profile(softmax)
    val f = fixture(p)

    f.check.onVerdict(p, softmax, doubleArrayOf(0.94, 0.06))

    assertTrue(
      f.state.lastCheatProbability < 0.2,
      "0.94 is the confidence that the player is clean, and mitigating on it throttles everyone",
    )
  }

  @Test
  fun `a model without labels mitigates on its single number`() {
    val p = profile(a, b)
    val f = fixture(p)

    f.check.onVerdict(p, b, doubleArrayOf(0.93))

    verify(exactly = 1) { f.scorer.record(any(), p, b, 0.93, any()) }
  }

  @Test
  fun `a model without the mitigate role never reaches mitigation`() {
    val p = profile(a, d)
    val f = fixture(p)

    f.check.onVerdict(p, d, doubleArrayOf(0.99))

    verify(exactly = 0) { f.scorer.record(any(), any(), any(), any(), any(), any()) }
  }

  @Test
  fun `a second model adds its labels without taking over the primary reading`() {
    val c = model("c", listOf("x"))
    val p = profile(a, c)
    val f = fixture(p)

    f.check.onVerdict(p, a, doubleArrayOf(0.40, 0.20))
    f.check.onVerdict(p, c, doubleArrayOf(0.99))

    assertEquals(0.40, f.state.lastCheatProbability, 1e-9, "the primary speaks for the player")
    assertEquals(
      mapOf("x" to 0.40, "y" to 0.20, "c/x" to 0.99),
      f.state.lastLabelProbabilities,
    )

    f.check.onVerdict(p, a, doubleArrayOf(0.10, 0.30))

    assertEquals(
      mapOf("c/x" to 0.99, "x" to 0.10, "y" to 0.30),
      f.state.lastLabelProbabilities,
      "a fresh primary answer replaces only the primary labels",
    )
  }

  @Test
  fun `the player's buffer counts every active model, the primary buffer only the primary`() {
    val e = model("e", listOf("x"), punish = false)
    val p = profile(a, e)
    val f = fixture(p)

    repeat(5) { f.check.onVerdict(p, e, doubleArrayOf(0.99)) }

    val side = f.state.labelBufferSnapshot().getValue("e/x")
    assertTrue(side > 0.0)
    assertEquals(side, f.state.buffer, 1e-9)
    assertEquals(0.0, f.state.primaryBuffer, 1e-9)
  }

  @Test
  fun `a model that shares its buffer feeds the other model with its weight`() {
    val side = b.copy(id = "c", buffer = b.buffer.copy(shared = "a", weight = 0.5))
    val p = profile(a, side)
    val f = fixture(p)

    f.check.onVerdict(p, side, doubleArrayOf(0.99))

    assertEquals(0.09 * 0.5, f.state.primaryBuffer, 1e-9, "half of what the model alone would add")
    assertTrue(f.state.labelBufferSnapshot().keys.none { it.startsWith("c/") })
  }

  @Test
  fun `mitigation reads only the labels its role names and waits for its buffer`() {
    val only = a.copy(role = a.role.copy(mitigate = RoleSpec(labels = setOf("x"))))
    val p = profile(only)
    val f = fixture(p)
    f.check.onVerdict(p, only, doubleArrayOf(0.35, 0.96))
    verify(exactly = 1) { f.scorer.record(any(), p, only, 0.35, mapOf("x" to 0.35), any()) }

    val later = a.copy(role = a.role.copy(mitigate = RoleSpec(buffer = 5.0)))
    val q = profile(later)
    val g = fixture(q)
    g.check.onVerdict(q, later, doubleArrayOf(0.35, 0.96))
    verify(exactly = 0) { g.scorer.record(any(), any(), any(), any(), any(), any()) }
  }
}
