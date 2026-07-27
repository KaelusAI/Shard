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

import ac.shard.api.event.AiPredictionEvent
import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorOutputKind
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MonitorHudSessionTest {
  private val viewerId = UUID.randomUUID()
  private val targetId = UUID.randomUUID()
  private val builder = MonitorFrameBuilder()

  private class RecordingOutput(private val policy: MonitorOutputPolicy) : MonitorOutput {
    val payloads = mutableListOf<MonitorRenderPayload>()

    override val kind = MonitorOutputKind.ACTIONBAR

    override val capabilities =
      MonitorOutputCapabilities(
        maxTargets = 1,
        claimsClientSlot = false,
        eventDriven = false,
        requiresClear = true,
      )

    override fun isAvailable(): Boolean = true

    override fun policy(config: MonitorHudRuntimeConfig): MonitorOutputPolicy = policy

    override fun attach(context: MonitorRenderContext): Boolean = true

    override fun render(context: MonitorRenderContext, payload: MonitorRenderPayload) {
      payloads.add(payload)
    }

    override fun clear(context: MonitorRenderContext) = Unit

    override fun detach(context: MonitorRenderContext) = Unit
  }

  private fun config(yaml: String = "update: 2\n"): MonitorHudRuntimeConfig =
    MonitorHudRuntimeConfig.from(
      ConfigView(
        YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build().load()
      ),
      2,
      Logger.getLogger("hud-session-test"),
    )

  private fun session(
    output: MonitorOutput,
    runtimeConfig: MonitorHudRuntimeConfig = config(),
    target: UUID = targetId,
  ): MonitorHudSession {
    val viewer = mockk<Player>(relaxed = true)
    every { viewer.uniqueId } returns viewerId
    every { viewer.isOnline } returns true
    val session =
      MonitorHudSession(
        MonitorSessionSpec(
          viewer = viewer,
          sessionId = 1L,
          chatStyle = MonitorChatStyle.SUMMARY,
          config = runtimeConfig,
        ),
        listOf(output),
      )
    val targetPlayer = mockk<Player>(relaxed = true)
    every { targetPlayer.uniqueId } returns target
    every { targetPlayer.name } returns "Steve"
    session.trackTarget(targetPlayer, UnavailableTexts(noData = "no data", noAiCheck = "no ai"))
    return session
  }

  private fun settings(theme: MonitorTheme = MonitorTheme.CALM) =
    MonitorSettings(
      mode = MonitorMode.COMPACT,
      theme = theme,
      showPing = true,
      showDmg = true,
      showTrend = true,
      showName = MonitorNameMode.AUTO,
    )

  private fun sample(probability: Double = 0.4, ping: Int = 50) =
    MonitorSample(
      targetId = targetId,
      targetName = "Steve",
      dataPresent = true,
      aiActive = true,
      probability = probability,
      buffer = 1.0,
      rawPing = ping,
      damageMultiplier = 1.0,
      prob90 = 0,
    )

  @Test
  fun `the first cycle always draws`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 10, minIntervalCycles = 0))
    val session = session(output)

    session.render(listOf(sample()), settings(), builder)

    assertEquals(1, output.payloads.size)
  }

  @Test
  fun `an unchanged frame is held until the keepalive elapses`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 3, minIntervalCycles = 0))
    val session = session(output)

    repeat(4) { session.render(listOf(sample()), settings(), builder) }

    assertEquals(2, output.payloads.size)
  }

  @Test
  fun `a keepalive of zero never resends an unchanged frame`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)

    repeat(20) { session.render(listOf(sample()), settings(), builder) }

    assertEquals(1, output.payloads.size)
  }

  @Test
  fun `a changed probability redraws immediately`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)

    session.render(listOf(sample(probability = 0.40)), settings(), builder)
    session.render(listOf(sample(probability = 0.90)), settings(), builder)

    assertEquals(2, output.payloads.size)
  }

  @Test
  fun `changing a setting redraws even when the sample is identical`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)

    session.render(listOf(sample()), settings(MonitorTheme.CALM), builder)
    session.render(listOf(sample()), settings(MonitorTheme.VIVID), builder)

    assertEquals(2, output.payloads.size)
  }

  @Test
  fun `a minimum interval throttles even a changed frame`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 3))
    val session = session(output)

    session.render(listOf(sample(probability = 0.1)), settings(), builder)
    session.render(listOf(sample(probability = 0.2)), settings(), builder)
    session.render(listOf(sample(probability = 0.3)), settings(), builder)

    assertEquals(1, output.payloads.size)
  }

  @Test
  fun `ping jitter inside one bucket does not redraw`() {
    val yaml = "update: 2\nbehavior:\n  ping-refresh-ticks: 200\n  ping-bucket-ms: 50\n"
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output, config(yaml))

    session.render(listOf(sample(ping = 50)), settings(), builder)
    repeat(5) { session.render(listOf(sample(ping = 90)), settings(), builder) }

    assertEquals(1, output.payloads.size)
  }

  @Test
  fun `an unavailable target renders the supplied text`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)

    session.render(
      listOf(sample().copy(dataPresent = false, aiActive = false)),
      settings(),
      builder,
    )

    assertEquals("no data", output.payloads.single().primary.headline)
  }

  @Test
  fun `a target with data but no ai check renders the other text`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)

    session.render(listOf(sample().copy(aiActive = false)), settings(), builder)

    assertEquals("no ai", output.payloads.single().primary.headline)
  }

  @Test
  fun `a live frame reuses the ping the render tick already sampled`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)
    session.render(listOf(sample(ping = 77)), settings(), builder)

    val frame = session.liveFrame(event(), settings(), builder)

    assertEquals("77", frame?.placeholders?.get("ping"))
  }

  @Test
  fun `a live frame carries the detection's own probability`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)
    session.render(listOf(sample(probability = 0.10)), settings(), builder)

    val frame = session.liveFrame(event(probability = 0.93), settings(), builder)

    assertEquals("93", frame?.placeholders?.get("prob"))
  }

  @Test
  fun `a live frame does not disturb the render diff`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)
    session.render(listOf(sample(probability = 0.10)), settings(), builder)

    session.liveFrame(event(probability = 0.93), settings(), builder)
    session.render(listOf(sample(probability = 0.10)), settings(), builder)

    assertEquals(1, output.payloads.size)
  }

  @Test
  fun `watching yourself is reported to the frame builder`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val selfSession = session(output, target = viewerId)

    selfSession.render(listOf(sample().copy(targetId = viewerId)), settings(), builder)

    assertTrue(!output.payloads.single().primary.headline.contains("Steve"))
  }

  @Test
  fun `a detection for a target that is not watched yields nothing`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)

    val frame = session.liveFrame(event().copy(playerId = UUID.randomUUID()), settings(), builder)

    assertNull(frame)
  }

  @Test
  fun `two targets each keep their own trend`() {
    val output = RecordingOutput(MonitorOutputPolicy(keepAliveCycles = 0, minIntervalCycles = 0))
    val session = session(output)
    val second = UUID.randomUUID()
    val secondPlayer = mockk<Player>(relaxed = true)
    every { secondPlayer.uniqueId } returns second
    every { secondPlayer.name } returns "Alex"
    session.trackTarget(secondPlayer, UnavailableTexts("no data", "no ai"))

    session.render(
      listOf(sample(probability = 0.10), sample(probability = 0.10).copy(targetId = second)),
      settings(),
      builder,
    )
    session.render(
      listOf(sample(probability = 0.90), sample(probability = 0.10).copy(targetId = second)),
      settings(),
      builder,
    )

    val frames = output.payloads.last().frames
    assertEquals("+0.80", frames.first { it.targetId == targetId }.placeholders["trend"])
    assertEquals("+0.00", frames.first { it.targetId == second }.placeholders["trend"])
  }

  private fun event(probability: Double = 0.5) =
    AiPredictionEvent(
      playerId = targetId,
      playerName = "Steve",
      checkName = "AI",
      probability = probability,
      bufferBefore = 1.0,
      bufferAfter = 2.0,
      damageMultiplier = 1.0,
      prob90 = 0,
      flagged = false,
    )
}
