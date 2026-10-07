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
package ac.shard.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodySubscribers
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.zip.GZIPInputStream

class BodyTooLargeException(limit: Int) : IOException("response is larger than $limit bytes")

object HttpBodies {
  const val PANEL_LIMIT = 1024 * 1024
  const val INFERENCE_LIMIT = 8 * 1024 * 1024

  fun bytes(limit: Int): HttpResponse.BodyHandler<ByteArray> = HttpResponse.BodyHandler {
    Capped(limit)
  }

  fun text(limit: Int): HttpResponse.BodyHandler<String> = HttpResponse.BodyHandler {
    BodySubscribers.mapping(Capped(limit)) { String(it, Charsets.UTF_8) }
  }

  fun gunzip(raw: ByteArray, limit: Int): ByteArray =
    GZIPInputStream(raw.inputStream()).use { read(it, limit) }

  private fun read(stream: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    while (true) {
      val count = stream.read(chunk)
      if (count < 0) return out.toByteArray()
      if (out.size() + count > limit) throw BodyTooLargeException(limit)
      out.write(chunk, 0, count)
    }
  }

  private class Capped(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val out = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
      this.subscription = subscription
      subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
      if (result.isDone) return
      for (buffer in item) {
        if (out.size() + buffer.remaining() > limit) {
          subscription?.cancel()
          result.completeExceptionally(BodyTooLargeException(limit))
          return
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        out.write(bytes)
      }
    }

    override fun onError(throwable: Throwable) {
      result.completeExceptionally(throwable)
    }

    override fun onComplete() {
      result.complete(out.toByteArray())
    }
  }

  private const val CHUNK_BYTES = 8192
}
