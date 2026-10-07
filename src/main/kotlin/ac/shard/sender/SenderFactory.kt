/*
 * This file is part of GrimAC - https://github.com/GrimAnticheat/Grim
 * Copyright (C) 2021-2026 GrimAC, DefineOutside and contributors
 *
 * GrimAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.sender

import ac.shard.utils.MiniText
import java.util.UUID
import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.SenderMapper

class SenderFactory(private val adventure: BukkitAudiences) : SenderMapper<CommandSender, Sender> {
  override fun map(base: CommandSender): Sender {
    return if (base is Player) {
      PlayerSender(base, adventure)
    } else {
      ConsoleSender(base, adventure)
    }
  }

  override fun reverse(mapped: Sender): CommandSender {
    return mapped.nativeSender
  }

  private class PlayerSender(
    private val bukkitPlayer: Player,
    private val adventure: BukkitAudiences,
  ) : Sender {
    override val name: String
      get() = bukkitPlayer.name

    override val uniqueId: UUID
      get() = bukkitPlayer.uniqueId

    override fun sendMessage(message: String) {
      adventure.player(bukkitPlayer).sendMessage(MiniText.deserializeRaw(message))
    }

    override fun sendMessage(message: Component) {
      adventure.player(bukkitPlayer).sendMessage(message)
    }

    override fun hasPermission(permission: String): Boolean = bukkitPlayer.hasPermission(permission)

    override val isConsole: Boolean
      get() = false

    override val isPlayer: Boolean
      get() = true

    override val isTrustedConsole: Boolean
      get() = false

    override val nativeSender: CommandSender
      get() = bukkitPlayer

    override val player: Player
      get() = bukkitPlayer
  }

  private class ConsoleSender(
    private val sender: CommandSender,
    private val adventure: BukkitAudiences,
  ) : Sender {
    override val name: String
      get() = Sender.CONSOLE_NAME

    override val uniqueId: UUID
      get() = Sender.CONSOLE_UUID

    override fun sendMessage(message: String) {
      adventure.sender(sender).sendMessage(MiniText.deserializeRaw(message))
    }

    override fun sendMessage(message: Component) {
      adventure.sender(sender).sendMessage(message)
    }

    override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

    override val isConsole: Boolean
      get() = true

    override val isPlayer: Boolean
      get() = false

    override val isTrustedConsole: Boolean
      get() = sender is ConsoleCommandSender || sender is RemoteConsoleCommandSender

    override val nativeSender: CommandSender
      get() = sender

    override val player: Player? = null
  }
}
