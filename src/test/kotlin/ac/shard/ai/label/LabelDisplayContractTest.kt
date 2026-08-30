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
package ac.shard.ai.label

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class LabelDisplayContractTest {

  private fun messages(locale: String): ConfigurationNode {
    val url =
      this::class.java.classLoader.getResource("messages/messages_$locale.yml")
        ?: error("bundled messages_$locale.yml is missing from the test classpath")
    return YamlConfigurationLoader.builder().file(File(url.toURI())).build().load()
  }

  private fun text(locale: String, vararg path: String): String =
    messages(locale).node(*path).getString("")

  @Test
  fun `both alert templates name the label the model attached`() {
    for (locale in LOCALES) {
      val alert = text(locale, "alerts-format")
      assertTrue(alert.contains("<check_label>"), "$locale alerts-format must carry <check_label>")
      assertTrue(
        !alert.contains("<check_name>"),
        "$locale still shows the bare check name, so a split verdict looks like a single one",
      )
    }
  }

  @Test
  fun `history and logs show which label a flag belonged to`() {
    for (locale in LOCALES) {
      assertTrue(
        text(locale, "history", "entry").contains("<labels_line>"),
        "$locale history.entry carries the label through the line that vanishes without one",
      )
      assertTrue(text(locale, "logs", "entry").contains("<labels_line>"), "$locale logs.entry")
    }
  }

  @Test
  fun `the suspicious alert says which label carries the buffer`() {
    for (locale in LOCALES) {
      assertTrue(
        text(locale, "suspicious", "alert-triggered").contains("<label>"),
        "$locale suspicious.alert-triggered shows a number with no label",
      )
    }
  }

  @Test
  fun `the profile breaks the buffer down per label`() {
    for (locale in LOCALES) {
      val lines = messages(locale).node("profile", "lines").getList(String::class.java).orEmpty()
      assertTrue(
        lines.any { it.contains("<ai_labels>") },
        "$locale profile.lines has no per-label breakdown",
      )
    }
  }

  private companion object {
    val LOCALES = listOf("en", "ru")
  }
}
