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
import ac.shard.utils.Message
import java.io.File
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.serialize.SerializationException
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

class LocaleManager(private val plugin: Shard, private val configManager: ConfigManager) {
  private var messagesConfig: ConfigView = ConfigView(CommentedConfigurationNode.root())
  private var defaultMessages: ConfigView? = null
  private var defaultLocaleMessages: ConfigView? = null

  init {
    reload()
  }

  fun reload() {
    val locale = configManager.config.getString("locale", "en")
    val messagesDir = File(plugin.dataFolder, "messages")
    if (!messagesDir.exists()) {
      messagesDir.mkdirs()
    }

    saveDefaultLocale("en")
    if (!"en".equals(locale, ignoreCase = true)) {
      saveDefaultLocale(locale)
    }

    var messagesFile = File(messagesDir, "messages_$locale.yml")
    if (!messagesFile.exists()) {
      plugin.logger.warning("Locale $locale not found.")
      messagesFile = File(messagesDir, "messages_en.yml")
    }

    messagesConfig = loadMessages(messagesFile)
    defaultLocaleMessages =
      if ("en".equals(locale, ignoreCase = true)) {
        null
      } else {
        loadDefaultsFromJar("messages/messages_$locale.yml")
      }
    defaultMessages = loadDefaultsFromJar("messages/messages_en.yml")
  }

  private fun saveDefaultLocale(locale: String) {
    val dir = File(plugin.dataFolder, "messages")
    val file = File(dir, "messages_$locale.yml")
    val resourcePath = "messages/messages_$locale.yml"
    if (!file.exists() && plugin.getResource(resourcePath) != null) {
      plugin.saveResource(resourcePath, false)
    }
  }

  private fun loadDefaultsFromJar(resourcePath: String): ConfigView? {
    val resource = plugin.getResource(resourcePath) ?: return null
    return try {
      val node =
        YamlConfigurationLoader.builder().source { resource.bufferedReader() }.build().load()
      ConfigView(node)
    } catch (e: java.io.IOException) {
      plugin.logger.warning("Failed to load default locale from JAR ($resourcePath): ${e.message}")
      null
    }
  }

  fun getRawMessage(key: Message): String {
    val node = resolveNode(key)
    return node.getString("Missing message: ${key.path}")
  }

  fun getRawMessageList(key: Message): List<String> {
    val node = resolveNode(key)
    return try {
      node.getList(String::class.java) ?: emptyList()
    } catch (_: SerializationException) {
      emptyList()
    }
  }

  private fun loadMessages(file: File): ConfigView {
    return try {
      val loader = YamlConfigurationLoader.builder().path(file.toPath()).build()
      ConfigView(loader.load())
    } catch (_: Exception) {
      plugin.logger.warning("Failed to load locale file ${file.name}")
      ConfigView(CommentedConfigurationNode.root())
    }
  }

  private fun resolveNode(key: Message): ConfigurationNode {
    val path = key.path
    val node = messagesConfig.node(path)
    if (!node.empty()) {
      return node
    }
    return firstPresent(path, defaultLocaleMessages, defaultMessages) ?: node
  }

  private fun firstPresent(path: String, vararg sources: ConfigView?): ConfigurationNode? {
    for (source in sources) {
      val candidate = source?.node(path) ?: continue
      if (!candidate.empty()) {
        return candidate
      }
    }
    return null
  }
}
