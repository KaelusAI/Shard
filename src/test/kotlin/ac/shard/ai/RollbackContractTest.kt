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
import ac.shard.server.ShardError
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RollbackContractTest {

  @Test
  fun `a model that dropped its labels can say so and be believed`() {
    val negotiated =
      negotiated(
        """{"error":{"code":"RECONFIGURE_REQUIRED","status":422,"details":{
          "expected_labels":[],"expected_legit_labels":[],"expected_label_titles":{},
          "expected_label_thresholds":{},"expected_label_mode":"","expected_model_title":""}}}"""
      )

    assertEquals(emptyList(), negotiated.newLabels, "this is the whole rollback path")
    assertEquals(emptyList(), negotiated.newLegitLabels)
    assertEquals(emptyMap(), negotiated.newLabelNames)
    assertEquals(emptyMap(), negotiated.newLabelThresholds)
    assertEquals("", negotiated.newLabelMode)
    assertEquals(
      "",
      negotiated.newModelTitle,
      "a blank title falls back to the model name, so the operator stops reading the old one",
    )
    assertTrue(
      negotiated.hasNewParams,
      "an answer that clears five fields has to count as a reconfiguration, not as an empty one",
    )
  }

  @Test
  fun `the model name is negotiable, or the fingerprint can never converge`() {
    val negotiated =
      negotiated(
        """{"error":{"code":"RECONFIGURE_REQUIRED","status":422,"details":{
          "expected_model":"model_one","expected_model_title":"Model One"}}}"""
      )

    assertEquals("model_one", negotiated.newModel, "the name goes into the fingerprint")
    assertEquals(
      "Model One",
      negotiated.newModelTitle,
      "the title is cosmetic and stays out of the fingerprint, so the two cannot be one field",
    )
  }

  @Test
  fun `a set that only filtered down to nothing is garbage, not an instruction`() {
    val negotiated =
      negotiated(
        """{"error":{"code":"RECONFIGURE_REQUIRED","status":422,"details":{
          "expected_labels":["  ",""],"expected_step":32}}}"""
      )

    assertNull(
      negotiated.newLabels,
      "blanks are a broken answer, and taking them for a clear would wipe a live label set",
    )
    assertEquals(32, negotiated.newStep)
  }

  @Test
  fun `an empty column set is never an instruction, because a model without columns is nothing`() {
    val negotiated =
      negotiated(
        """{"error":{"code":"RECONFIGURE_REQUIRED","status":422,"details":{
          "expected_columns":[],"expected_step":32}}}"""
      )

    assertNull(negotiated.newColumns)
    assertEquals(32, negotiated.newStep)
  }

  private fun negotiated(body: String): AiServiceException {
    val cause = ShardError.parse(HTTP_UNPROCESSABLE, body)
    val transport =
      object : AiTransport {
        override fun send(payload: ByteArray): CompletableFuture<String> =
          CompletableFuture.failedFuture(cause)
      }
    val provider = mockk<AIServerProvider>()
    every { provider.get() } returns transport
    val thrown =
      runCatching {
          DefaultAiService(provider, JacksonAiResponseParser()).request(byteArrayOf(1)).join()
        }
        .exceptionOrNull()
        .let { (it as? CompletionException)?.cause ?: error("the request was expected to fail") }
    return assertIs<AiServiceException>(thrown)
  }

  private companion object {
    const val HTTP_UNPROCESSABLE = 422
  }
}
