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
import ac.shard.config.ConfigView
import ac.shard.monitor.core.LabelFocus
import ac.shard.monitor.core.MonitorLabelInfo
import ac.shard.monitor.core.MonitorMode
import ac.shard.monitor.core.MonitorNameMode
import ac.shard.monitor.core.MonitorSample
import ac.shard.monitor.core.MonitorSettings
import ac.shard.monitor.core.MonitorTheme
import ac.shard.monitor.hud.output.buildSidebarLines
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class MonitorRenderSamplesTest {
  private val shipped: MonitorHudRuntimeConfig =
    MonitorHudRuntimeConfig.from(ConfigView(loadShipped()), 2, Logger.getLogger("render-samples"))

  private val titles = mapOf("aim" to "Aim Assist", "trigger" to "Auto Clicker", "reach" to "Reach")

  @Test
  fun `a model with nothing to attribute leaves no empty tag anywhere`() {
    val frame = frame(mapOf("_unattributed" to 41.2))

    for (rendered in listOf(actionBar(frame), tabList(frame), live(frame))) {
      assertFalse(
        EMPTY_TAG.containsMatchIn(rendered),
        "an empty theme wrapper means an operator sees a dangling separator: $rendered",
      )
      assertFalse("hover" in rendered, "a hover with nothing behind it is noise: $rendered")
    }
    assertEquals(1, tabList(frame).split("<newline>").size, "no blank footer row")
    assertEquals(7, buildSidebarLines(payload(frame), shipped.sidebar).size)
  }

  @Test
  fun `the leading label rides next to the buffer on every one-line output`() {
    val frame = frame(mapOf("trigger" to 63.4, "aim" to 51.2))

    for (rendered in listOf(actionBar(frame), bossBar(frame))) {
      assertTrue(
        "trigger" in rendered,
        "the number needs to say what it belongs to: $rendered",
      )
      assertFalse("aim " in rendered, "one line names one detection, not the whole list")
    }
    assertTrue("trigger" in live(frame), "chat names the leader in the line itself")
  }

  @Test
  fun `the outputs with the space for it carry the whole breakdown`() {
    val frame = frame(mapOf("trigger" to 63.4, "aim" to 51.2))

    val breakdown = "Auto Clicker 96% ◆63.40 · Aim Assist 35% ◆51.20"
    assertEquals("<gray>$breakdown</gray>", tabList(frame).split("<newline>")[1])
    val hovered = MonitorHudRuntimeConfig.from(withLabelHover(), 2, Logger.getLogger("hovered"))
    assertTrue(
      breakdown in live(frame, hovered),
      "the chat line can carry it under the cursor, though the shipped template leaves it off " +
        "because the line already names the detection beside its own number",
    )

    val sidebar = buildSidebarLines(payload(frame), shipped.sidebar)
    assertEquals("<gray>Buffer</gray>", sidebar[4], "the rows below it carry their own numbers")
    assertEquals(
      "<color:#C4B5FD>Auto Clicker</color>  <white>96%</white>  <yellow>◆ 63.40</yellow>",
      sidebar[5],
    )
    assertEquals(
      "<color:#C4B5FD>Aim Assist</color>  <white>35%</white>  <yellow>◆ 51.20</yellow>",
      sidebar[6],
    )
  }

  @Test
  fun `one line describes one detection, and never lends it another's number`() {
    val leaderByBuffer = mapOf("aim" to 70.0, "trigger" to 5.0)
    val frame = frame(leaderByBuffer, probabilities = mapOf("aim" to 0.30, "trigger" to 0.99))

    val rendered = actionBar(frame)
    assertTrue("30%" in rendered, "the number shown belongs to the label named beside it")
    assertFalse(
      "99%" in rendered,
      "99% is the other detection's, and putting it next to Aim Assist would read as a lie",
    )
    assertTrue(
      "30%</white></bold><color:#C4B5FD> aim</color>" in rendered,
      "the name hangs off its own number, not off the player, and carries no padding",
    )
  }

  @Test
  fun `a lone detection is not decorated with a count of nothing`() {
    val rendered = actionBar(frame(mapOf("aim" to 41.2)))

    assertTrue("aim" in rendered, "the percentage says what it is a percentage of")
    assertFalse("1/1" in rendered, "there is nothing to count")
  }

  @Test
  fun `the one-line outputs take turns, so a second detection is never invisible`() {
    val buffers = mapOf("aim" to 70.0, "trigger" to 5.0)
    val probabilities = mapOf("aim" to 0.30, "trigger" to 0.99)

    val first = actionBar(frame(buffers, probabilities, atMillis = 0L))
    val second = actionBar(frame(buffers, probabilities, atMillis = 2_000L))

    assertTrue("aim" in first && "30%" in first && "70.00" in first)
    assertTrue("trigger" in second && "99%" in second && "5.00" in second)
    assertTrue(
      "trigger" !in first && "aim" !in second,
      "the turn is what tells the operator a second detection is live, so each turn shows one",
    )
  }

  @Test
  fun `every turn writes the same width, and a long name cannot open a gap`() {
    val catalog = mapOf("aim" to "Aim", "trigger" to "Killaura Autoclicker Reach Detection")
    val buffers = mapOf("aim" to 70.0, "trigger" to 5.0)

    val titled = MonitorHudRuntimeConfig.from(paddedStyle(), 2, Logger.getLogger("padded"))
    val rendered =
      (0..1).map {
        fillFrameTemplate(
          titled.actionBar.template,
          frame(buffers, atMillis = it * 2_000L, config = titled, names = catalog),
        )
      }

    assertEquals(
      rendered[0].length,
      rendered[1].length,
      "a centred line that changes width jumps on every turn",
    )
    assertTrue("Killaura Au…" in rendered[1], "the long name is cut, not left to stretch it")
  }

  @Test
  fun `a model with more labels than the sidebar can hold says so instead of losing rows`() {
    val many = (1..9).associate { "label$it" to it.toDouble() }
    val lines = buildSidebarLines(payload(frame(many, names = emptyMap())), shipped.sidebar)

    assertTrue(lines.size <= 15, "the client only draws 15 rows")
    assertTrue(
      lines.any { "more" in it },
      "silently dropping the tail takes Ping and Dmg off the screen with no trace: $lines",
    )
    assertTrue(lines.any { "Ping" in it } && lines.any { "Dmg" in it })
  }

  @Test
  fun `a viewer who pinned a label sees it whatever the clock says`() {
    val buffers = mapOf("aim" to 70.0, "trigger" to 5.0)
    val probabilities = mapOf("aim" to 0.30, "trigger" to 0.99)

    val rendered =
      (0..3).map {
        actionBar(frame(buffers, probabilities, atMillis = it * 2_000L, focus = "trigger"))
      }

    assertEquals(1, rendered.distinct().size, "a pinned label does not take turns")
    assertTrue("trigger" in rendered[0] && "99%" in rendered[0] && "5.00" in rendered[0])
  }

  @Test
  fun `a pin on a label the model dropped falls back instead of showing nothing`() {
    val rendered = actionBar(frame(mapOf("aim" to 70.0), focus = "trigger"))

    assertTrue("aim" in rendered, "the pinned label is gone, so show what is left")
  }

  @Test
  fun `off pins the viewer to the strongest without touching the server setting`() {
    val buffers = mapOf("aim" to 70.0, "trigger" to 5.0)

    val rendered =
      (0..3).map {
        actionBar(frame(buffers, atMillis = it * 2_000L, focus = LabelFocus.STRONGEST))
      }

    assertEquals(1, rendered.distinct().size)
    assertTrue("aim" in rendered[0])
  }

  @Test
  fun `pinning the rotation keeps the strongest detection on screen`() {
    val buffers = mapOf("aim" to 70.0, "trigger" to 5.0)
    val pinned = MonitorHudRuntimeConfig.from(pinnedRotation(), 2, Logger.getLogger("pinned"))

    val rendered =
      (0..3).map {
        fillFrameTemplate(
          pinned.actionBar.template,
          frame(buffers, atMillis = it * 2_000L, config = pinned),
        )
      }

    assertEquals(1, rendered.distinct().size, "label-rotate-ticks 0 means it never moves")
    assertTrue("aim" in rendered[0])
  }

  @Test
  fun `a third label adds a sidebar row and never widens the action bar`() {
    val frame = frame(mapOf("trigger" to 63.4, "aim" to 51.2, "reach" to 12.0))

    assertEquals(10, buildSidebarLines(payload(frame), shipped.sidebar).size)

    val rendered = actionBar(frame)
    assertTrue("trigger" in rendered)
    assertFalse(
      "reach" in rendered,
      "a third detection must not widen the narrowest line: $rendered",
    )
  }

  @Test
  fun `a head the model declared is named even while it is quiet`() {
    val frame =
      frame(
        buffers = emptyMap(),
        probabilities = mapOf("aim" to 0.0, "trigger" to 0.004),
        declared = listOf("aim", "trigger"),
      )

    val rendered = actionBar(frame)
    assertTrue("aim" in rendered, "a percentage with no name reads as a verdict on the player")
    assertEquals(
      listOf("Aim Assist", "Auto Clicker"),
      frame.labels.map { it.name },
      "declared order holds while every buffer is level, so the rows do not swap on their own",
    )
    assertEquals(9, buildSidebarLines(payload(frame), shipped.sidebar).size)
  }

  @Test
  fun `a model with no heads at all still leaves the label off`() {
    val frame = frame(buffers = emptyMap(), probabilities = emptyMap())

    assertTrue(frame.labels.isEmpty(), "there is no head to name")
    assertFalse("aim" in actionBar(frame), "a name invented here would belong to no head")
  }

  @Test
  fun `the buffer decides the order, not the loudest window`() {
    val frame =
      frame(
        buffers = mapOf("trigger" to 24.0),
        probabilities = mapOf("aim" to 0.99, "trigger" to 0.10),
        declared = listOf("aim", "trigger"),
      )

    assertEquals(
      listOf("Auto Clicker", "Aim Assist"),
      frame.labels.map { it.name },
      "the accumulated buffer leads, so a single loud window cannot reshuffle the rows",
    )
  }

  private fun actionBar(frame: MonitorFrame) = fillFrameTemplate(shipped.actionBar.template, frame)

  private fun bossBar(frame: MonitorFrame) = fillFrameTemplate(shipped.bossBar.title, frame)

  private fun tabList(frame: MonitorFrame) =
    shipped.tabList.footerLines
      .map { fillFrameTemplate(it, frame) }
      .filter { it.isNotBlank() }
      .joinToString("<newline>")

  private fun live(frame: MonitorFrame, config: MonitorHudRuntimeConfig = shipped): String {
    val hover =
      if (frame.labels.isEmpty()) "" else " " + fillFrameTemplate(config.chat.labelHover, frame)
    return fillFrameTemplate(config.chat.liveTemplate.replace("{label_hover}", hover), frame)
  }

  private fun payload(frame: MonitorFrame) = MonitorRenderPayload(listOf(frame), emptyMap())

  private fun loadShipped(): CommentedConfigurationNode =
    YamlConfigurationLoader.builder()
      .file(
        File(
          this::class.java.classLoader.getResource("monitor.yml")?.toURI()
            ?: error("bundled monitor.yml is missing from the test classpath")
        )
      )
      .build()
      .load()

  private fun paddedStyle(): ConfigView {
    val node = loadShipped()
    node.node("behavior", "label", "style").set("title")
    node.node("behavior", "label", "keep-width").set(true)
    return ConfigView(node)
  }

  private fun withLabelHover(): ConfigView {
    val node = loadShipped()
    val chat = node.node("outputs", "chat", "live")
    chat.node("template").set(chat.node("template").getString("") + "{label_hover}")
    return ConfigView(node)
  }

  private fun pinnedRotation(): ConfigView {
    val node = loadShipped()
    node.node("behavior", "label-rotate-ticks").set(0L)
    return ConfigView(node)
  }

  @Suppress("LongParameterList")
  private fun frame(
    buffers: Map<String, Double>,
    probabilities: Map<String, Double> = PROBABILITIES.filterKeys { it in buffers },
    atMillis: Long = 0L,
    config: MonitorHudRuntimeConfig = shipped,
    focus: String = LabelFocus.AUTO,
    names: Map<String, String> = titles,
    declared: List<String> = emptyList(),
  ): MonitorFrame =
    MonitorFrameBuilder(LabelCatalog(local = { emptyMap() }, fromServer = { names })) { atMillis }
      .build(
        MonitorFrameRequest(
          sample =
            MonitorSample(
              targetId = UUID.randomUUID(),
              targetName = "Steve",
              dataPresent = true,
              aiActive = true,
              probability = MonitorLabelInfo.probabilityOfLeader(buffers, probabilities, 0.97),
              buffer = buffers.values.maxOrNull() ?: 0.0,
              rawPing = 57,
              damageMultiplier = 1.0,
              prob90 = 4,
              leadingLabel = MonitorLabelInfo.leading(buffers),
              labelBuffers = buffers,
              labelProbabilities = probabilities,
              declaredLabels = declared,
            ),
          settings =
            MonitorSettings(
              mode = MonitorMode.COMPACT,
              theme = MonitorTheme.CALM,
              showPing = true,
              showDmg = true,
              showTrend = true,
              showName = MonitorNameMode.ALWAYS,
              labelFocus = focus,
            ),
          pingValue = 57,
          trend = 0.12,
          selfView = false,
          unavailableHeadline = "no data",
        ),
        config,
      )

  private companion object {
    val EMPTY_TAG = Regex("<([a-z_:#0-9]+)[^>]*></\\1>")
    val PROBABILITIES = mapOf("trigger" to 0.96, "aim" to 0.35, "reach" to 0.12)
  }
}
