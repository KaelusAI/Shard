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
package ac.shard.monitor.hud.output

import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorChatStyle
import ac.shard.monitor.hud.MonitorFrame
import ac.shard.monitor.hud.MonitorHudRuntimeConfig
import ac.shard.monitor.hud.MonitorRenderContext
import ac.shard.monitor.hud.MonitorRenderPayload
import ac.shard.monitor.hud.MonitorSeverity
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bukkit.entity.Player
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ChatOutputTest {
  private val viewerId = UUID.randomUUID()
  private val sent = mutableListOf<String>()
  private val output = ChatOutput { _, raw -> sent.add(raw) }

  private val yaml =
    """
    outputs:
      chat:
        enabled: true
        summary:
          interval-ticks: 20
          template: "{name}:{prob}"
          skip-unchanged: true
        live:
          min-probability: 0.5
          always-show-flagged: true
          cooldown-ticks: 20
          template: "V{prob}"
          flagged-template: "F{prob}"
    """
      .trimIndent()

  private fun runtimeConfig(source: String = yaml): MonitorHudRuntimeConfig {
    val loader = YamlConfigurationLoader.builder().source { source.reader().buffered() }.build()
    return MonitorHudRuntimeConfig.from(
      ConfigView(loader.load()),
      2,
      Logger.getLogger("chat-output-test"),
    )
  }

  private fun context(
    style: MonitorChatStyle,
    config: MonitorHudRuntimeConfig = runtimeConfig(),
  ): MonitorRenderContext {
    val viewer = mockk<Player>(relaxed = true)
    every { viewer.uniqueId } returns viewerId
    every { viewer.isOnline } returns true
    return MonitorRenderContext(viewer, viewerId, 1L, style, config)
  }

  private fun frame(
    targetId: UUID = UUID.randomUUID(),
    name: String = "Steve",
    prob: String = "43",
    dataPresent: Boolean = true,
    aiActive: Boolean = true,
  ) =
    MonitorFrame(
      targetId = targetId,
      targetName = name,
      headline = "$name:$prob",
      placeholders = mapOf("name" to name, "prob" to prob),
      progress = 0.43f,
      severity = MonitorSeverity.CALM,
      dataPresent = dataPresent,
      aiActive = aiActive,
    )

  private fun payload(vararg frames: MonitorFrame) =
    MonitorRenderPayload(frames.toList(), emptyMap())

  @Test
  fun `a summary is printed once and then suppressed while it is unchanged`() {
    val context = context(MonitorChatStyle.SUMMARY)
    output.attach(context)

    output.render(context, payload(frame()))
    output.render(context, payload(frame()))

    assertEquals(listOf("Steve:43"), sent)
  }

  @Test
  fun `a changed summary is printed again`() {
    val context = context(MonitorChatStyle.SUMMARY)
    output.attach(context)

    output.render(context, payload(frame(prob = "43")))
    output.render(context, payload(frame(prob = "88")))

    assertEquals(listOf("Steve:43", "Steve:88"), sent)
  }

  @Test
  fun `skip-unchanged off reprints every cycle`() {
    val config = runtimeConfig(yaml.replace("skip-unchanged: true", "skip-unchanged: false"))
    val context = context(MonitorChatStyle.SUMMARY, config)
    output.attach(context)

    output.render(context, payload(frame()))
    output.render(context, payload(frame()))

    assertEquals(2, sent.size)
  }

  @Test
  fun `a summary skips targets with no data instead of printing a placeholder`() {
    val context = context(MonitorChatStyle.SUMMARY)
    output.attach(context)

    output.render(context, payload(frame(dataPresent = false, aiActive = false)))

    assertTrue(sent.isEmpty())
  }

  @Test
  fun `live style ignores the render tick`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)

    output.render(context, payload(frame()))

    assertTrue(sent.isEmpty())
  }

  @Test
  fun `a line below the probability floor is dropped`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)

    val delivered = output.deliverLive(context, LiveSignal(frame(), flagged = false, 0.2, 1_000L))

    assertFalse(delivered)
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `a flag bypasses the probability floor`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)

    val delivered = output.deliverLive(context, LiveSignal(frame(), flagged = true, 0.2, 1_000L))

    assertTrue(delivered)
    assertEquals(listOf("F43"), sent)
  }

  @Test
  fun `always-show-flagged off keeps the probability floor`() {
    val config =
      runtimeConfig(yaml.replace("always-show-flagged: true", "always-show-flagged: false"))
    val context = context(MonitorChatStyle.LIVE, config)
    output.attach(context)

    val delivered = output.deliverLive(context, LiveSignal(frame(), flagged = true, 0.2, 1_000L))

    assertFalse(delivered)
  }

  @Test
  fun `a second line for the same target inside the cooldown is dropped`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)
    val target = frame()

    output.deliverLive(context, LiveSignal(target, flagged = false, 0.9, 1_000L))
    val second = output.deliverLive(context, LiveSignal(target, flagged = false, 0.9, 1_500L))

    assertFalse(second)
    assertEquals(listOf("V43"), sent)
  }

  @Test
  fun `the cooldown is per target`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)

    output.deliverLive(context, LiveSignal(frame(), flagged = false, 0.9, 1_000L))
    val other = output.deliverLive(context, LiveSignal(frame(name = "Alex"), false, 0.9, 1_500L))

    assertTrue(other)
    assertEquals(2, sent.size)
  }

  @Test
  fun `a line lands again once the cooldown has elapsed`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)
    val target = frame()

    output.deliverLive(context, LiveSignal(target, flagged = false, 0.9, 1_000L))
    val later = output.deliverLive(context, LiveSignal(target, flagged = false, 0.9, 2_000L))

    assertTrue(later)
  }

  @Test
  fun `clearing forgets the cooldown so the next line is immediate`() {
    val context = context(MonitorChatStyle.LIVE)
    output.attach(context)
    val target = frame()

    output.deliverLive(context, LiveSignal(target, flagged = false, 0.9, 1_000L))
    output.clear(context)
    val next = output.deliverLive(context, LiveSignal(target, flagged = false, 0.9, 1_100L))

    assertTrue(next)
  }
}
