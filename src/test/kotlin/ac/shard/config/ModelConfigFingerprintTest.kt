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
package ac.shard.config

import ac.shard.Shard
import ac.shard.ai.label.LabelMode
import ac.shard.connect.CredentialsStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModelConfigFingerprintTest {

  @Test
  fun `the fingerprint is a crc32 of a spelled-out canonical form`(@TempDir dir: Path) {
    val manager = manager(dir)
    manager.updateAiParams(
      preWindow = 32,
      postWindow = 32,
      step = 32,
      model = "shard_2",
      labels = listOf("aim", "trigger"),
      labelNames = mapOf("trigger" to "Auto Clicker", "aim" to "Aim Assist"),
      legitLabels = listOf("clean"),
    )

    assertEquals("0b8230d2", manager.modelConfigFingerprint())

    assertEquals(
      crc32(
        """
        pre_window=32
        post_window=32
        step=32
        model=shard_2
        labels=aim,trigger
        legit_labels=clean
        label_mode=
        label_thresholds=
        """
          .trimIndent()
      ),
      manager.modelConfigFingerprint(),
      "the canonical form is the contract, spelled out here so a second implementation can match it",
    )
  }

  @Test
  fun `reordering titles or legit labels does not move the fingerprint`(@TempDir dir: Path) {
    val first = manager(dir)
    first.updateAiParams(
      null,
      null,
      null,
      labels = listOf("aim", "trigger"),
      labelNames = mapOf("aim" to "Aim", "trigger" to "Trigger"),
      legitLabels = listOf("clean", "none"),
    )
    val second = manager(dir)
    second.updateAiParams(
      null,
      null,
      null,
      labels = listOf("aim", "trigger"),
      labelNames = mapOf("trigger" to "Trigger", "aim" to "Aim"),
      legitLabels = listOf("none", "clean"),
    )

    assertEquals(first.modelConfigFingerprint(), second.modelConfigFingerprint())
  }

  @Test
  fun `reordering labels does move it, because probabilities are matched by position`(
    @TempDir dir: Path
  ) {
    val first = manager(dir)
    first.updateAiParams(null, null, null, labels = listOf("aim", "trigger"))
    val second = manager(dir)
    second.updateAiParams(null, null, null, labels = listOf("trigger", "aim"))

    assertNotEquals(first.modelConfigFingerprint(), second.modelConfigFingerprint())
  }

  @Test
  fun `renaming a label leaves the fingerprint alone, because names change nothing`(
    @TempDir dir: Path
  ) {
    val manager = manager(dir)
    manager.updateAiParams(null, null, null, labels = listOf("aim"))
    val baseline = manager.modelConfigFingerprint()

    manager.updateAiParams(null, null, null, labelNames = mapOf("aim" to "Aim Assist"))

    assertEquals(
      baseline,
      manager.modelConfigFingerprint(),
      "a cosmetic rename must not read as a configuration mismatch",
    )
  }

  @Test
  fun `changing what the model calls clean does move it, because detection depends on it`(
    @TempDir dir: Path
  ) {
    val manager = manager(dir)
    manager.updateAiParams(null, null, null, labels = listOf("aim"), legitLabels = listOf("clean"))
    val baseline = manager.modelConfigFingerprint()

    manager.updateAiParams(null, null, null, legitLabels = listOf("normal"))

    assertNotEquals(baseline, manager.modelConfigFingerprint())
  }

  @Test
  fun `the model says what shape it is, and config overrides it`(@TempDir dir: Path) {
    val manager = manager(dir)
    assertEquals(null, manager.effectiveLabelMode, "nothing declared, nothing assumed")

    manager.updateAiParams(null, null, null, labelMode = "multilabel")

    assertEquals(LabelMode.MULTI_LABEL, manager.effectiveLabelMode)
    assertEquals(LabelMode.MULTI_LABEL, manager.aiServerLabelMode)
  }

  @Test
  fun `the declared shape survives a restart`(@TempDir dir: Path) {
    for (mode in LabelMode.entries) {
      val before = manager(dir)
      before.updateAiParams(32, 32, 32, labels = listOf("aim"), labelMode = mode.wire)
      val fingerprint = before.modelConfigFingerprint()

      val after = manager(dir)

      assertEquals(mode, after.aiServerLabelMode, "$mode was lost between restarts")
      assertEquals(fingerprint, after.modelConfigFingerprint(), "$mode moved the fingerprint")
    }
  }

  @Test
  fun `per-label thresholds survive a restart`(@TempDir dir: Path) {
    val before = manager(dir)
    before.updateAiParams(
      32,
      32,
      32,
      labels = listOf("aim", "trigger"),
      labelThresholds =
        mapOf(
          "aim" to mapOf("cheat" to 0.9, "legit" to 0.1),
          "trigger" to mapOf("cheat" to 0.85, "legit" to 0.15),
        ),
    )
    val fingerprint = before.modelConfigFingerprint()

    val after = manager(dir)

    assertEquals(before.aiLabelThresholds, after.aiLabelThresholds)
    assertEquals(0.85, after.aiLabelThresholds["trigger"]?.cheat)
    assertEquals(
      fingerprint,
      after.modelConfigFingerprint(),
      "thresholds left out of model.yml would make the server see a mismatch after every restart",
    )
  }

  @Test
  fun `thresholds the model cannot mean are refused rather than stored`(@TempDir dir: Path) {
    val manager = manager(dir)

    manager.updateAiParams(
      null,
      null,
      null,
      labels = listOf("aim", "trigger", "reach"),
      labelThresholds =
        mapOf(
          "aim" to mapOf("cheat" to 1.4, "legit" to 0.1),
          "trigger" to mapOf("cheat" to 0.5, "legit" to 0.7),
          "reach" to mapOf("cheat" to 0.8, "legit" to 0.2),
        ),
    )

    assertEquals(setOf("reach"), manager.aiLabelThresholds.keys)
  }

  @Test
  fun `moving a threshold moves the fingerprint, because detection depends on it`(
    @TempDir dir: Path
  ) {
    val manager = manager(dir)
    manager.updateAiParams(
      null,
      null,
      null,
      labels = listOf("aim"),
      labelThresholds = mapOf("aim" to mapOf("cheat" to 0.9, "legit" to 0.1)),
    )
    val baseline = manager.modelConfigFingerprint()

    manager.updateAiParams(
      null,
      null,
      null,
      labelThresholds = mapOf("aim" to mapOf("cheat" to 0.8, "legit" to 0.1)),
    )

    assertNotEquals(baseline, manager.modelConfigFingerprint())
  }

  @Test
  fun `a shape nobody recognises is refused rather than guessed at`(@TempDir dir: Path) {
    val manager = manager(dir)

    manager.updateAiParams(null, null, null, labelMode = "quantum")

    assertEquals(null, manager.aiServerLabelMode)
  }

  @Test
  fun `the declared shape moves the fingerprint, because detection depends on it`(
    @TempDir dir: Path
  ) {
    val manager = manager(dir)
    manager.updateAiParams(null, null, null, labels = listOf("aim", "trigger"))
    val baseline = manager.modelConfigFingerprint()

    manager.updateAiParams(null, null, null, labelMode = "multiclass")

    assertNotEquals(baseline, manager.modelConfigFingerprint())
  }

  private fun crc32(canonical: String): String {
    val crc = CRC32()
    crc.update(canonical.toByteArray(Charsets.UTF_8))
    return "%08x".format(crc.value)
  }

  private fun manager(dir: Path): ConfigManager {
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.dataFolder } returns dir.toFile()
    every { plugin.logger } returns mockk<Logger>(relaxed = true)
    every { plugin.saveResource(any(), any()) } answers
      {
        val name = firstArg<String>()
        val source = this::class.java.classLoader.getResourceAsStream(name) ?: return@answers
        File(dir.toFile(), name).apply { parentFile.mkdirs() }.outputStream().use(source::copyTo)
      }
    return ConfigManager(plugin, CredentialsStore(plugin))
  }
}
