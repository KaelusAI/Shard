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
package ac.shard.panel

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PendingLinkStoreTest {

  private val pending =
    PendingLink(
      deviceCode = "dev-123",
      userCode = "ABCD-EFGH",
      url = "https://app.shard.ac/connect?code=ABCD-EFGH",
      deadlineEpochSec = 1_786_000_000L,
      intervalSeconds = 5L,
    )

  @Test
  fun `a device code survives a restart`(@TempDir dir: Path) {
    PendingLinkStore(dir.toFile()).write(pending)

    assertEquals(pending, PendingLinkStore(dir.toFile()).read())
  }

  @Test
  fun `nothing is pending when the file is missing`(@TempDir dir: Path) {
    assertNull(PendingLinkStore(dir.toFile()).read())
  }

  @Test
  fun `a cleared link is gone for good`(@TempDir dir: Path) {
    val store = PendingLinkStore(dir.toFile())
    store.write(pending)

    store.clear()

    assertNull(store.read())
  }

  @Test
  fun `a file without a device code is not a pending link`(@TempDir dir: Path) {
    dir.resolve("linking.yml").toFile().writeText("user-code: \"ABCD\"\ndeadline: 1\n")

    assertNull(PendingLinkStore(dir.toFile()).read())
  }
}
