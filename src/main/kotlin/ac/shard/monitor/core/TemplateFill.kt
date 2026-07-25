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
package ac.shard.monitor.core

internal fun fillTemplate(template: String, values: Map<String, String>): String {
  var result = template
  for ((key, value) in values) {
    result = result.replace("{$key}", value)
  }
  return result
}

internal fun fillTemplate(template: String, resolve: (String) -> String?): String {
  if (!template.contains('{')) {
    return template
  }
  val out = StringBuilder(template.length)
  var index = 0
  while (index < template.length) {
    val open = template.indexOf('{', index)
    val close = if (open < 0) -1 else template.indexOf('}', open + 1)
    if (close < 0) {
      out.append(template, index, template.length)
      break
    }
    out.append(template, index, open)
    val key = template.substring(open + 1, close)
    out.append(resolve(key) ?: template.substring(open, close + 1))
    index = close + 1
  }
  return out.toString()
}
