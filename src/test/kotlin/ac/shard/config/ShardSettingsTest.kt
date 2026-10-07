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

import ac.shard.connect.Credentials
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class ShardSettingsTest {
  private fun view(yaml: String): ConfigView =
    ConfigView(YamlConfigurationLoader.builder().source { yaml.reader().buffered() }.build().load())

  private fun read(
    yaml: String,
    credentials: Credentials? = null,
  ): Pair<ShardSettings, List<String>> {
    val warnings = mutableListOf<String>()
    return ShardSettings.read(view(yaml), credentials, 1, warnings::add) to warnings
  }

  private fun linked(key: String = "k1", url: String? = "https://a.example/v2/x") =
    Credentials(key, null, null, null, url)

  @Test
  fun `linked credentials replace the configured address and key`() {
    val (settings, warnings) =
      read("ai:\n  enabled: false\n  server: https://b.example/v1/x\n  api-key: k0\n", linked())

    assertEquals("k1", settings.ai.key)
    assertEquals("https://a.example/v2/x", settings.ai.url)
    assertTrue(settings.ai.enabled)
    assertEquals(1, warnings.size)
  }

  @Test
  fun `the placeholder key does not warn when the server is linked`() {
    val (settings, warnings) = read("ai:\n  api-key: API-KEY\n", linked(url = null))

    assertEquals("k1", settings.ai.key)
    assertFalse(settings.ai.enabled)
    assertTrue(warnings.isEmpty())
  }

  @Test
  fun `turning telemetry off turns cloud punishments off`() {
    val (settings, _) = read("telemetry:\n  enabled: false\ncloud:\n  punishments: sync\n")

    assertEquals(CloudPunishments.OFF, settings.telemetry.cloudPunishments)
  }

  @Test
  fun `a non-positive ttl falls back to one hour with a warning`() {
    val (settings, warnings) = read("ai:\n  persistent-buffer:\n    ttl-hours: 0\n")

    assertEquals(3_600_000L, settings.buffer.ttlMillis)
    assertEquals(1, warnings.size)
  }

  @Test
  fun `the legacy region list is grouped by world with one warning`() {
    val (settings, warnings) =
      read("ai:\n  worldguard:\n    disabled-regions:\n      - R1:W1\n      - r2\n")

    assertEquals(mapOf("w1" to listOf("r1"), "*" to listOf("r2")), settings.regions.disabled)
    assertEquals(1, warnings.size)
  }

  @Test
  fun `a broken pattern is skipped with one warning`() {
    val (settings, warnings) = read("client-brand:\n  ignored-clients:\n    - '['\n    - '^x'\n")

    assertTrue(settings.clientBrand.isIgnored("xy"))
    assertFalse(settings.clientBrand.isIgnored("["))
    assertEquals(1, warnings.size)
  }

  @Test
  fun `the device address is derived only from a secure server address`() {
    val (secure, _) = read("ai:\n  server: https://a.example/v2/x\n")
    val (plain, _) = read("ai:\n  server: http://a.example/v2/x\n")

    assertEquals("https://a.example/v2/device/q", secure.ai.deviceUrl("q"))
    assertEquals(null, plain.ai.deviceUrl("q"))
  }
}
