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

import ac.shard.config.ConfigView
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MonitorFrameBuilderTest {
  private val builder = MonitorFrameBuilder()
  private val targetId = UUID.randomUUID()

  private val baseYaml =
    """
    update: 2
    modes:
      compact: [name, prob, trend, buffer, ping, dmg]
    theme:
      calm:
        name: "N{name}"
        prob: "P{prob}"
        trend: "T{trend}"
        buffer: "B{buffer}"
        ping: "G{ping}"
        dmg: "D{dmg}"
        sep: "|"
    behavior:
      neutral:
        ping: "<ping-off>"
        dmg: "<dmg-off>"
        trend: "<trend-off>"
    """
      .trimIndent()

  private fun config(yaml: String = baseYaml, viewSlot: Int = 2): MonitorHudRuntimeConfig {
    val loader = YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build()
    return MonitorHudRuntimeConfig.from(
      ConfigView(loader.load()),
      viewSlot,
      Logger.getLogger("frame-builder-test"),
    )
  }

  private fun settings(
    showPing: Boolean = true,
    showDmg: Boolean = true,
    showTrend: Boolean = true,
    showName: MonitorNameMode = MonitorNameMode.ALWAYS,
  ) =
    MonitorSettings(
      mode = MonitorMode.COMPACT,
      theme = MonitorTheme.CALM,
      showPing = showPing,
      showDmg = showDmg,
      showTrend = showTrend,
      showName = showName,
    )

  private fun sample(
    name: String = "Steve",
    probability: Double = 0.43,
    buffer: Double = 2.5,
    damageMultiplier: Double = 1.0,
    available: Boolean = true,
  ) =
    MonitorSample(
      targetId = targetId,
      targetName = name,
      dataPresent = available,
      aiActive = available,
      probability = probability,
      buffer = buffer,
      rawPing = 57,
      damageMultiplier = damageMultiplier,
      prob90 = 3,
    )

  private fun request(
    sample: MonitorSample = sample(),
    settings: MonitorSettings = settings(),
    pingValue: Int = 57,
    trend: Double = 0.0,
    selfView: Boolean = false,
  ) =
    MonitorFrameRequest(
      sample = sample,
      settings = settings,
      pingValue = pingValue,
      trend = trend,
      selfView = selfView,
      unavailableHeadline = "no data",
    )

  @Test
  fun `the headline joins themed tokens with the theme separator`() {
    val frame = builder.build(request(), config())

    assertEquals("NSteve|P43|T+0.00|B2.50|G57|D1.00", frame.headline)
  }

  @Test
  fun `auto name mode drops the name when watching yourself`() {
    val frame =
      builder.build(
        request(settings = settings(showName = MonitorNameMode.AUTO), selfView = true),
        config(),
      )

    assertEquals("P43|T+0.00|B2.50|G57|D1.00", frame.headline)
  }

  @Test
  fun `auto name mode keeps the name when watching someone else`() {
    val frame =
      builder.build(
        request(settings = settings(showName = MonitorNameMode.AUTO), selfView = false),
        config(),
      )

    assertTrue(frame.headline.startsWith("NSteve|"))
  }

  @Test
  fun `never name mode always drops the name`() {
    val frame =
      builder.build(request(settings = settings(showName = MonitorNameMode.NEVER)), config())

    assertEquals("P43|T+0.00|B2.50|G57|D1.00", frame.headline)
  }

  @Test
  fun `a long name is truncated with the configured suffix`() {
    val yaml = baseYaml + "\n  name:\n    max-length: 6\n    truncate-suffix: \"..\"\n"
    val frame = builder.build(request(sample = sample(name = "Bartholomew")), config(yaml))

    assertEquals("Bart..", frame.placeholders["name"])
  }

  @Test
  fun `a hidden ping renders the neutral placeholder`() {
    val frame = builder.build(request(settings = settings(showPing = false)), config())

    assertEquals("NSteve|P43|T+0.00|B2.50|<ping-off>|D1.00", frame.headline)
  }

  @Test
  fun `a hidden trend renders the neutral placeholder`() {
    val frame = builder.build(request(settings = settings(showTrend = false)), config())

    assertEquals("NSteve|P43|<trend-off>|B2.50|G57|D1.00", frame.headline)
  }

  @Test
  fun `keep-length off drops hidden tokens entirely`() {
    val yaml = baseYaml + "\n  keep-length: false\n"
    val frame = builder.build(request(settings = settings(showPing = false)), config(yaml))

    assertEquals("NSteve|P43|T+0.00|B2.50|D1.00", frame.headline)
  }

  @Test
  fun `a default damage multiplier is hidden when the operator asks for it`() {
    val yaml = baseYaml + "\nformat:\n  dmg:\n    hide-when-default: true\n"
    val frame = builder.build(request(sample = sample(damageMultiplier = 1.0)), config(yaml))

    assertEquals("NSteve|P43|T+0.00|B2.50|G57|<dmg-off>", frame.headline)
  }

  @Test
  fun `placeholders carry the raw value, the themed value and the headline`() {
    val frame = builder.build(request(), config())

    assertEquals("43", frame.placeholders["prob"])
    assertEquals("P43", frame.placeholders["prob!"])
    assertEquals("3", frame.placeholders["prob90"])
    assertEquals(frame.headline, frame.placeholders["headline"])
  }

  @Test
  fun `an unavailable target uses the supplied headline and zeroes the bar`() {
    val frame = builder.build(request(sample = sample(available = false)), config())

    assertEquals("no data", frame.headline)
    assertEquals(0f, frame.progress)
    assertEquals(MonitorSeverity.CALM, frame.severity)
  }

  @Test
  fun `ping jitter inside one sampled value leaves the frame identical`() {
    val first = builder.build(request(sample = sample().copy(rawPing = 50)), config())
    val second = builder.build(request(sample = sample().copy(rawPing = 57)), config())

    assertEquals(first, second)
  }

  @Test
  fun `a changed sampled ping does change the frame`() {
    val first = builder.build(request(pingValue = 50), config())
    val second = builder.build(request(pingValue = 90), config())

    assertNotEquals(first, second)
  }

  @Test
  fun `progress clamps out-of-range and non-finite probabilities`() {
    val config = config()

    assertEquals(1f, builder.build(request(sample = sample(probability = 4.0)), config).progress)
    assertEquals(0f, builder.build(request(sample = sample(probability = -2.0)), config).progress)
    assertEquals(
      0f,
      builder.build(request(sample = sample(probability = Double.NaN)), config).progress,
    )
  }

  @Test
  fun `severity follows the configured thresholds`() {
    val config = config()

    assertEquals(
      MonitorSeverity.CALM,
      builder.build(request(sample = sample(probability = 0.10)), config).severity,
    )
    assertEquals(
      MonitorSeverity.WATCH,
      builder.build(request(sample = sample(probability = 0.50)), config).severity,
    )
    assertEquals(
      MonitorSeverity.ALERT,
      builder.build(request(sample = sample(probability = 0.90)), config).severity,
    )
  }

  @Test
  fun `the buffer progress source is scaled by the configured maximum`() {
    val yaml =
      baseYaml + "\noutputs:\n  bossbar:\n    progress: buffer\n    progress-buffer-max: 10.0\n"
    val frame = builder.build(request(sample = sample(buffer = 5.0)), config(yaml))

    assertEquals(0.5f, frame.progress)
  }
}
