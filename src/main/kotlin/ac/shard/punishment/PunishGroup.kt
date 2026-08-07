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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.punishment

import ac.shard.checks.ICheck
import java.util.Locale
import java.util.NavigableMap

class PunishGroup(
  val groupName: String,
  checkNames: List<String>,
  labels: List<String>,
  val actions: NavigableMap<Int, List<String>>,
) {
  val associatedCheckNames: Set<String> = checkNames.map { it.lowercase(Locale.ROOT) }.toSet()
  val associatedLabels: Set<String> = labels.map { it.lowercase(Locale.ROOT) }.toSet()

  fun isCheckAssociated(check: ICheck): Boolean {
    val names = (listOf(check.checkName) + check.legacyCheckNames).map { it.lowercase(Locale.ROOT) }
    return associatedCheckNames.any { filter -> names.any { it.contains(filter) } }
  }

  fun matches(check: ICheck, labels: Set<String>): Boolean =
    isCheckAssociated(check) &&
      (associatedLabels.isEmpty() || labels.any { it.lowercase(Locale.ROOT) in associatedLabels })
}
