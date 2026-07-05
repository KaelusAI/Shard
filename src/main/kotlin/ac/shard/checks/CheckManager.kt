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

import ac.shard.checks.type.PacketCheck
import ac.shard.player.ShardPlayer
import com.github.retrooper.packetevents.event.PacketReceiveEvent

class CheckManager(private val player: ShardPlayer, checkFactories: Set<CheckFactory>) {
  private val packetChecks = ArrayList<PacketCheck>()
  private val checks = HashMap<Class<out ICheck>, ICheck>()

  init {
    for (factory in checkFactories) {
      registerCheck(factory.create(player))
    }
  }

  private fun checksDisabled(): Boolean =
    player.exemptManager.isDisabled(player.player) || player.isBedrockExempt

  private fun registerCheck(check: ICheck) {
    checks[check.javaClass] = check

    if (check is PacketCheck) {
      packetChecks.add(check)
    }
  }

  fun reloadChecks() {
    for (check in checks.values) {
      if (check is Reloadable) {
        check.reload()
      }
    }
  }

  fun onPacketReceive(event: PacketReceiveEvent) {
    if (checksDisabled()) {
      return
    }
    for (i in packetChecks.indices) {
      packetChecks[i].onPacketReceive(event)
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun <T : ICheck> getCheck(clazz: Class<T>): T? {
    return checks[clazz] as T?
  }

  fun getAllChecks(): Collection<ICheck> = checks.values

  fun interface Factory {
    fun create(player: ShardPlayer): CheckManager
  }
}
