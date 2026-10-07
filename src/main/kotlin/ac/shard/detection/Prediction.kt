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
package ac.shard.detection

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class Prediction(
  val playerId: UUID,
  val playerName: String,
  val probability: Double,
  val bufferBefore: Double,
  val bufferAfter: Double,
  val damageMultiplier: Double,
  val prob90: Int,
  val flagged: Boolean,
  val labelBuffers: Map<String, Double> = emptyMap(),
  val labelProbabilities: Map<String, Double> = emptyMap(),
  val model: String = "",
  val primary: Boolean = true,
  val modelId: String = "",
)

class Predictions {
  private val listeners = CopyOnWriteArrayList<(Prediction) -> Unit>()

  fun listen(listener: (Prediction) -> Unit) {
    listeners += listener
  }

  fun publish(prediction: Prediction) {
    listeners.forEach { it(prediction) }
  }
}
