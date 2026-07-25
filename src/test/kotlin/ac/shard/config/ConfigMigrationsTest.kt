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

import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.vyarus.yaml.updater.YamlUpdater

class ConfigMigrationsTest {

  private fun bundledTemplate(name: String = "config.yml"): File =
    File(
      this::class.java.classLoader.getResource(name)?.toURI()
        ?: error("bundled $name is missing from test classpath")
    )

  private fun runMigration(file: File, name: String = "config.yml") {
    val version = ConfigMigrations.readVersion(file, name)
    val drops = ConfigMigrations.forcedDropsForUpgradeFrom(version, name)
    YamlUpdater.create(file, bundledTemplate(name)).backup(false).deleteProps(drops).update()
  }

  private val legacyUserConfig =
    """
    # Locale: en, ru
    locale: "ru"

    ai:
      # Enable AI check?
      enabled: true
      # URL for the AI inference API.
      server: "https://example.internal/inference"
      # The API key for the AI server.
      api-key: "MY_KEY"
      # The number of ticks to send in a sequence to the AI.
      sequence: 40
      # The number of ticks to wait before sending the next sequence.
      step: 10
      buffer:
        flag: 50.0
        reset-on-flag: 25.0
        multiplier: 100.0
        decrease: 0.25
      damage-reduction:
        enabled: true
        prob: 0.9
        multiplier: 1.0
      worldguard:
        enabled: true
        disabled-regions:
          world:
            - "spawn"
      backoff:
        initial-duration: 5
        max-duration: 60
        multiplier: 2.0

    client-brand:
      ignored-clients:
        - "^vanilla${'$'}"
      disconnect-blacklisted-forge-versions: true

    alerts:
      print-to-console: true

    history:
      enabled: true

    database:
      type: sqlite
      sqlite:
        file: "violations.db"
      mysql:
        host: "localhost"
        port: 3306
        database: "shard"
        username: "root"
        password: "password"
        use-ssl: false

    suspicious:
      alerts:
        buffer: 25.0

    cancel-duplicate-packet: true
    force-cancel-duplicate-packet: false
    ignore-duplicate-packet-rotation: true

    debug:
      enabled: false
      categories:
        probability: false    # AI probability values per check
        timeout: false        # API timeouts and retries
        rate-limit: false     # Rate limiting events
        worldguard: false     # WorldGuard region checks
        packet-duplication: false  # Mojang packet duplication bugs
    """
      .trimIndent() + "\n"

