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
package ac.shard.config.yaml

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

sealed interface WriteOutcome {
  data class Written(val stamp: String, val files: Set<String>) : WriteOutcome

  data class Rejected(val reason: String) : WriteOutcome

  data class RolledBack(val reason: String, val stamp: String) : WriteOutcome
}

@Suppress("TooManyFunctions")
internal class YamlFileStore(
  private val dataFolder: File,
  private val logger: Logger,
  private val now: () -> Instant = Instant::now,
) {

  @Synchronized
  fun write(contents: Map<String, String>): WriteOutcome {
    val missing = contents.keys.filterNot { File(dataFolder, it).isFile }
    return if (missing.isNotEmpty()) {
      WriteOutcome.Rejected("no such file: ${missing.sorted().joinToString(", ")}")
    } else {
      transact(contents)
    }
  }

  private fun transact(contents: Map<String, String>): WriteOutcome {
    val (stamp, backups) = freeBackupDir()
    return runCatching {
        backups.mkdirs()
        contents.keys.forEach { copyInto(File(dataFolder, it), File(backups, it)) }
        contents.forEach { (name, body) -> replace(File(dataFolder, name), body) }
        verify(contents.keys)
      }
      .fold(
        onSuccess = { broken ->
          if (broken == null) {
            WriteOutcome.Written(stamp, contents.keys.toSet())
          } else {
            rollback(contents.keys, backups, broken, stamp)
          }
        },
        onFailure = { rollback(contents.keys, backups, it.message ?: it.toString(), stamp) },
      )
  }

  private fun freeBackupDir(): Pair<String, File> {
    val root = File(dataFolder, BACKUP_DIR)
    val stamp = STAMP_FORMAT.format(now().atZone(ZoneOffset.UTC))
    if (!File(root, stamp).exists()) return stamp to File(root, stamp)
    var suffix = 2
    while (File(root, "$stamp-$suffix").exists() && suffix < MAX_BACKUPS_PER_SECOND) {
      suffix++
    }
    return "$stamp-$suffix" to File(root, "$stamp-$suffix")
  }

  private fun verify(names: Collection<String>): String? = names.firstNotNullOfOrNull { name ->
    val file = File(dataFolder, name)
    val failure =
      runCatching {
          YamlConfigurationLoader.builder().path(file.toPath()).build().load()
          YamlPatcher.read(file)
        }
        .exceptionOrNull()
    failure?.let { "$name did not survive the write: ${it.message}" }
  }

  fun stamps(): List<String> =
    File(dataFolder, BACKUP_DIR)
      .listFiles()
      .orEmpty()
      .filter { it.isDirectory && it.listFiles().orEmpty().any { file -> file.isFile } }
      .map { it.name }
      .sortedDescending()

  @Synchronized
  fun restore(stamp: String): WriteOutcome {
    val saved = File(File(dataFolder, BACKUP_DIR), stamp)
    val files = saved.listFiles().orEmpty().filter { it.isFile }
    if (!saved.isDirectory || files.isEmpty()) {
      return WriteOutcome.Rejected("no backup called $stamp")
    }
    val bodies = files.associate { it.name to it.readText() }
    return write(bodies)
  }

  private fun rollback(
    names: Collection<String>,
    backups: File,
    reason: String,
    stamp: String,
  ): WriteOutcome {
    logger.severe("[Config] $reason - restoring the files saved under $BACKUP_DIR/$stamp")
    names.forEach { name ->
      runCatching { copyInto(File(backups, name), File(dataFolder, name)) }
        .onFailure { logger.severe("[Config] Could not restore $name: ${it.message}") }
    }
    return WriteOutcome.RolledBack(reason, stamp)
  }

  private fun copyInto(source: File, target: File) {
    target.parentFile?.mkdirs()
    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    restrict(target)
  }

  private fun replace(target: File, body: String) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
      Files.deleteIfExists(tmp.toPath())
      tmp.writeText(body)
      restrict(tmp)
      moveIntoPlace(tmp, target)
    } finally {
      tmp.delete()
    }
  }

  private fun moveIntoPlace(tmp: File, target: File) {
    try {
      Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun restrict(target: File) {
    runCatching {
      Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
  }

  private companion object {
    const val BACKUP_DIR = "backups"
    const val MAX_BACKUPS_PER_SECOND = 100
    val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}
