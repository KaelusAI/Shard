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
package ac.shard.command

import ac.shard.sender.Sender
import org.incendo.cloud.CommandManager
import org.incendo.cloud.kotlin.MutableCommandBuilder
import org.incendo.cloud.kotlin.extension.buildAndRegister

interface ShardCommand {
  fun register(manager: CommandManager<Sender>)

  fun start() = Unit
}

private val ROOT_ALIASES = arrayOf("shardac", "sloth", "slothac")

fun CommandManager<Sender>.shardCommand(configure: MutableCommandBuilder<Sender>.() -> Unit) {
  buildAndRegister("shard", aliases = ROOT_ALIASES, lambda = configure)
}
