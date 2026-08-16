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

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorSessionStoreTest {

  private val session =
    EditorSession(
      kind = SessionKind.EDITOR,
      sessionId = "ses_01J9",
      openedBy = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
      openedFromConsole = false,
      deadline = Instant.parse("2026-08-10T13:00:00Z"),
      pollUntil = Instant.parse("2026-08-10T12:15:00Z"),
      baseline = mapOf("config.yml" to "sha256:1f0a", "monitor.yml" to "sha256:2b7c"),
    )

  @Test
  fun `a session read back is the session that was written`(@TempDir dir: Path) {
    val store = EditorSessionStore(dir.toFile(), SessionKind.EDITOR)

    store.write(session)

    assertEquals(session, store.read())
  }

  @Test
  fun `a console session keeps no player behind it`(@TempDir dir: Path) {
    val store = EditorSessionStore(dir.toFile(), SessionKind.EDITOR)
    val fromConsole = session.copy(openedBy = null, openedFromConsole = true)

    store.write(fromConsole)

    assertEquals(fromConsole, store.read())
  }

  @Test
  fun `setup and editor keep separate files`(@TempDir dir: Path) {
    val setup = EditorSessionStore(dir.toFile(), SessionKind.SETUP)
    val editor = EditorSessionStore(dir.toFile(), SessionKind.EDITOR)

    setup.write(session.copy(kind = SessionKind.SETUP, sessionId = "ses_setup"))
    editor.write(session.copy(sessionId = "ses_editor"))

    assertEquals("ses_setup", setup.read()?.sessionId)
    assertEquals("ses_editor", editor.read()?.sessionId)
    assertTrue(dir.resolve("setup.yml").toFile().isFile)
    assertTrue(dir.resolve("editor.yml").toFile().isFile)
  }

  @Test
  fun `the mid-apply marker survives a restart`(@TempDir dir: Path) {
    val store = EditorSessionStore(dir.toFile(), SessionKind.EDITOR)

    store.write(session.copy(applyInProgress = true))

    assertEquals(true, store.read()?.applyInProgress)
  }

  @Test
  fun `nothing on disk means no session`(@TempDir dir: Path) {
    assertNull(EditorSessionStore(dir.toFile(), SessionKind.EDITOR).read())
  }

  @Test
  fun `a damaged file reads as no session rather than throwing`(@TempDir dir: Path) {
    dir.resolve("editor.yml").writeText("session-id: [unclosed\n\tbroken: true\n")

    assertNull(EditorSessionStore(dir.toFile(), SessionKind.EDITOR).read())
  }

  @Test
  fun `a file with no session id reads as no session`(@TempDir dir: Path) {
    dir.resolve("editor.yml").writeText("opened-from-console: true\n")

    assertNull(EditorSessionStore(dir.toFile(), SessionKind.EDITOR).read())
  }

  @Test
  fun `clearing removes the file and leaves no temporary behind`(@TempDir dir: Path) {
    val store = EditorSessionStore(dir.toFile(), SessionKind.EDITOR)
    store.write(session)

    assertTrue(store.clear())

    assertNull(store.read())
    assertTrue(dir.toFile().listFiles()!!.none { it.name.endsWith(".tmp") })
  }
}
