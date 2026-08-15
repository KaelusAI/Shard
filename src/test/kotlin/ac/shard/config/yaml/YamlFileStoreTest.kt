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
package ac.shard.config.yaml

import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class YamlFileStoreTest {

  private val fixedNow = { Instant.parse("2026-08-10T12:30:00Z") }
  private val stamp = "20260810-123000"

  private fun store(dir: Path, logger: Logger = mockk(relaxed = true)) =
    YamlFileStore(dir.toFile(), logger, fixedNow)

  private fun seed(dir: Path, vararg files: Pair<String, String>) {
    files.forEach { (name, body) -> dir.resolve(name).toFile().writeText(body) }
  }

  @Test
  fun `a good write lands and leaves a backup of what was there`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "ai:\n  enabled: true\n")

    val outcome = store(dir).write(mapOf("config.yml" to "ai:\n  enabled: false\n"))

    assertIs<WriteOutcome.Written>(outcome)
    assertEquals(stamp, outcome.stamp)
    assertEquals("ai:\n  enabled: false\n", dir.resolve("config.yml").toFile().readText())
    assertEquals(
      "ai:\n  enabled: true\n",
      File(dir.toFile(), "backups/$stamp/config.yml").readText(),
      "the backup must hold what the file said before the write",
    )
  }

  @Test
  fun `two writes in the same second do not share a backup`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "ai:\n  enabled: true\n")
    val store = store(dir)

    store.write(mapOf("config.yml" to "ai:\n  enabled: false\n"))
    val second = store.write(mapOf("config.yml" to "ai:\n  enabled: true\n"))

    assertIs<WriteOutcome.Written>(second)
    assertEquals("$stamp-2", second.stamp)
    assertEquals(
      "ai:\n  enabled: true\n",
      File(dir.toFile(), "backups/$stamp/config.yml").readText(),
      "the first backup must keep what the first write replaced",
    )
    assertEquals(
      "ai:\n  enabled: false\n",
      File(dir.toFile(), "backups/$stamp-2/config.yml").readText(),
    )
  }

  @Test
  fun `undo puts back what the write replaced`(@TempDir dir: Path) {
    val original = "ai:\n  enabled: true\n"
    seed(dir, "config.yml" to original)
    val store = store(dir)
    val written = store.write(mapOf("config.yml" to "ai:\n  enabled: false\n"))

    val undone = store.restore((written as WriteOutcome.Written).stamp)

    assertIs<WriteOutcome.Written>(undone)
    assertEquals(original, dir.resolve("config.yml").toFile().readText())
  }

  @Test
  fun `undo of an unknown stamp changes nothing`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "ai:\n  enabled: true\n")

    val outcome = store(dir).restore("20990101-000000")

    assertIs<WriteOutcome.Rejected>(outcome)
    assertEquals("ai:\n  enabled: true\n", dir.resolve("config.yml").toFile().readText())
  }

  @Test
  fun `undo is itself undoable, because it backs up first`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "ai:\n  enabled: true\n")
    val store = store(dir)
    val first = store.write(mapOf("config.yml" to "ai:\n  enabled: false\n"))

    store.restore((first as WriteOutcome.Written).stamp)

    assertTrue(store.stamps().size >= 2, "the undo must leave a backup of what it replaced")
  }

  @Test
  fun `a file that stops parsing is rolled back`(@TempDir dir: Path) {
    val original = "ai:\n  buffer:\n    flag: 50.0\n"
    seed(dir, "config.yml" to original)

    val outcome =
      store(dir).write(mapOf("config.yml" to "ai:\n  buffer:\n    flag:8.0\n   bad: [\n"))

    assertIs<WriteOutcome.RolledBack>(outcome)
    assertEquals(original, dir.resolve("config.yml").toFile().readText(), "the file must be back")
    assertContains(outcome.reason, "config.yml")
  }

  @Test
  fun `one broken file takes the whole batch back`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "a: 1\n", "monitor.yml" to "b: 2\n")

    val outcome =
      store(dir).write(mapOf("config.yml" to "a: 11\n", "monitor.yml" to "b: [unclosed\n\tc: 3\n"))

    assertIs<WriteOutcome.RolledBack>(outcome)
    assertEquals(
      "a: 1\n",
      dir.resolve("config.yml").toFile().readText(),
      "config must roll back too",
    )
    assertEquals("b: 2\n", dir.resolve("monitor.yml").toFile().readText())
  }

  @Test
  fun `a name that is not on disk is refused before anything is touched`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "a: 1\n")

    val outcome = store(dir).write(mapOf("config.yml" to "a: 2\n", "ghost.yml" to "c: 3\n"))

    assertIs<WriteOutcome.Rejected>(outcome)
    assertEquals("a: 1\n", dir.resolve("config.yml").toFile().readText())
    assertTrue(!File(dir.toFile(), "backups").exists(), "a refused write leaves no backup behind")
  }

  @Test
  fun `no temporary file is left behind`(@TempDir dir: Path) {
    seed(dir, "config.yml" to "a: 1\n")

    store(dir).write(mapOf("config.yml" to "a: 2\n"))

    assertTrue(
      dir.toFile().listFiles()!!.none { it.name.endsWith(".tmp") },
      "the atomic move must not leave a .tmp next to the config",
    )
  }
}
