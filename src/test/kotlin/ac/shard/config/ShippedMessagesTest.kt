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

import ac.shard.utils.Message
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ShippedMessagesTest {
  private fun locale(name: String): ConfigView {
    val url =
      this::class.java.classLoader.getResource("messages/messages_$name.yml")
        ?: error("bundled messages_$name.yml is missing from the test classpath")
    return ConfigView(YamlConfigurationLoader.builder().file(File(url.toURI())).build().load())
  }

  private fun keysOf(node: ConfigurationNode, prefix: String = ""): List<String> =
    if (node.isMap) {
      node.childrenMap().flatMap { (key, child) ->
        keysOf(child, if (prefix.isEmpty()) key.toString() else "$prefix.$key")
      }
    } else {
      listOf(prefix)
    }

  @Test
  fun `every message key resolves in english`() {
    val view = locale("en")
    val missing = Message.entries.filter { view.node(it.path).empty() }.map { it.path }

    assertEquals(emptyList(), missing)
  }

  @Test
  fun `every message key resolves in russian`() {
    val view = locale("ru")
    val missing = Message.entries.filter { view.node(it.path).empty() }.map { it.path }

    assertEquals(emptyList(), missing)
  }

  @Test
  fun `the two shipped locales carry the same keys`() {
    val english = keysOf(locale("en").root()).toSet()
    val russian = keysOf(locale("ru").root()).toSet()

    assertEquals(emptyList(), (english - russian).sorted())
    assertEquals(emptyList(), (russian - english).sorted())
  }
}
