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

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ModelOverrideTest {

  private fun parse(yaml: String): Map<String, ModelOverride> =
    ModelOverride.parse(
      YamlConfigurationLoader.builder().buildAndLoadString(yaml).node("ai", "models")
    )

  @Test
  fun `an unquoted off switches the model off although yaml reads it as false`() {
    val models =
      parse(
        """
        ai:
          models:
            a: off
            b: "off"
            c: auto
        """
          .trimIndent()
      )

    assertEquals(false, models.getValue("a").enabled)
    assertEquals(false, models.getValue("b").enabled)
    assertEquals(true, models.getValue("c").enabled)
  }

  @Test
  fun `roles switch off one by one and never on`() {
    val models =
      parse(
        """
        ai:
          models:
            a:
              punish: false
              alert: off
              mitigate: true
            b:
              enabled: off
        """
          .trimIndent()
      )

    assertEquals(ModelOverride(true, setOf("punish", "alert")), models.getValue("a"))
    assertEquals(false, models.getValue("b").enabled)
  }

  @Test
  fun `no section means no overrides`() {
    assertEquals(emptyMap(), parse("ai:\n  enabled: true\n"))
  }
}
