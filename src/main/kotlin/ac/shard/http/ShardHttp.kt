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

import ac.shard.Shard
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

class ShardHttp(plugin: Shard) : AutoCloseable {
  val client: HttpClient =
    HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .connectTimeout(CONNECT_TIMEOUT)
      .build()

  // pluginMeta is newer than the Paper versions Shard supports.
  @Suppress("DEPRECATION") val pluginVersion: String = plugin.description.version

  val userAgent: String = "Shard/$pluginVersion"

  fun keyed(uri: URI, key: String): HttpRequest.Builder {
    require(isSecureEndpoint(uri)) { "refusing to send the API key over plain http to $uri" }
    return HttpRequest.newBuilder(uri).header("X-API-Key", key).header("User-Agent", userAgent)
  }

  override fun close() {
    (client as? AutoCloseable)?.close()
  }

  companion object {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
  }
}
