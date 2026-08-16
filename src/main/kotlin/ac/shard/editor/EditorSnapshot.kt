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
@file:Suppress("ReturnCount")

package ac.shard.editor

import ac.shard.config.yaml.YamlPatcher
import java.io.File
import java.security.MessageDigest
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import ru.vyarus.yaml.updater.parse.comments.model.CmtTree

data class SnapshotField(val path: String, val value: String)

data class FileSnapshot(val name: String, val baseline: String, val fields: List<SnapshotField>)

data class EditorSnapshot(
  val files: List<FileSnapshot>,
  val disabledRegions: Map<String, List<String>>,
  val punishments: Map<String, Map<String, List<String>>>,
  val mitigations: Map<String, Any?>? = null,
)

internal class EditorSnapshotBuilder(private val dataFolder: File) {

  fun build(names: Collection<String> = EDITABLE_FILES): EditorSnapshot =
    EditorSnapshot(names.mapNotNull(::snapshot), regions(), punishmentGroups(), mitigations())

  private fun mitigations(): Map<String, Any?>? {
    val file = File(dataFolder, MITIGATIONS)
    if (!file.isFile) return null
    val root =
      runCatching { YamlConfigurationLoader.builder().path(file.toPath()).build().load() }
        .getOrNull() ?: return null
    return MITIGATION_SECTIONS.mapNotNull { section ->
        val node = root.node(section)
        if (node.virtual()) null else section to plain(node)
      }
      .toMap()
  }

  private fun plain(node: ConfigurationNode): Any? =
    when {
      node.isMap -> node.childrenMap().entries.associate { it.key.toString() to plain(it.value) }
      node.isList -> node.childrenList().map { plain(it) }
      else -> node.rawScalar()
    }

  private fun punishmentGroups(): Map<String, Map<String, List<String>>> {
    val tree = treeOf("punishments.yml") ?: return emptyMap()
    return YamlPatcher.childKeys(tree, "Punishments").associateWith { group ->
      YamlPatcher.readStringListMap(tree, "Punishments/$group/actions").orEmpty()
    }
  }

  private fun treeOf(name: String): CmtTree? {
    val file = File(dataFolder, name)
    return if (!file.isFile) null else runCatching { YamlPatcher.read(file) }.getOrNull()
  }

  private fun regions(): Map<String, List<String>> =
    treeOf("config.yml")?.let { YamlPatcher.readStringListMap(it, REGIONS_PATH) }.orEmpty()

  private fun snapshot(name: String): FileSnapshot? {
    val file = File(dataFolder, name)
    val tree = if (!file.isFile) null else runCatching { YamlPatcher.read(file) }.getOrNull()
    return if (tree == null) {
      null
    } else {
      val fields =
        EditorSchema.editablePaths(name).sorted().mapNotNull { path ->
          YamlPatcher.readScalar(tree, path)?.let { SnapshotField(path, it) }
        }
      FileSnapshot(name, baseline(file), fields)
    }
  }

  private fun baseline(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
  }

  companion object {
    const val MITIGATIONS = "mitigations.yml"
    val EDITABLE_FILES = listOf("config.yml", "monitor.yml", MITIGATIONS)
    val MITIGATION_SECTIONS = listOf("enabled", "skip", "score", "rules")
    const val REGIONS_PATH = "ai/worldguard/disabled-regions"
  }
}
