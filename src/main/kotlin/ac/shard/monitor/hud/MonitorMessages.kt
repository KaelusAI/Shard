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
package ac.shard.monitor.hud

import ac.shard.config.LocaleManager
import ac.shard.utils.Message

internal fun rawMessageFor(localeManager: LocaleManager, key: Message, targetName: String): String =
  localeManager
    .getRawMessage(key)
    .replace(PREFIX_TAG, localeManager.getRawMessage(Message.PREFIX))
    .replace(PLAYER_TAG, targetName)

internal fun targetTexts(localeManager: LocaleManager, targetName: String): UnavailableTexts =
  UnavailableTexts(
    noData = rawMessageFor(localeManager, Message.MONITOR_NO_DATA, targetName),
    noAiCheck = rawMessageFor(localeManager, Message.MONITOR_NO_AICHECK, targetName),
  )

private const val PREFIX_TAG = "<prefix>"
private const val PLAYER_TAG = "<player>"
