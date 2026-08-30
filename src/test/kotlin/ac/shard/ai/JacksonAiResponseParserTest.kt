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
package ac.shard.ai

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class JacksonAiResponseParserTest {
  private val parser = JacksonAiResponseParser()

  @Test
  fun `parses numeric probability`() {
    val response = parser.parse("""{"probability":0.93}""")
    assertEquals(0.93, response.probability)
  }

  @Test
  fun `parses textual probability`() {
    val response = parser.parse("""{"probability":"0.75"}""")
    assertEquals(0.75, response.probability)
  }

  @Test
  fun `throws for missing probability`() {
    assertFailsWith<IllegalArgumentException> { parser.parse("""{"details":{"sequence":10}}""") }
  }

  @Test
  fun `throws for invalid probability type`() {
    assertFailsWith<IllegalArgumentException> { parser.parse("""{"probability":{"value":0.5}}""") }
  }

  @Test
  fun `parses labels and probabilities`() {
    val response =
      parser.parse(
        """{"probability":0.62,"probabilities":[0.62,0.04],"labels":["AIM_AURA","AUTOCRYSTAL"]}"""
      )
    assertEquals(listOf(0.62, 0.04), response.probabilities)
    assertEquals(listOf("AIM_AURA", "AUTOCRYSTAL"), response.labels)
  }

  @Test
  fun `keeps labels null for a single-headed model`() {
    val response = parser.parse("""{"probability":0.93}""")
    assertEquals(null, response.labels)
    assertEquals(null, response.probabilities)
  }

  @Test
  fun `a broken entry throws the whole probabilities array away instead of calling it zero`() {
    val parsed =
      JacksonAiResponseParser()
        .parse("""{"probability":0.9,"labels":["aim","trigger"],"probabilities":[0.95,null]}""")

    assertNull(
      parsed.probabilities,
      "a substituted 0.0 keeps the array length, so the size check passes and the buffer is " +
        "told that label is certainly clean",
    )
  }

  @Test
  fun `probabilities keyed by name are read without relying on order`() {
    val parsed =
      JacksonAiResponseParser()
        .parse("""{"probability":0.9,"probabilities":{"trigger":0.96,"aim":0.35}}""")

    assertEquals(mapOf("trigger" to 0.96, "aim" to 0.35), parsed.namedProbabilities)
    assertNull(parsed.probabilities, "an object is not a positional array")
  }
}
