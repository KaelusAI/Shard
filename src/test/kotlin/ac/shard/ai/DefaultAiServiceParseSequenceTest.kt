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

import ac.shard.server.AIServerProvider
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DefaultAiServiceParseSequenceTest {

  private val service =
    DefaultAiService(
      transportProvider = mockk<AIServerProvider>(relaxed = true),
      parser = mockk(relaxed = true),
    )

  @Test
  fun `parses pre window from valid JSON body`() {
    val body = """{"details":{"expected_pre_window":42}}"""
    assertEquals(42, service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `parses value with extra fields`() {
    val body =
      """{"details":{"expected_post_window":7,"received_length":40,"other":"value"},"status":"error"}"""
    assertEquals(7, service.parseIntFromBody(body, "expected_post_window"))
  }

  @Test
  fun `returns null for null input`() {
    assertNull(service.parseIntFromBody(null, "expected_pre_window"))
  }

  @Test
  fun `returns null for blank input`() {
    assertNull(service.parseIntFromBody("", "expected_pre_window"))
    assertNull(service.parseIntFromBody("   ", "expected_pre_window"))
  }

  @Test
  fun `returns null when no details object`() {
    val body = """{"error":"something"}"""
    assertNull(service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `returns null when details has no matching key`() {
    val body = """{"details":{"message":"no window here"}}"""
    assertNull(service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `parses expected step from protocol details`() {
    val body =
      """{"code":"RECONFIGURE_REQUIRED","details":{"expected_step":64,"received_length":50}}"""
    assertEquals(64, service.parseIntFromBody(body, "expected_step"))
  }

  @Test
  fun `returns null when expected key is missing`() {
    val body = """{"details":{"pre_window":50}}"""
    assertNull(service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `returns null when only error text is present`() {
    val body = """{"error":"Invalid window for model shard_2. Expected 64, got 50"}"""
    assertNull(service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `returns null for invalid JSON`() {
    assertNull(service.parseIntFromBody("not json at all", "expected_pre_window"))
  }

  @Test
  fun `returns null when details is not an object`() {
    val body = """{"details":"just a string"}"""
    assertNull(service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `parses zero value`() {
    val body = """{"details":{"expected_pre_window":0}}"""
    assertEquals(0, service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `parses negative value`() {
    val body = """{"details":{"expected_pre_window":-1}}"""
    assertEquals(-1, service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `parses large value`() {
    val body = """{"details":{"expected_pre_window":999999}}"""
    assertEquals(999999, service.parseIntFromBody(body, "expected_pre_window"))
  }

  @Test
  fun `parses expected columns from details`() {
    val body =
      """{"code":"RECONFIGURE_REQUIRED","details":{"expected_columns":["yaw","pitch","x"]}}"""
    assertEquals(
      listOf("yaw", "pitch", "x"),
      service.parseStringListFromBody(body, "expected_columns"),
    )
  }

  @Test
  fun `skips blank and non-textual column entries`() {
    val body = """{"details":{"expected_columns":["yaw","",7,null,"pitch"]}}"""
    assertEquals(listOf("yaw", "pitch"), service.parseStringListFromBody(body, "expected_columns"))
  }

  @Test
  fun `returns null when expected columns is empty or not an array`() {
    assertNull(
      service.parseStringListFromBody("""{"details":{"expected_columns":[]}}""", "expected_columns")
    )
    assertNull(
      service.parseStringListFromBody(
        """{"details":{"expected_columns":"yaw"}}""",
        "expected_columns",
      )
    )
    assertNull(service.parseStringListFromBody("not json", "expected_columns"))
  }
}
