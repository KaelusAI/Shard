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

import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class NamedBindingCollectionTest {
  private interface Renderer {
    val id: String
  }

  private class Fake(override val id: String) : Renderer

  private class Collector(val renderers: List<Renderer>)

  @Test
  fun `getAll collects every named binding of one type`() {
    val app = koinApplication {
      modules(
        module {
          single<Renderer>(named("a")) { Fake("a") }
          single<Renderer>(named("b")) { Fake("b") }
          single<Renderer>(named("c")) { Fake("c") }
          single { Collector(getAll()) }
        }
      )
    }

    val collector = app.koin.get<Collector>()

    assertEquals(listOf("a", "b", "c"), collector.renderers.map { it.id }.sorted())
  }
}
