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

import java.util.concurrent.CompletableFuture

class RetryingAiTransport(
  private val delegate: AiTransport,
  private val retryExecutor: RetryExecutor,
) : AiTransport {
  override fun send(payload: ByteArray): CompletableFuture<String> {
    return retryExecutor.execute(
      operation = { delegate.send(payload) },
      shouldRetry = InferenceRetryPolicy::shouldRetry,
    )
  }
}

class RetryingAiBatchTransport(
  private val delegate: AiBatchTransport,
  private val retryExecutor: RetryExecutor,
) : AiBatchTransport {
  override fun sendBatch(items: List<ByteArray>): CompletableFuture<String> {
    return retryExecutor.execute(
      operation = { delegate.sendBatch(items) },
      shouldRetry = InferenceRetryPolicy::shouldRetry,
    )
  }
}
