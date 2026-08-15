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
import ac.shard.connect.CredentialsStore
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokenConfigTest {

  private fun runtime(dir: Path): Pair<Shard, Logger> {
    listOf("config.yml", "punishments.yml", "monitor.yml").forEach { name ->
      val bytes =
        this::class.java.classLoader.getResourceAsStream(name)?.readBytes()
          ?: error("bundled $name is missing from test classpath")
      dir.resolve(name).toFile().writeBytes(bytes)
    }
    val logger = mockk<Logger>(relaxed = true)
    val plugin = mockk<Shard>(relaxed = true)
    every { plugin.dataFolder } returns dir.toFile()
    every { plugin.logger } returns logger
    return plugin to logger
  }

  @Test
  fun `a config that stops parsing does not wipe the loaded values`(@TempDir dir: Path) {
    val (plugin, logger) = runtime(dir)
    val manager = ConfigManager(plugin, CredentialsStore(plugin))
    val flagBefore = manager.config.getDouble("ai.buffer.flag", -1.0)
    assertTrue(flagBefore > 0.0, "the bundled config should carry a flag threshold")

    dir.resolve("config.yml").writeText("ai:\n  buffer:\n   flag: [unclosed\n\tenabled: true\n")
    manager.reloadConfig()

    assertEquals(
      flagBefore,
      manager.config.getDouble("ai.buffer.flag", -1.0),
      "a broken reload must keep the values that were already loaded",
    )
    io.mockk.verify { logger.severe(match<String> { it.contains("could not be parsed") }) }
    io.mockk.verify { logger.severe(match<String> { it.contains("Keeping the values") }) }
  }

  @Test
  fun `a config broken from the start says the defaults are in use`(@TempDir dir: Path) {
    val (plugin, logger) = runtime(dir)
    dir.resolve("config.yml").writeText("ai:\n  buffer:\n   flag: [unclosed\n\tenabled: true\n")

    ConfigManager(plugin, CredentialsStore(plugin))

    io.mockk.verify { logger.severe(match<String> { it.contains("built-in defaults") }) }
  }

  @Test
  fun `nothing is reported when the config parses`(@TempDir dir: Path) {
    val (plugin, logger) = runtime(dir)

    ConfigManager(plugin, CredentialsStore(plugin))

    io.mockk.verify(exactly = 0) { logger.severe(any<String>()) }
  }
}
