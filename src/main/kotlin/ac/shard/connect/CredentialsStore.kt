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
package ac.shard.connect

import ac.shard.Shard
import ac.shard.utils.AtomicFiles
import java.io.File
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

data class Credentials(
  val secretKey: String,
  val serverId: String?,
  val serverName: String?,
  val allowlistedIp: String?,
  val inferenceUrl: String?,
)

@Suppress("TooGenericExceptionCaught")
class CredentialsStore(private val plugin: Shard) {
  private val file = File(plugin.dataFolder, FILE_NAME)

  private val instanceFile = File(plugin.dataFolder, INSTANCE_FILE)

  private val cachedInstanceId: String by lazy { readOrCreateInstanceId() }

  fun isLinked(): Boolean = file.exists()

  fun instanceId(): String = cachedInstanceId

  private fun readOrCreateInstanceId(): String {
    runCatching {
      if (instanceFile.exists()) {
        val node = YamlConfigurationLoader.builder().path(instanceFile.toPath()).build().load()
        node
          .node("id")
          .getString("")
          .trim()
          .takeIf { it.isNotBlank() }
          ?.let {
            return it
          }
      }
    }
    val id = java.util.UUID.randomUUID().toString()
    try {
      if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
      val loader = YamlConfigurationLoader.builder().path(instanceFile.toPath()).build()
      val node = loader.createNode()
      node
        .node("_note")
        .set("Unique id of THIS server install. Do NOT copy to another server - it would collide.")
      node.node("id").set(id)
      loader.save(node)
    } catch (e: Exception) {
      plugin.logger.warning("[Connect] Failed to persist instance id: ${e.message}")
    }
    return id
  }

  fun read(): Credentials? {
    if (!file.exists()) return null
    return try {
      val node = YamlConfigurationLoader.builder().path(file.toPath()).build().load()
      val key = node.node("secret-key").getString("")
      if (key.isBlank()) {
        null
      } else {
        Credentials(
          secretKey = key,
          serverId = node.node("server-id").getString("").ifBlank { null },
          serverName = node.node("server-name").getString("").ifBlank { null },
          allowlistedIp = node.node("allowlisted-ip").getString("").ifBlank { null },
          inferenceUrl = node.node("inference-url").getString("").ifBlank { null },
        )
      }
    } catch (e: Exception) {
      plugin.logger.warning("[Connect] Failed to read $FILE_NAME: ${e.message}")
      null
    }
  }

  fun write(credentials: Credentials) {
    try {
      AtomicFiles.replace(file.toPath(), ownerOnly = true) { tmp ->
        val loader = YamlConfigurationLoader.builder().path(tmp).build()
        val node = loader.createNode()
        node.node("_note").set("Managed by /shard connect. Do not edit by hand.")
        node.node("secret-key").set(credentials.secretKey)
        node.node("server-id").set(credentials.serverId)
        node.node("server-name").set(credentials.serverName)
        node.node("allowlisted-ip").set(credentials.allowlistedIp)
        node.node("inference-url").set(credentials.inferenceUrl)
        loader.save(node)
      }
    } catch (e: Exception) {
      plugin.logger.warning("[Connect] Failed to write $FILE_NAME: ${e.message}")
    }
  }

  fun clear(): Boolean = (!file.exists()) || file.delete()

  private companion object {
    const val FILE_NAME = "credentials.yml"
    const val INSTANCE_FILE = "instance.yml"
  }
}
