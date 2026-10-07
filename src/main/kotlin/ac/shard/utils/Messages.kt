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
package ac.shard.utils

import ac.shard.config.LocaleManager
import java.util.logging.Logger
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender

object MiniText {
  val miniMessage: MiniMessage = MiniMessage.miniMessage()

  fun escape(value: String): String = miniMessage.escapeTags(value)

  fun deserializeRaw(message: String): Component = miniMessage.deserialize(message)

  fun deserializeRaw(message: String, resolver: TagResolver): Component =
    miniMessage.deserialize(message, resolver)

  fun hoverTextTag(key: String, hoverText: Component): TagResolver =
    TagResolver.resolver(key, Tag.styling(HoverEvent.showText(hoverText)))

  fun clickCommandTag(key: String, command: String): TagResolver =
    TagResolver.resolver(key, Tag.styling(ClickEvent.runCommand(command)))

  fun clickUrlTag(key: String, url: String): TagResolver =
    TagResolver.resolver(key, Tag.styling(ClickEvent.openUrl(url)))

  fun suggestCommandTag(key: String, command: String): TagResolver =
    TagResolver.resolver(key, Tag.styling(ClickEvent.suggestCommand(command)))
}

class Messages(
  private val localeManager: LocaleManager,
  private val adventure: BukkitAudiences,
  private val logger: Logger,
) {
  private val miniMessage = MiniText.miniMessage

  fun format(message: String, vararg placeholders: String): Component {
    val processedMessage = message.replace("<prefix>", localeManager.getRawMessage(Message.PREFIX))
    val resolverBuilder = TagResolver.builder()
    if (placeholders.size % 2 != 0) {
      logger.warning("Invalid placeholders count for message: $message")
    } else {
      addPlaceholders(resolverBuilder, placeholders)
    }
    return miniMessage.deserialize(processedMessage, resolverBuilder.build())
  }

  private fun addPlaceholders(builder: TagResolver.Builder, placeholders: Array<out String>) {
    var i = 0
    while (i < placeholders.size) {
      val key = placeholders[i]
      val value = placeholders[i + 1]
      val safe = if (key in UNESCAPED_PLACEHOLDERS) value else miniMessage.escapeTags(value)
      builder.resolver(Placeholder.parsed(key, safe))
      i += 2
    }
  }

  @Suppress("SpreadOperator")
  fun sendMessage(sender: CommandSender, key: Message, vararg placeholders: String) {
    adventure.sender(sender).sendMessage(getMessage(key, *placeholders))
  }

  fun sendMessage(sender: CommandSender, component: Component) {
    adventure.sender(sender).sendMessage(component)
  }

  @Suppress("SpreadOperator")
  fun sendMessageList(sender: CommandSender, key: Message, vararg placeholders: String) {
    getMessageList(key, *placeholders).forEach { line ->
      adventure.sender(sender).sendMessage(line)
    }
  }

  fun rawMessage(key: Message): String = localeManager.getRawMessage(key)

  fun labelsLine(shown: String): String =
    if (shown.isBlank()) ""
    else rawMessage(Message.ALERTS_LABELS_LINE).replace("<labels>", MiniText.escape(shown))

  @Suppress("SpreadOperator")
  fun getMessage(key: Message, vararg placeholders: String): Component =
    format(localeManager.getRawMessage(key), *placeholders)

  fun getMessage(key: Message, resolver: TagResolver): Component {
    val rawMessage = localeManager.getRawMessage(key)
    val processedMessage =
      rawMessage.replace("<prefix>", localeManager.getRawMessage(Message.PREFIX))
    return miniMessage.deserialize(processedMessage, resolver)
  }

  @Suppress("SpreadOperator")
  fun getMessageList(key: Message, vararg placeholders: String): List<Component> =
    localeManager.getRawMessageList(key).map { line -> format(line, *placeholders) }

  private companion object {
    val UNESCAPED_PLACEHOLDERS = setOf("channels", "waiting", "server", "labels_line")
  }
}
