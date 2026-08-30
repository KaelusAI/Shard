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
package ac.shard.monitor.view

import ac.shard.ai.label.LabelCatalog
import ac.shard.checks.CheckManager
import ac.shard.checks.impl.ai.AiCheck
import ac.shard.config.ConfigView
import ac.shard.data.CollectManager
import ac.shard.mitigation.MitigationState
import ac.shard.monitor.core.MonitorSampler
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ViewTagRendererFallbackTest {
  private val config =
    ViewRuntimeConfig(
      updateTicks = 2,
      rebindCycles = 10,
      resyncCycles = 50,
      pingRefreshCycles = 20,
      pingBucketMs = 10,
      placement = ViewPlacement.BELOW_NAME,
      belowTitle = "",
      fallbackProb = "--",
      fallbackBuffer = "??",
      probDecimals = 0,
      bufferDecimals = 2,
      prefixTemplate = "{prob}|{buffer}",
      suffixTemplate = "",
      belowTemplate = "{prob}",
      defaultBelowText = "--",
      usesPing = false,
    )

  @Test
  fun `unknown player falls back on both values and scores zero`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    every { playerDataManager.getPlayer(target) } returns null

    val rendered =
      ViewTagRenderer(MonitorSampler(playerDataManager, noCollector()), emptyCatalog())
        .render(target, target, "", config)

    assertEquals("--|??", rendered.prefix)
    assertEquals("--", rendered.below)
    assertEquals(0, rendered.belowScore)
  }

  @Test
  fun `known player without ai check falls back on both values and scores zero`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns mockk(relaxed = true)
    every { shardPlayer.mitigation } returns MitigationState()
    every { checkManager.getCheck(AiCheck::class.java) } returns null

    val rendered =
      ViewTagRenderer(MonitorSampler(playerDataManager, noCollector()), emptyCatalog())
        .render(target, target, "", config)

    assertEquals("--|??", rendered.prefix)
    assertEquals("--", rendered.below)
    assertEquals(0, rendered.belowScore)
  }

  @Test
  fun `present ai check renders formatted values and a rounded score`() {
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    val aiCheck = mockk<AiCheck>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns mockk(relaxed = true)
    every { shardPlayer.mitigation } returns MitigationState()
    every { checkManager.getCheck(AiCheck::class.java) } returns aiCheck
    every { aiCheck.lastProbability } returns 0.954
    every { aiCheck.buffer } returns 12.5
    every { aiCheck.prob90 } returns 0
    every { aiCheck.inferenceProgress } returns null
    every { aiCheck.labelBufferSnapshot() } returns emptyMap()
    every { aiCheck.lastCheatProbability } returns 0.954
    every { aiCheck.lastLabelProbabilities } returns emptyMap()
    every { aiCheck.declaredLabels } returns emptyList()

    val rendered =
      ViewTagRenderer(MonitorSampler(playerDataManager, noCollector()), emptyCatalog())
        .render(target, target, "", config)

    assertEquals("95|12.50", rendered.prefix)
    assertEquals("95", rendered.below)
    assertEquals(95, rendered.belowScore)
  }

  @Test
  fun `the nameplate names the detection its numbers belong to, and takes turns`() {
    val labelled =
      config.copy(
        prefixTemplate = "{prob}|{buffer}",
        suffixTemplate = "{label_tag}",
        labelTemplate = " [{label}]",
        labelRotateMillis = 2_000L,
      )
    val playerDataManager = labelledPlayer()
    val target = target(playerDataManager)

    val first = renderAt(playerDataManager, target, labelled, 0L)
    val second = renderAt(playerDataManager, target, labelled, 2_000L)

    assertEquals("30|70.00", first.prefix)
    assertEquals(" [aim    ]", first.suffix, "padded so a centred nameplate does not jump")
    assertEquals("99|5.00", second.prefix)
    assertEquals(" [trigger]", second.suffix)
  }

  @Test
  fun `a viewer pinned to one detection keeps it on the nameplate`() {
    val labelled =
      config.copy(
        prefixTemplate = "{prob}|{buffer}",
        suffixTemplate = "{label_tag}",
        labelTemplate = " [{label}]",
        labelRotateMillis = 2_000L,
      )
    val playerDataManager = labelledPlayer()
    val target = target(playerDataManager)

    val rendered =
      (0..3).map { renderAt(playerDataManager, target, labelled, it * 2_000L, "trigger") }

    assertEquals(1, rendered.map(RenderedTag::suffix).distinct().size)
    assertEquals(" [trigger]", rendered[0].suffix)
  }

  @Test
  fun `the shipped below-name placement carries the label, because it draws nothing else`() {
    val shipped = ViewRuntimeConfig.from(ConfigView(loadShippedMonitor()))
    val playerDataManager = labelledPlayer()
    val target = target(playerDataManager)

    val rendered = renderAt(playerDataManager, target, shipped, 0L)

    assertTrue(
      "aim" in rendered.below,
      "position is BELOW_NAME by default, so a label only in the suffix would never be seen",
    )
  }

  @Test
  fun `a model without labels leaves the nameplate exactly as it was`() {
    val labelled = config.copy(suffixTemplate = "{label_tag}", labelTemplate = " [{label}]")
    val playerDataManager = mockk<PlayerDataManager>()
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    every { playerDataManager.getPlayer(target) } returns null

    val rendered =
      ViewTagRenderer(MonitorSampler(playerDataManager, noCollector()), emptyCatalog())
        .render(target, target, "", labelled)

    assertEquals("", rendered.suffix, "no empty brackets over an unlabelled player's head")
  }

  private fun renderAt(
    playerDataManager: PlayerDataManager,
    target: org.bukkit.entity.Player,
    config: ViewRuntimeConfig,
    millis: Long,
    pinned: String = "",
  ): RenderedTag =
    ViewTagRenderer(
        MonitorSampler(playerDataManager, noCollector()),
        emptyCatalog(),
        { pinned },
        { millis },
      )
      .render(target, target, "", config)

  private fun loadShippedMonitor() =
    YamlConfigurationLoader.builder()
      .file(
        java.io.File(
          this::class.java.classLoader.getResource("monitor.yml")?.toURI()
            ?: error("bundled monitor.yml is missing from the test classpath")
        )
      )
      .build()
      .load()

  private fun labelledPlayer(): PlayerDataManager = mockk<PlayerDataManager>()

  private fun target(playerDataManager: PlayerDataManager): org.bukkit.entity.Player {
    val target = mockk<org.bukkit.entity.Player>(relaxed = true)
    val shardPlayer = mockk<ShardPlayer>()
    val checkManager = mockk<CheckManager>()
    val aiCheck = mockk<AiCheck>()
    every { playerDataManager.getPlayer(target) } returns shardPlayer
    every { shardPlayer.checkManager } returns checkManager
    every { shardPlayer.combat } returns mockk(relaxed = true)
    every { shardPlayer.mitigation } returns MitigationState()
    every { checkManager.getCheck(AiCheck::class.java) } returns aiCheck
    every { aiCheck.lastProbability } returns 0.99
    every { aiCheck.buffer } returns 70.0
    every { aiCheck.prob90 } returns 0
    every { aiCheck.inferenceProgress } returns null
    every { aiCheck.labelBufferSnapshot() } returns mapOf("aim" to 70.0, "trigger" to 5.0)
    every { aiCheck.lastCheatProbability } returns 0.99
    every { aiCheck.lastLabelProbabilities } returns mapOf("aim" to 0.30, "trigger" to 0.99)
    every { aiCheck.declaredLabels } returns listOf("aim", "trigger")
    return target
  }
}

private fun noCollector(): CollectManager =
  mockk<CollectManager>(relaxed = true).also { every { it.getSession(any()) } returns null }

private fun emptyCatalog(): LabelCatalog = LabelCatalog({ emptyMap() }, { emptyMap() })
