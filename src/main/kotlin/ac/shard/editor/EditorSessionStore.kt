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
package ac.shard.editor

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

enum class SessionKind {
  SETUP,
  EDITOR,
}

data class EditorSession(
  val kind: SessionKind,
  val sessionId: String,
  val openedBy: UUID?,
  val openedFromConsole: Boolean,
  val deadline: Instant,
  val pollUntil: Instant,
  val baseline: Map<String, String>,
  val applyInProgress: Boolean = false,
)

internal class EditorSessionStore(private val dataFolder: File, private val kind: SessionKind) {

  private val file = File(dataFolder, if (kind == SessionKind.SETUP) "setup.yml" else "editor.yml")

  fun read(): EditorSession? {
    if (!file.isFile) {
      return null
    }
    return runCatching { parse() }.getOrNull()
  }

  private fun parse(): EditorSession? {
    val node = YamlConfigurationLoader.builder().path(file.toPath()).build().load()
    val sessionId = node.node("session-id").getString("")
    return if (sessionId.isBlank()) {
      null
    } else {
      EditorSession(
        kind = kind,
        sessionId = sessionId,
        openedBy =
          node.node("opened-by").getString("").takeIf { it.isNotBlank() }?.let(UUID::fromString),
        openedFromConsole = node.node("opened-from-console").getBoolean(false),
        deadline = Instant.ofEpochSecond(node.node("deadline").getLong(0)),
        pollUntil = Instant.ofEpochSecond(node.node("poll-until").getLong(0)),
        baseline =
          node.node("baseline").childrenMap().entries.associate {
            it.key.toString() to it.value.getString("")
          },
        applyInProgress = node.node("apply-in-progress").getBoolean(false),
      )
    }
  }

  fun write(session: EditorSession) {
    val tmp = File(dataFolder, "${file.name}.tmp")
    try {
      dataFolder.mkdirs()
      Files.deleteIfExists(tmp.toPath())
      val loader = YamlConfigurationLoader.builder().path(tmp.toPath()).build()
      val node = loader.createNode()
      node.node("session-id").set(session.sessionId)
      node.node("opened-by").set(session.openedBy?.toString().orEmpty())
      node.node("opened-from-console").set(session.openedFromConsole)
      node.node("deadline").set(session.deadline.epochSecond)
      node.node("poll-until").set(session.pollUntil.epochSecond)
      node.node("apply-in-progress").set(session.applyInProgress)
      session.baseline.forEach { (name, hash) -> node.node("baseline", name).set(hash) }
      loader.save(node)
      restrict(tmp)
      moveIntoPlace(tmp)
    } finally {
      tmp.delete()
    }
  }

  fun clear(): Boolean = !file.exists() || file.delete()

  private fun moveIntoPlace(tmp: File) {
    try {
      Files.move(
        tmp.toPath(),
        file.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun restrict(target: File) {
    runCatching {
      Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
  }
}
