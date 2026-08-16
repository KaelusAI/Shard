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
package ac.shard.config

import ac.shard.config.yaml.YamlPatcher
import java.io.File
import ru.vyarus.yaml.updater.parse.comments.CommentsWriter

private val RENAMES =
  listOf(
    "cross-server/server-name" to "name",
    "cross-server/alerts/regular" to "alerts",
    "cross-server/alerts" to "share",
    "cross-server" to "network",
  )

internal fun renameCrossServerToNetwork(file: File): Boolean {
  val tree = if (file.exists()) runCatching { YamlPatcher.read(file) }.getOrNull() else null
  val worthMoving = tree?.find("cross-server") != null && tree.find("network") == null
  return if (!worthMoving) {
    false
  } else {
    val targets = RENAMES.mapNotNull { (path, name) -> tree.find(path)?.let { it to name } }
    targets.forEach { (node, name) -> node.key = name }
    runCatching { CommentsWriter.write(tree, file) }.isSuccess
  }
}
