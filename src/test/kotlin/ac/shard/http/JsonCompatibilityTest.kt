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
package ac.shard.http

import ac.shard.network.NetworkAlert
import ac.shard.network.SuspiciousSnapshot
import ac.shard.punishment.cloud.PunishmentDoc
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class JsonCompatibilityTest {

  @Test
  fun `the punishment tree hash the panel checks stays byte for byte`() {
    val doc =
      Json.mapper.readTree(
        """{"groups":{"b":{"expire":"1d","labels":["y","x"],"actions":{"3":"[alert]",""" +
          """"1":"say é \"q\" <player>"}},"a":{}},"other":{"expire":"2h"}}"""
      )

    assertEquals(CANONICAL, PunishmentDoc.canonical(doc))
    assertEquals(HASH, PunishmentDoc.hash(doc))
  }

  @Test
  fun `payloads other servers read keep their json`() {
    assertEquals(
      ALERT,
      Json.mapper.writeValueAsString(NetworkAlert("o", "s", "REGULAR", "{\"text\":\"x\"}")),
    )
    assertEquals(
      SNAPSHOT,
      Json.mapper.writeValueAsString(SuspiciousSnapshot("s", "u", "n", 1.5, 20, 7L, null, 0.25)),
    )
  }

  @Test
  fun `payloads from newer servers with extra fields still parse`() {
    val parsed =
      Json.lenient.readValue(
        """{"origin":"o","server":"s","type":"REGULAR","component":"c","extra":1}""",
        NetworkAlert::class.java,
      )

    assertEquals(NetworkAlert("o", "s", "REGULAR", "c"), parsed)
  }

  private companion object {
    const val CANONICAL =
      """{"groups":{"b":{"labels":["y","x"],"expire":"1d","actions":{"1":"say é \"q\" <player>",""" +
        """"3":"[alert]"}},"a":{}},"other":{"expire":"2h"}}"""
    const val HASH = "2744603b308b53b4f592be3ce8a2d4424e65c74dbd5cbb2d1320985c3fbc5404"
    const val ALERT =
      """{"origin":"o","server":"s","type":"REGULAR","component":"{\"text\":\"x\"}"}"""
    const val SNAPSHOT =
      """{"server":"s","uuid":"u","name":"n","buffer":1.5,"ping":20,"updatedAt":7,""" +
        """"level":null,"score":0.25}"""
  }
}
