/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
package ac.shard.checks

import ac.shard.api.event.CheckFlagEvent
import ac.shard.player.ShardPlayer

abstract class AbstractCheck(protected val shardPlayer: ShardPlayer) : ICheck {
  private val checkNameInternal: String
  val configName: String

  override val checkName: String
    get() = checkNameInternal

  private val legacyCheckNamesInternal: List<String>

  override val legacyCheckNames: List<String>
    get() = legacyCheckNamesInternal

  init {
    val data = javaClass.getAnnotation(CheckData::class.java)
    checkNameInternal = data.name
    configName = if (data.configName == "DEFAULT") data.name else data.configName
    legacyCheckNamesInternal = data.legacyNames.toList()
  }

  protected fun flag(debug: String) {
    val event =
      CheckFlagEvent(shardPlayer.uuid, shardPlayer.player.name, checkName, configName, debug)
    shardPlayer.eventBus.post(event)
    if (event.cancelled) {
      return
    }
    shardPlayer.punishmentManager.handleFlag(this, debug)
  }
}
