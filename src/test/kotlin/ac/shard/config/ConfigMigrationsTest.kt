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
    val drops = ConfigMigrations.forcedDropsForUpgradeFrom(version, name, file)
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
    assertContains(ConfigMigrations.forcedDropsForUpgradeFrom(3), "ai/continuous")
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
    assertContains(merged, "gzip: true")
    assertFalse(merged.contains("continuous:"))
    assertContains(merged, "config-version: ${ConfigMigrations.LATEST_VERSION}")
    assertContains(merged, "# Redis connection, used by the network section below.")
    assertContains(merged, """name: "server-1"""")
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
  fun `user-modified value for an existing key is not overwritten on merge`(
    @TempDir tempDir: Path
  ) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(
      """
      config-version: 4
      locale: "en"
      ai:
        enabled: true
        gzip: false
      """
        .trimIndent() + "\n"
    )
    runMigration(userFile)
    val merged = userFile.readText()
    assertContains(merged, "gzip: false")
    assertFalse(merged.contains("gzip: true"))
  }

  @Test
  fun `legacy continuous key is dropped on upgrade`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(
      """
      config-version: 3
      locale: "en"
      ai:
        enabled: true
        continuous: true
      """
        .trimIndent() + "\n"
    )
    runMigration(userFile)
    val merged = userFile.readText()
    assertFalse(merged.contains("continuous:"))
    assertContains(merged, "config-version: ${ConfigMigrations.LATEST_VERSION}")
  }

  @Test
  fun `shipped v1 inference url is replaced on upgrade`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(
      """
      config-version: 3
      ai:
        enabled: true
        server: "https://api.shard.ac/v1/inference"
      """
        .trimIndent() + "\n"
    )
    runMigration(userFile)
    val merged = userFile.readText()
    assertContains(merged, "https://api.shard.ac/v2/inference")
    assertFalse(merged.contains("api.shard.ac/v1/inference"))
  }

  @Test
  fun `self-hosted inference url survives the upgrade`(@TempDir tempDir: Path) {
    val userFile = tempDir.resolve("config.yml").toFile()
    userFile.writeText(
      """
      config-version: 3
      ai:
        enabled: true
        server: "https://my-own-box.example/v1/inference"
      """
        .trimIndent() + "\n"
    )
    runMigration(userFile)
    val merged = userFile.readText()
    assertContains(merged, "https://my-own-box.example/v1/inference")
    assertFalse(merged.contains("api.shard.ac/v2/inference"))
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

  @Test
  fun `the cross-server section keeps its values under the network name`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml").toFile()
    file.writeText(
      """
      cross-server:
        enabled: true
        server-name: "PvP"
        channel: "mine:alerts"
        alerts:
          regular: false
          suspicious: true
        suspicious-sync:
          ttl-seconds: 90
      """
        .trimIndent()
    )

    assertTrue(renameCrossServerToNetwork(file))

    val moved = file.readText()
    assertContains(moved, """name: "PvP"""")
    assertContains(moved, """channel: "mine:alerts"""")
    assertContains(moved, "ttl-seconds: 90")
    assertContains(moved, "share:")
    assertContains(moved, "alerts: false")
    assertContains(moved, "suspicious: true")
    assertFalse(moved.contains("cross-server"))
    assertFalse(moved.contains("server-name"))
  }

  @Test
  fun `the rename leaves the comments and the layout alone`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml").toFile()
    val before =
      """
      # a section about servers
      cross-server:
        # shown as the origin tag
        server-name: "Lobby"
        alerts:
          regular: true   # rule breakers
          suspicious: false

      other:
        untouched: true
      """
        .trimIndent() + "\n"
    file.writeText(before)

    assertTrue(renameCrossServerToNetwork(file))

    assertEquals(
      before
        .replace("cross-server:", "network:")
        .replace("server-name:", "name:")
        .replace("  alerts:", "  share:")
        .replace("regular:", "alerts:"),
      file.readText(),
      "only the key names may change",
    )
  }

  @Test
  fun `the shipped 1_2_1 config survives the upgrade with its comments`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml").toFile()
    val legacy =
      this::class.java.classLoader.getResource("legacy/config-1.2.1.yml")?.readText(Charsets.UTF_8)
        ?: error("legacy/config-1.2.1.yml is missing")
    file.writeText(legacy)
    assertEquals(3, ConfigMigrations.readVersion(file))

    renameCrossServerToNetwork(file)
    runMigration(file)

    val merged = file.readText()
    assertContains(merged, "config-version: ${ConfigMigrations.LATEST_VERSION}")
    assertContains(merged, "flag-overrides-list: true")
    assertFalse(merged.contains("cross-server"), "the old section name must be gone")
    assertFalse(merged.contains("enabled: false\n  # Category toggles"), "debug.enabled must go")
    assertContains(merged, "# Locale: en, ru")
    assertContains(merged, "# Enable AI check?")
    assertContains(merged, "# Redis connection, used by the network section below.")
    assertContains(merged, "probability: false    # AI probability values per check")
    assertTrue(
      merged.lineSequence().count { it.trimStart().startsWith("#") } > 40,
      "an upgrade must not strip the comments out of the file",
    )
  }

  @Test
  fun `the upgrade does not leave debug categories talking`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml").toFile()
    val legacy =
      this::class.java.classLoader.getResource("legacy/config-1.2.1.yml")?.readText(Charsets.UTF_8)
        ?: error("legacy/config-1.2.1.yml is missing")
    file.writeText(legacy)
    assertContains(legacy, "timeout: true", ignoreCase = false)

    renameCrossServerToNetwork(file)
    runMigration(file)

    val merged = file.readText()
    assertFalse(
      merged.contains("timeout: true"),
      "the master switch used to mute this one, so it must not survive the upgrade switched on",
    )
    assertFalse(merged.contains("service-unavailable: true"))
    assertContains(merged, "timeout: false")
    assertContains(merged, "service-unavailable: false")
  }

  @Test
  fun `the upgrade drops damage reduction and leaves mitigations out of config`(
    @TempDir dir: Path
  ) {
    val file = dir.resolve("config.yml").toFile()
    val legacy =
      this::class.java.classLoader.getResource("legacy/config-1.2.1.yml")?.readText(Charsets.UTF_8)
        ?: error("legacy/config-1.2.1.yml is missing")
    file.writeText(legacy)
    assertContains(legacy, "damage-reduction:")

    renameCrossServerToNetwork(file)
    runMigration(file)

    val merged = file.readText()
    assertFalse(
      merged.contains("damage-reduction:"),
      "the old block drove damage off the raw probability and has no counterpart here",
    )
    assertFalse(
      merged.contains("\nmitigation:"),
      "mitigations live in their own file, config.yml must not grow a second copy",
    )
  }

  @Test
  fun `a config carrying the in-development mitigation section has it removed`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml").toFile()
    file.writeText(
      """
      config-version: 3
      locale: "en"
      mitigation:
        enabled: true
        mitigations:
          melee:
            enabled: true
      """
        .trimIndent() + "\n"
    )

    runMigration(file)

    val merged = file.readText()
    assertFalse(merged.contains("\nmitigation:"), "the whole section goes, not just its keys")
    assertContains(merged, "config-version: ${ConfigMigrations.LATEST_VERSION}")
  }

  @Test
  fun `a config already on the network name is left alone`(@TempDir dir: Path) {
    val file = dir.resolve("config.yml").toFile()
    file.writeText("network:\n  enabled: true\n  name: \"PvP\"\n")

    assertFalse(renameCrossServerToNetwork(file))
  }
}