  @Test
  fun `readVersion returns 0 when key is absent`(@TempDir tempDir: Path) {
    val file = tempDir.resolve("config.yml").toFile()
    file.writeText("""locale: "en"""" + "\n")
    assertEquals(0, ConfigMigrations.readVersion(file))
  }

  @Test
  fun `readVersion returns LATEST when file is missing`(@TempDir tempDir: Path) {
    assertEquals(
      ConfigMigrations.LATEST_VERSION,
      ConfigMigrations.readVersion(tempDir.resolve("missing.yml").toFile()),
    )
  }

  @Test
  fun `readVersion parses an integer value`(@TempDir tempDir: Path) {
    val file = tempDir.resolve("config.yml").toFile()
    file.writeText("# header\nconfig-version: 7\nlocale: \"en\"\n")
    assertEquals(7, ConfigMigrations.readVersion(file))
  }

  @Test
  fun `forcedDropsForUpgradeFrom drops config-version only when behind`() {
    assertTrue(
      ConfigMigrations.forcedDropsForUpgradeFrom(ConfigMigrations.LATEST_VERSION).isEmpty()
    )
    assertContains(ConfigMigrations.forcedDropsForUpgradeFrom(0), "config-version")
  }

  @Test
  fun `merge adds missing keys and preserves comments, blank lines, user values`(
    @TempDir tempDir: Path
  ) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(legacyUserConfig)
    assertEquals(0, ConfigMigrations.readVersion(userFile))

    runMigration(userFile)

    val merged = userFile.readText()
    assertContains(merged, "continuous: false")
    assertContains(merged, "config-version: ${ConfigMigrations.LATEST_VERSION}")
    assertContains(merged, "# Redis connection, used by cross-server alerting.")
    assertContains(merged, """server-name: "server-1"""")
    assertContains(merged, """channel: "shard:alerts"""")
    assertContains(merged, "suspicious-sync:")
    assertContains(merged, "ttl-seconds: 30")
    assertContains(merged, """server: "https://example.internal/inference"""")
    assertContains(merged, """api-key: "MY_KEY"""")
    assertContains(merged, """locale: "ru"""")
    assertContains(merged, "probability: false    # AI probability values per check")
    assertContains(merged, "packet-duplication: false  # Mojang packet duplication bugs")
    assertContains(merged, "\n\nclient-brand:")
    assertContains(merged, "\n\nalerts:")
    val versionLines = merged.lineSequence().count { it.trim().startsWith("config-version:") }
    assertEquals(1, versionLines)
  }

  @Test
  fun `second run is a no-op`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(
      """
      locale: "en"
      ai:
        enabled: true
        step: 10
      """
        .trimIndent() + "\n"
    )
    runMigration(userFile)
    val afterFirst = userFile.readText()
    runMigration(userFile)
    assertEquals(afterFirst, userFile.readText(), "second run should not touch the file")
  }

  @Test
  fun `user-modified value for a new key is not overwritten on merge`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(
      """
      locale: "en"
      ai:
        enabled: true
        step: 10
        continuous: true
      """
        .trimIndent() + "\n"
    )
    runMigration(userFile)
    val merged = userFile.readText()
    // The user's `continuous: true` must survive the merge, not be replaced with template default.
    assertContains(merged, "continuous: true")
    assertFalse(merged.contains("continuous: false"))
  }

  @Test
  fun `monitor merge keeps user values and lists while adding new keys`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("monitor.yml").toFile()
    userFile.writeText(
      """
      update: 5
      view:
        template:
          prefix: "MYPREFIX"
      modes:
        compact: [prob, name]
      """
        .trimIndent() + "\n"
    )
    assertEquals(0, ConfigMigrations.readVersion(userFile, "monitor.yml"))

    runMigration(userFile, "monitor.yml")

    val merged = userFile.readText()
    assertContains(merged, "update: 5")
    assertContains(merged, """prefix: "MYPREFIX"""")
    assertContains(merged, "compact: [prob, name]")
    assertContains(merged, "config-version: ${ConfigMigrations.MONITOR_LATEST_VERSION}")
    assertContains(merged, "per-player: true")
    assertContains(merged, "theme:")
    val versionLines = merged.lineSequence().count { it.trim().startsWith("config-version:") }
    assertEquals(1, versionLines)
  }

  @Test
  fun `a second monitor merge changes nothing`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("monitor.yml").toFile()
    userFile.writeText("update: 5\n")
    runMigration(userFile, "monitor.yml")
    val first = userFile.readText()

    runMigration(userFile, "monitor.yml")

    assertEquals(first, userFile.readText())
  }

  @Test
  fun `monitor and config versions are tracked separately`() {
    assertEquals(ConfigMigrations.LATEST_VERSION, ConfigMigrations.latestVersion("config.yml"))
    assertEquals(
      ConfigMigrations.MONITOR_LATEST_VERSION,
      ConfigMigrations.latestVersion("monitor.yml"),
    )
    assertTrue(
      ConfigMigrations.forcedDropsForUpgradeFrom(
          ConfigMigrations.MONITOR_LATEST_VERSION,
          "monitor.yml",
        )
        .isEmpty()
    )
    assertContains(ConfigMigrations.forcedDropsForUpgradeFrom(0, "monitor.yml"), "config-version")
  }
}
