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
package ac.shard.editor

import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EditorSchemaTest {

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "ai/api-key",
        "ai/server",
        "connect/panel-url",
        "database/type",
        "database/mysql/password",
        "database/sqlite/file",
        "redis/host",
        "redis/password",
        "redis/port",
        "telemetry/group-id",
        "config-version",
      ]
  )
  fun `a key the panel must never touch is refused`(path: String) {
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", path, "anything"))
  }

  @Test
  fun `a key nobody declared is refused rather than guessed at`() {
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/buffer/made-up", "1"))
    assertIs<Verdict.Refused>(EditorSchema.check("secrets.yml", "ai/enabled", "true"))
  }

  @Test
  fun `an editable key takes a value of the right shape`() {
    assertIs<Verdict.Allowed>(EditorSchema.check("config.yml", "ai/buffer/flag", "8.0"))
    assertIs<Verdict.Allowed>(EditorSchema.check("config.yml", "ai/enabled", "false"))
    assertIs<Verdict.Allowed>(EditorSchema.check("config.yml", "locale", "\"ru\""))
    assertIs<Verdict.Allowed>(EditorSchema.check("monitor.yml", "auto/exit-ratio", "0.5"))
  }

  @Test
  fun `a value of the wrong shape is refused`() {
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/enabled", "yes"))
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/buffer/flag", "loads"))
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "locale", "de"))
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/damage-reduction/prob", "1.5"))
    assertIs<Verdict.Refused>(EditorSchema.check("monitor.yml", "auto/exit-ratio", "-0.1"))
    assertIs<Verdict.Refused>(EditorSchema.check("monitor.yml", "update", "0"))
  }

  @Test
  fun `a number that is not a real number is refused`() {
    listOf("NaN", " NaN ", "+NaN", "-NaN", "Infinity", "-Infinity").forEach {
      assertIs<Verdict.Refused>(
        EditorSchema.check("config.yml", "ai/buffer/flag", it),
        "$it must not reach the file",
      )
    }
  }

  @Test
  fun `a value that quietly switches the check off is refused`() {
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/buffer/multiplier", "0"))
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/buffer/flag", "0"))
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "ai/damage-reduction/prob", "0"))
    assertIs<Verdict.Refused>(
      EditorSchema.check("config.yml", "ai/backoff/initial-duration", "3600")
    )
  }

  @Test
  fun `switching the check off everywhere at once is refused`() {
    assertIs<Verdict.Refused>(EditorSchema.checkRegions(mapOf("*" to listOf("__global__"))))
    assertIs<Verdict.Allowed>(EditorSchema.checkRegions(mapOf("world" to listOf("__global__"))))
    assertIs<Verdict.Allowed>(EditorSchema.checkRegions(mapOf("*" to listOf("spawn"))))
  }

  @Test
  fun `a pair is judged on the state it lands in`() {
    assertIs<Verdict.Refused>(
      EditorSchema.checkTogether(
        "config.yml",
        mapOf("ai/buffer/flag" to "8.0", "ai/buffer/reset-on-flag" to "25.0"),
      )
    )
    assertIs<Verdict.Allowed>(
      EditorSchema.checkTogether(
        "config.yml",
        mapOf("ai/buffer/flag" to "50.0", "ai/buffer/reset-on-flag" to "25.0"),
      )
    )
    assertIs<Verdict.Refused>(
      EditorSchema.checkTogether(
        "config.yml",
        mapOf("ai/backoff/initial-duration" to "60", "ai/backoff/max-duration" to "30"),
      )
    )
  }

  @Test
  fun `a network name may not smuggle anything odd in`() {
    assertIs<Verdict.Allowed>(EditorSchema.check("config.yml", "network/name", "\"PvP-1\""))
    assertIs<Verdict.Refused>(EditorSchema.check("config.yml", "network/name", "\"<click>\""))
    assertIs<Verdict.Refused>(
      EditorSchema.check("config.yml", "network/name", "\"${"x".repeat(64)}\"")
    )
  }
}
