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
package ac.shard.panel

import ac.shard.Shard
import ac.shard.config.ConfigManager
import ac.shard.connect.CredentialsStore
import ac.shard.connect.isSecurePanelUrl
import ac.shard.connect.readCapped
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPOutputStream

data class PanelReply(val status: Int, val node: JsonNode)

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val REQUEST_TIMEOUT_SECONDS = 15L
private const val GZIP_ABOVE_BYTES = 4096

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)

internal class PanelClient(
  private val plugin: Shard,
  private val configManager: ConfigManager,
  private val credentialsStore: CredentialsStore,
) {
  private val mapper = ObjectMapper()
  private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

  fun post(path: String, body: Map<String, Any?>): PanelReply? {
    val base = configManager.connectPanelUrl.trim().trimEnd('/')
    val key = credentialsStore.read()?.secretKey
    return if (!usable(base) || key.isNullOrBlank()) null else send(base, path, body, key)
  }

  fun verifiedPayload(node: JsonNode): String? {
    val payload = node.path("payload").asText("")
    return payload.ifBlank { null }
  }

  private fun send(base: String, path: String, body: Map<String, Any?>, key: String): PanelReply? =
    runCatching {
        val raw = mapper.writeValueAsString(body).toByteArray(Charsets.UTF_8)
        val compress = raw.size > GZIP_ABOVE_BYTES
        val builder =
          HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Shard/" + plugin.description.version)
            .header("X-API-Key", key)
            .timeout(REQUEST_TIMEOUT)
        if (compress) {
          builder.header("Content-Encoding", "gzip")
        }
        val request =
          builder
            .POST(HttpRequest.BodyPublishers.ofByteArray(if (compress) gzip(raw) else raw))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val text = response.body().use(::readCapped)
        val node =
          if (text.isBlank()) mapper.createObjectNode()
          else runCatching { mapper.readTree(text) }.getOrElse { mapper.createObjectNode() }
        PanelReply(response.statusCode(), node)
      }
      .onFailure { plugin.logger.fine("[Panel] $path failed: ${it.message}") }
      .getOrNull()

  private fun gzip(raw: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(raw) }
    return out.toByteArray()
  }

  private fun usable(base: String): Boolean =
    when {
      base.isBlank() -> false
      !isSecurePanelUrl(base) -> {
        plugin.logger.warning("[Panel] Refusing to contact the panel over an insecure URL: $base")
        false
      }
      else -> true
    }
}
