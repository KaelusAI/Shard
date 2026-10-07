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
package ac.shard.utils

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

object AtomicFiles {
  fun replace(target: Path, ownerOnly: Boolean = false, write: (Path) -> Unit) {
    val dir = target.toAbsolutePath().parent
    Files.createDirectories(dir)
    val temp = Files.createTempFile(dir, target.fileName.toString(), ".tmp")
    try {
      if (ownerOnly) restrict(temp)
      write(temp)
      if (ownerOnly) restrict(temp)
      move(temp, target)
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  private fun restrict(path: Path) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    } catch (_: UnsupportedOperationException) {
      return
    }
  }

  private fun move(source: Path, target: Path) {
    try {
      Files.move(
        source,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
