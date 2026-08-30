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

import ac.shard.Shard
import ac.shard.ai.label.LabelThresholds
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class ModelStore(private val plugin: Shard) {
  private val file = File(plugin.dataFolder, FILE_NAME)

  fun readPreWindow(): Int? = readInt("pre-window")

  fun readPostWindow(): Int? = readInt("post-window")

  fun readStep(): Int? = readInt("step")

  fun readModel(): String? {
    val node = loadCurrentFormat() ?: return null
    return node.node("model").getString("").takeIf { it.isNotBlank() }
  }

  fun readModelTitle(): String = loadCurrentFormat()?.node("model-title")?.getString("").orEmpty()

  fun readLabelMode(): String = loadCurrentFormat()?.node("label-mode")?.getString("").orEmpty()

  fun readLabels(): List<String>? = readStringList("labels")

  fun readColumns(): List<String>? = readStringList("columns")

  fun readLabelNames(): Map<String, String> =
    loadCurrentFormat()
      ?.node("label-titles")
      ?.takeIf { it.isMap }
      ?.childrenMap()
      ?.mapNotNull { (key, child) -> child.string?.let { key.toString() to it } }
      ?.toMap()
      .orEmpty()

  fun readLegitLabels(): List<String> = readStringList("legit-labels").orEmpty()

  fun readLabelThresholds(): Map<String, LabelThresholds> =
    loadCurrentFormat()
      ?.node("label-thresholds")
      ?.takeIf { it.isMap }
      ?.childrenMap()
      ?.mapNotNull { (key, child) ->
        LabelThresholds.parse(
            child.node("cheat").getDouble(Double.NaN),
            child.node("legit").getDouble(Double.NaN),
          )
          ?.let { key.toString() to it }
      }
      ?.toMap()
      .orEmpty()

  private fun readStringList(key: String): List<String>? =
    loadCurrentFormat()
      ?.node(key)
      ?.takeIf { it.isList }
      ?.childrenList()
      ?.mapNotNull { it.getString("").takeIf { name -> name.isNotBlank() } }
      ?.takeIf { it.isNotEmpty() }

  private fun readInt(key: String): Int? {
    val node = loadCurrentFormat() ?: return null
    return node.node(key).getInt(0).takeIf { it >= 1 }
  }

  private fun loadCurrentFormat(): org.spongepowered.configurate.ConfigurationNode? {
    if (!file.exists()) return null
    return runCatching { YamlConfigurationLoader.builder().path(file.toPath()).build().load() }
      .getOrNull()
      ?.takeUnless { it.node("pre-window").virtual() }
  }

  @Synchronized
  @Suppress("LongParameterList")
  fun write(
    preWindow: Int,
    postWindow: Int,
    step: Int,
    model: String?,
    columns: List<String>? = null,
    labels: List<String>? = null,
    labelNames: Map<String, String>? = null,
    legitLabels: Set<String>? = null,
    modelTitle: String? = null,
    labelMode: String? = null,
    labelThresholds: Map<String, LabelThresholds>? = null,
  ) {
    val tmp = File(plugin.dataFolder, "$FILE_NAME.tmp")
    try {
      if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
      Files.deleteIfExists(tmp.toPath())
      val loader = YamlConfigurationLoader.builder().path(tmp.toPath()).build()
      val node = loader.createNode()
      node
        .node("_note")
        .set("Managed by the inference server. Do not edit; tune ai.* in config.yml.")
      node.node("pre-window").set(preWindow)
      node.node("post-window").set(postWindow)
      node.node("step").set(step)
      if (model != null) node.node("model").set(model)
      if (!modelTitle.isNullOrBlank()) node.node("model-title").set(modelTitle)
      if (!labelMode.isNullOrBlank()) node.node("label-mode").set(labelMode)
      if (columns != null) node.node("columns").set(columns)
      if (labels != null) node.node("labels").set(labels)
      if (!legitLabels.isNullOrEmpty()) node.node("legit-labels").set(legitLabels.toList())
      if (!labelNames.isNullOrEmpty()) {
        val titles = node.node("label-titles")
        for ((key, value) in labelNames) titles.node(key).set(value)
      }
      if (!labelThresholds.isNullOrEmpty()) {
        val thresholds = node.node("label-thresholds")
        for ((key, value) in labelThresholds) {
          thresholds.node(key, "cheat").set(value.cheat)
          thresholds.node(key, "legit").set(value.legit)
        }
      }
      loader.save(node)
      moveIntoPlace(tmp)
    } catch (e: Exception) {
      plugin.logger.warning("[Config] Failed to write $FILE_NAME: ${e.message}")
    } finally {
      tmp.delete()
    }
  }

  private fun moveIntoPlace(tmp: File) {
    try {
      Files.move(
        tmp.toPath(),
        file.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private companion object {
    const val FILE_NAME = "model.yml"
  }
}
