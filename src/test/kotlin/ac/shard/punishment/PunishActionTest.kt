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
package ac.shard.punishment

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class PunishActionTest {

  @Test
  fun `built-in actions ignore case and surrounding spaces`() {
    assertEquals(PunishAction.Alert, PunishAction.parse("  [ALERT] "))
    assertEquals(PunishAction.Log, PunishAction.parse("[log]"))
    assertEquals(PunishAction.Reset, PunishAction.parse("[Reset]"))
  }

  @Test
  fun `broadcast keeps the template as written`() {
    assertEquals(PunishAction.Broadcast("<b>x</b>"), PunishAction.parse("[BROADCAST] <b>x</b>"))
  }

  @Test
  fun `wait reads its duration once and keeps what it could not read`() {
    assertEquals(PunishAction.Wait("1m", 1_200L), PunishAction.parse("[wait] 1m"))
    assertEquals(PunishAction.Wait("abc", null), PunishAction.parse("[wait] abc"))
    assertNull(PunishAction.parse("[wait]"))
  }

  @Test
  fun `anything else is a console command`() {
    assertEquals(PunishAction.Console("kick <player>"), PunishAction.parse(" kick <player>"))
  }
}
