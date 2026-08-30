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
package ac.shard.di

import ac.shard.config.MitigationsFile
import ac.shard.mitigation.HitStamps
import ac.shard.mitigation.MitigationSettings
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class HitStampsBindingTest {

  @Test
  fun `hit stamps keep their own clock while a settings provider sits in the graph`() {
    val app = koinApplication {
      modules(
        module {
          single<() -> MitigationSettings> { { MitigationsFile.OFF } }
          single { HitStamps() }
        }
      )
    }

    val stamps = app.koin.get<HitStamps>()
    val projectile = UUID.randomUUID()

    stamps.remember(projectile, UUID.randomUUID(), 0.5)

    assertNotNull(
      stamps.take(projectile),
      "a stamp is only kept when the clock returns a number, so this fails once the " +
        "settings provider is injected in place of the clock",
    )
  }
}
