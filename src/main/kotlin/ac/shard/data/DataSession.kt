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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package ac.shard.data

import ac.shard.Shard
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class DataSession(val uuid: UUID, val player: String, val status: String) {
  val recordedTicks: Queue<TickData> = ConcurrentLinkedQueue()
  val startTime: Instant = Instant.now()

  fun addTick(tickData: TickData) {
    recordedTicks.add(tickData)
  }

  fun generateFileName(): String {
    val timestamp = TIMESTAMP_FORMAT.format(startTime)
    val statusForFilename = status.replace(' ', '#').replace(Regex("[/\\\\?%*:|\"<>']"), "-")
    return String.format("%s_%s_%s.csv", statusForFilename, player, timestamp)
  }

  fun writeCsv(writer: Appendable) {
    if (recordedTicks.isEmpty()) {
      return
    }
    writer.append(TickData.HEADER).append('\n')
    val cheatingStatus =
      when {
        status.startsWith("CHEAT") -> "CHEAT"
        else -> "LEGIT"
      }
    for (tick in recordedTicks) {
      tick.appendCsv(writer, cheatingStatus)
      writer.append('\n')
    }
  }

  @Throws(IOException::class)
  fun saveAndClose(plugin: Shard) {
    if (recordedTicks.isEmpty()) {
      return
    }
    val dataFolder = File(plugin.dataFolder, "datacollection")
    if (!dataFolder.exists()) {
      dataFolder.mkdirs()
    }
    val outputFile = File(dataFolder, generateFileName())
    Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8).use { writer ->
      writeCsv(writer)
    }
  }

  private companion object {
    private val TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
  }
}
