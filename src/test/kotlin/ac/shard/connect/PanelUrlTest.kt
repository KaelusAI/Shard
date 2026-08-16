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
package ac.shard.connect

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PanelUrlTest {

  @Test
  fun `https is accepted for any host`() {
    assertTrue(isSecurePanelUrl("https://app.shard.ac"))
    assertTrue(isSecurePanelUrl("  https://panel.example.com:8443/base  "))
  }

  @Test
  fun `plain http is accepted only on the loopback`() {
    assertTrue(isSecurePanelUrl("http://localhost:8080"))
    assertTrue(isSecurePanelUrl("http://127.0.0.1"))
    assertTrue(isSecurePanelUrl("http://[::1]:3000"))
    assertFalse(isSecurePanelUrl("http://app.shard.ac"))
    assertFalse(isSecurePanelUrl("http://192.168.1.10:8080"))
  }

  @Test
  fun `a host that merely looks like the loopback is rejected`() {
    assertFalse(isSecurePanelUrl("http://localhost.evil.com"))
    assertFalse(isSecurePanelUrl("http://127.0.0.1.evil.com"))
  }

  @Test
  fun `other schemes and unparseable values are rejected`() {
    assertFalse(isSecurePanelUrl("ftp://app.shard.ac"))
    assertFalse(isSecurePanelUrl("app.shard.ac"))
    assertFalse(isSecurePanelUrl(""))
    assertFalse(isSecurePanelUrl("http://"))
  }
}
