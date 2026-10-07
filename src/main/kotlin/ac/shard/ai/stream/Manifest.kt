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
package ac.shard.ai.stream

import ac.shard.data.TickSchema
import ac.shard.http.Json
import java.util.Locale
import java.util.zip.CRC32

object Manifest {
  const val REV = 1

  val json: String =
    Json.mapper.writeValueAsString(
      linkedMapOf(
        "columns" to
          TickSchema.fields.map { listOf(it.name, it.wireType.name.lowercase(Locale.ROOT)) },
        "rev" to REV,
      )
    )

  val bytes: ByteArray = json.toByteArray(Charsets.UTF_8)

  val hash: String = "%08x".format(CRC32().apply { update(bytes) }.value)
}
