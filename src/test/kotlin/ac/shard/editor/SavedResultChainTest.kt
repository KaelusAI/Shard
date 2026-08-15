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

import ac.shard.config.yaml.YamlFileStore
import ac.shard.config.yaml.YamlPatcher
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedResultChainTest {

  private val issued = "2026-08-10T12:00:00Z"
  private val now = Instant.parse(issued).plusSeconds(30)

  private val payload =
    """
    {"result_id":"res_live_1","session_id":"d4ff5afa-e417-4949-8290-436249647365",
     "issued_at":"$issued",
     "baseline":{"config.yml":"sha256:aa"},
     "changes":{"config.yml":{"ai/buffer/flag":{"was":50.0,"now":80.0}}},
     "disabled_regions":{"world":["spawn"]},
     "punishments":[{"group":"AI","actions":{"1":["[alert]","[log]"]}}]}
    """
      .trimIndent()

  private fun bundled(name: String): String =
    File(
        this::class.java.classLoader.getResource(name)?.toURI()
          ?: error("bundled $name is missing from test classpath")
      )
      .readText()

  private fun world(dir: Path): EditorApply {
    listOf("config.yml", "monitor.yml", "punishments.yml").forEach {
      dir.resolve(it).toFile().writeText(bundled(it))
    }
    val store = YamlFileStore(dir.toFile(), mockk(relaxed = true)) { now }
    return EditorApply(dir.toFile(), store)
  }

  @Test
  fun `a result travels from the wire to the files`(@TempDir dir: Path) {
    val decoded = DeltaCodec.decode(payload)
    assertIs<DecodeResult.Decoded>(decoded)
    assertIs<Verdict.Allowed>(
      ResultGuard(Duration.ofMinutes(10)) { now }
        .accept(decoded.result.resultId, decoded.result.issuedAt)
    )

    val applied = world(dir).apply(decoded.result.delta)

    assertIs<ApplyResult.Applied>(applied)
    val config = dir.resolve("config.yml").toFile()
    assertContains(config.readText(), "flag: 80.0")
    assertEquals(
      mapOf("world" to listOf("spawn")),
      YamlPatcher.readStringListMap(YamlPatcher.read(config), "ai/worldguard/disabled-regions"),
    )
    assertContains(dir.resolve("punishments.yml").toFile().readText(), "- \"[log]\"")
  }

  @Test
  fun `the same result is not applied twice`() {
    val guard = ResultGuard(Duration.ofMinutes(10)) { now }
    val decoded = DeltaCodec.decode(payload)
    assertIs<DecodeResult.Decoded>(decoded)

    assertIs<Verdict.Allowed>(guard.accept(decoded.result.resultId, decoded.result.issuedAt))
    assertIs<Verdict.Refused>(guard.accept(decoded.result.resultId, decoded.result.issuedAt))
  }

  @Test
  fun `a pair broken by the panel is refused with a reason a person can read`(@TempDir dir: Path) {
    val loosening =
      payload.replace(
        "\"ai/buffer/flag\":{\"was\":50.0,\"now\":80.0}",
        "\"ai/buffer/flag\":{\"was\":50.0,\"now\":8.0}",
      )
    val decoded = DeltaCodec.decode(loosening)
    assertIs<DecodeResult.Decoded>(decoded)

    val result = world(dir).apply(decoded.result.delta)

    assertIs<ApplyResult.Refused>(result)
    assertTrue(
      result.reasons.any { it.contains("reset-on-flag") },
      "the refusal must name the key that made it impossible: ${result.reasons}",
    )
    assertContains(dir.resolve("config.yml").toFile().readText(), "flag: 50.0")
  }
}
