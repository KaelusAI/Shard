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

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DeltaCodecTest {

  private val full =
    """
    {
      "result_id": "res_1",
      "session_id": "ses_1",
      "issued_at": "2026-08-10T12:00:00Z",
      "baseline": { "config.yml": "sha256:aa", "monitor.yml": "sha256:bb" },
      "changes": {
        "config.yml": {
          "ai/buffer/flag": { "was": 50.0, "now": 80.0 },
          "ai/enabled": { "was": true, "now": false }
        }
      },
      "disabled_regions": { "world": ["spawn", "arena"] },
      "punishments": [ { "group": "AI", "actions": { "1": ["[alert]"] } } ]
    }
    """
      .trimIndent()

  @Test
  fun `a well formed result decodes into the shape the applier takes`() {
    val decoded = DeltaCodec.decode(full)

    assertIs<DecodeResult.Decoded>(decoded)
    val result = decoded.result
    assertEquals("res_1", result.resultId)
    assertEquals(Instant.parse("2026-08-10T12:00:00Z"), result.issuedAt)
    assertEquals(mapOf("config.yml" to "sha256:aa", "monitor.yml" to "sha256:bb"), result.baseline)
    assertEquals(
      listOf(Change("ai/buffer/flag", "50.0", "80.0"), Change("ai/enabled", "true", "false")),
      result.delta.changes.getValue("config.yml"),
    )
    assertEquals(mapOf("world" to listOf("spawn", "arena")), result.delta.disabledRegions)
    assertEquals(
      listOf(PunishmentEdit("AI", mapOf("1" to listOf("[alert]")))),
      result.delta.punishments,
    )
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "not json at all",
        "[]",
        "\"a string\"",
        """{"session_id":"ses_1","issued_at":"2026-08-10T12:00:00Z"}""",
        """{"result_id":"res_1","issued_at":"2026-08-10T12:00:00Z"}""",
        """{"result_id":"res_1","session_id":"ses_1"}""",
        """{"result_id":"res_1","session_id":"ses_1","issued_at":"yesterday"}""",
      ]
  )
  fun `a result missing what it must carry is malformed, not half decoded`(payload: String) {
    assertIs<DecodeResult.Malformed>(DeltaCodec.decode(payload))
  }

  @Test
  fun `a change that is an object instead of a value is dropped rather than stringified`() {
    val payload =
      """
      {
        "result_id": "res_1", "session_id": "ses_1", "issued_at": "2026-08-10T12:00:00Z",
        "changes": { "config.yml": {
          "ai/buffer/flag": { "was": 50.0, "now": { "nested": "trick" } },
          "ai/enabled": { "was": true, "now": false }
        } }
      }
      """
        .trimIndent()

    val decoded = DeltaCodec.decode(payload)

    assertIs<DecodeResult.Decoded>(decoded)
    assertEquals(
      listOf(Change("ai/enabled", "true", "false")),
      decoded.result.delta.changes.getValue("config.yml"),
    )
  }

  @Test
  fun `a result with an absurd number of changes is refused`() {
    val many = (1..600).joinToString(",") { """"key$it": { "was": 1, "now": 2 }""" }
    val payload =
      """{"result_id":"r","session_id":"s","issued_at":"2026-08-10T12:00:00Z",""" +
        """"changes":{"config.yml":{$many}}}"""

    assertIs<DecodeResult.Malformed>(DeltaCodec.decode(payload))
  }
}
