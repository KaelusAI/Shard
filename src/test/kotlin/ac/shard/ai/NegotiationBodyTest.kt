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

import ac.shard.server.AIServer
import ac.shard.server.AIServerProvider
import ac.shard.server.ShardError
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class NegotiationBodyTest {

  private val details =
    """
    "expected_pre_window":32,"expected_post_window":32,"expected_step":32,
    "expected_labels":["aim","trigger"],"expected_label_titles":{"aim":"Aimbot"},
    "expected_legit_labels":["clean"],"expected_label_mode":"multilabel",
    "expected_label_thresholds":{"aim":{"cheat":0.9,"legit":0.1}},
    "expected_model_title":"Shard 2"
    """
      .trimIndent()
      .replace("\n", "")

  @Test
  fun `the wrapped body carries every negotiated field into the plugin`() {
    val negotiated =
      negotiated(
        """{"error":{"code":"RECONFIGURE_REQUIRED","message":"window does not match",""" +
          """"status":422,"retryable":false,"details":{$details}}}"""
      )

    assertEquals(32, negotiated.newPreWindow)
    assertEquals(listOf("aim", "trigger"), negotiated.newLabels)
    assertEquals(mapOf("aim" to "Aimbot"), negotiated.newLabelNames)
    assertEquals(listOf("clean"), negotiated.newLegitLabels)
    assertEquals("multilabel", negotiated.newLabelMode)
    assertEquals(mapOf("cheat" to 0.9, "legit" to 0.1), negotiated.newLabelThresholds?.get("aim"))
    assertEquals("Shard 2", negotiated.newModelTitle)
  }

  @Test
  fun `a body that kept details but lost the envelope reconfigures halfway`() {
    val negotiated =
      negotiated("""{"code":"RECONFIGURE_REQUIRED","status":422,"details":{$details}}""")

    assertEquals(
      listOf("aim", "trigger"),
      negotiated.newLabels,
      "labels and window are scavenged out of the raw body, so this looks like it worked",
    )
    assertEquals(32, negotiated.newPreWindow)
    assertNull(
      negotiated.newLabelMode,
      "mode is read from the parsed details and nowhere else, so the plugin keeps the old one, " +
        "the fingerprint can never match again, and every window is refused from here on",
    )
    assertNull(negotiated.newLabelThresholds, "thresholds are lost the same way")
    assertNull(negotiated.newLabelNames)
    assertNull(negotiated.newModelTitle)
  }

  @Test
  fun `a body flattened all the way is not a negotiation at all`() {
    val thrown = thrown("""{"code":"RECONFIGURE_REQUIRED","status":422,$details}""")

    assertIs<AIServer.RequestException>(
      thrown,
      "with nothing under details there is nothing to reconfigure to, so the window just fails",
    )
  }

  @Test
  fun `the envelope is what decides whether details are parsed at all`() {
    val wrapped =
      ShardError.parse(
        HTTP_UNPROCESSABLE,
        """{"error":{"code":"RECONFIGURE_REQUIRED","status":422,"details":{$details}}}""",
      )
    val bare =
      ShardError.parse(HTTP_UNPROCESSABLE, """{"code":"RECONFIGURE_REQUIRED","status":422}""")

    assertEquals(AIServer.ResponseCode.RECONFIGURE_REQUIRED, wrapped.code)
    assertEquals(
      AIServer.ResponseCode.RECONFIGURE_REQUIRED,
      bare.code,
      "422 alone already names the code, which is why an unwrapped body is not obviously broken",
    )
    assertEquals(listOf("aim", "trigger"), wrapped.details?.get("expected_labels"))
    assertNull(bare.details, "no envelope, no details, and nothing in the log says so")
  }

  private fun negotiated(body: String): AiServiceException =
    assertIs<AiServiceException>(thrown(body))

  private fun thrown(body: String): Throwable {
    val cause = ShardError.parse(HTTP_UNPROCESSABLE, body)
    val transport =
      object : AiTransport {
        override fun send(payload: ByteArray): CompletableFuture<String> =
          CompletableFuture.failedFuture(cause)
      }
    val provider = mockk<AIServerProvider>()
    every { provider.get() } returns transport
    return runCatching {
        DefaultAiService(provider, JacksonAiResponseParser()).request(byteArrayOf(1)).join()
      }
      .exceptionOrNull()
      .let { (it as? CompletionException)?.cause ?: error("the request was expected to fail") }
  }

  private companion object {
    const val HTTP_UNPROCESSABLE = 422
  }
}
