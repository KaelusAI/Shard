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

import ac.shard.http.isSecureEndpoint
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PanelUrlTest {

  @Test
  fun `https is accepted for any host`() {
    assertTrue(isSecureEndpoint("https://app.shard.ac"))
    assertTrue(isSecureEndpoint("  https://panel.example.com:8443/base  "))
  }

  @Test
  fun `plain http is accepted only on the loopback`() {
    assertTrue(isSecureEndpoint("http://localhost:8080"))
    assertTrue(isSecureEndpoint("http://127.0.0.1"))
    assertTrue(isSecureEndpoint("http://[::1]:3000"))
    assertFalse(isSecureEndpoint("http://app.shard.ac"))
    assertFalse(isSecureEndpoint("http://192.168.1.10:8080"))
  }

  @Test
  fun `a host that merely looks like the loopback is rejected`() {
    assertFalse(isSecureEndpoint("http://localhost.evil.com"))
    assertFalse(isSecureEndpoint("http://127.0.0.1.evil.com"))
  }

  @Test
  fun `other schemes and unparseable values are rejected`() {
    assertFalse(isSecureEndpoint("ftp://app.shard.ac"))
    assertFalse(isSecureEndpoint("app.shard.ac"))
    assertFalse(isSecureEndpoint(""))
    assertFalse(isSecureEndpoint("http://"))
  }
}
