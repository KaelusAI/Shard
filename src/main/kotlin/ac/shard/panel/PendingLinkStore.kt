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

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

data class PendingLink(
  val deviceCode: String,
  val userCode: String,
  val url: String,
  val deadlineEpochSec: Long,
  val intervalSeconds: Long,
)

internal class PendingLinkStore(dataFolder: File) {

  private val file = File(dataFolder, "linking.yml")

  fun read(): PendingLink? {
    val node =
      if (!file.isFile) {
        null
      } else {
        runCatching { YamlConfigurationLoader.builder().path(file.toPath()).build().load() }
          .getOrNull()
      }
    val deviceCode = node?.node("device-code")?.getString("").orEmpty()
    return if (node == null || deviceCode.isBlank()) {
      null
    } else {
      PendingLink(
        deviceCode = deviceCode,
        userCode = node.node("user-code").getString(""),
        url = node.node("url").getString(""),
        deadlineEpochSec = node.node("deadline").getLong(0L),
        intervalSeconds = node.node("interval").getLong(1L),
      )
    }
  }

  fun write(pending: PendingLink) {
    runCatching {
      file.parentFile?.mkdirs()
      val loader = YamlConfigurationLoader.builder().path(file.toPath()).build()
      val node = loader.createNode()
      node.node("device-code").set(pending.deviceCode)
      node.node("user-code").set(pending.userCode)
      node.node("url").set(pending.url)
      node.node("deadline").set(pending.deadlineEpochSec)
      node.node("interval").set(pending.intervalSeconds)
      loader.save(node)
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
  }

  fun clear() {
    runCatching { Files.deleteIfExists(file.toPath()) }
  }
}
