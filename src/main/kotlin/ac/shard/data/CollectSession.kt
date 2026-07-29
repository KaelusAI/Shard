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
package ac.shard.data

import ac.shard.ai.TickSerializer
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class CollectSession(
  val sessionId: UUID,
  val playerUuid: UUID,
  val playerName: String,
  val label: String,
  val serverVersion: String = "",
  val clientVersion: Int = 0,
) {
  val startTime: Instant = Instant.now()
  private val pendingWindows = ConcurrentLinkedQueue<List<TickData>>()
  private val windowCount = AtomicInteger(0)

  fun addWindow(window: List<TickData>) {
    pendingWindows.add(window)
    windowCount.incrementAndGet()
  }

  fun windowCount(): Int = windowCount.get()

  @Throws(IOException::class)
  fun save(dataFolder: File) {
    val windows = mutableListOf<List<TickData>>()
    while (true) {
      windows.add(pendingWindows.poll() ?: break)
    }
    if (windows.isEmpty()) return

    val collectRoot = File(dataFolder, COLLECT_DIR)
    val finalDir = File(collectRoot, generateSessionDirName())
    val stagingDir = File(File(collectRoot, STAGING_DIR), sessionId.toString())
    if (stagingDir.exists()) stagingDir.deleteRecursively()
    stagingDir.mkdirs()

    var totalTicks = 0
    var writtenIdx = 0
    for (window in windows) {
      if (window.isEmpty()) continue

      Files.newBufferedWriter(File(stagingDir, "w$writtenIdx.csv").toPath(), StandardCharsets.UTF_8)
        .use { writer ->
          writer.append(TickSchema.csvHeader).append('\n')
          for (tick in window) {
            TickSchema.appendCsvRow(writer, tick)
            writer.append('\n')
          }
        }
      totalTicks += window.size
      writtenIdx++
    }

    Files.newBufferedWriter(File(stagingDir, "session.meta").toPath(), StandardCharsets.UTF_8)
      .use { w ->
        w.append("session_id=").append(sessionId.toString()).append('\n')
        w.append("player_uuid=").append(playerUuid.toString()).append('\n')
        w.append("player_name=").append(playerName).append('\n')
        w.append("label=").append(label).append('\n')
        w.append("format_version=1").append('\n')
        w.append("schema_version=").append(TickSerializer.SCHEMA_VERSION.toString()).append('\n')
        w.append("collection_timestamp=").append(startTime.toEpochMilli().toString()).append('\n')
        w.append("server_version=").append(serverVersion).append('\n')
        w.append("client_version=").append(clientVersion.toString()).append('\n')
        w.append("windows=").append(windows.size.toString()).append('\n')
        w.append("ticks=").append(totalTicks.toString()).append('\n')
      }

    finalDir.parentFile.mkdirs()
    try {
      Files.move(stagingDir.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(stagingDir.toPath(), finalDir.toPath())
    }
  }

  private fun generateSessionDirName(): String {
    val timestamp = TIMESTAMP_FORMAT.format(startTime)
    val safeLabel = label.replace(' ', '_').replace(Regex("[/\\\\?%*:|\"<>']"), "-")
    return "${safeLabel}_${playerName}_${timestamp}"
  }

  companion object {
    private const val COLLECT_DIR = "collect"
    private const val STAGING_DIR = ".staging"

    private val TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    fun sweepStaging(dataFolder: File) {
      val staging = File(File(dataFolder, COLLECT_DIR), STAGING_DIR)
      if (!staging.isDirectory) return
      staging.listFiles()?.forEach { it.deleteRecursively() }
    }
  }
}
