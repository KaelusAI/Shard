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
package ac.shard.api.impl

import ac.shard.api.Initiator
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

class PluginInitiator(private val plugin: Plugin) : Initiator {
  override fun kind(): Initiator.Kind = Initiator.Kind.PLUGIN

  override fun name(): String = plugin.name

  override fun plugin(): Plugin = plugin

  override fun equals(other: Any?): Boolean = other is PluginInitiator && other.plugin === plugin

  override fun hashCode(): Int = System.identityHashCode(plugin)

  override fun toString(): String = "plugin:${plugin.name}"
}

class CommandInitiator(private val name: String) : Initiator {
  override fun kind(): Initiator.Kind = Initiator.Kind.COMMAND

  override fun name(): String = name

  override fun plugin(): Plugin? = null

  override fun equals(other: Any?): Boolean = other is CommandInitiator && other.name == name

  override fun hashCode(): Int = name.hashCode()

  override fun toString(): String = "command:$name"

  companion object {
    fun of(sender: CommandSender): CommandInitiator = CommandInitiator(sender.name)
  }
}

object ShardInitiator : Initiator {
  override fun kind(): Initiator.Kind = Initiator.Kind.SHARD

  override fun name(): String = "Shard"

  override fun plugin(): Plugin? = null

  override fun toString(): String = "shard"
}
