/*
 * This file is part of GrimAC - https://github.com/GrimAnticheat/Grim
 * Copyright (C) 2021-2026 GrimAC, DefineOutside and contributors
 *
 * GrimAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GrimAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.utils.math

import kotlin.math.floor
import kotlin.math.pow

object ShardMath {
  private val MINIMUM_DIVISOR: Double = 0.2f.pow(3) * 8 * 0.15 - 1e-3

  @JvmStatic fun getMinimumDivisor(): Double = MINIMUM_DIVISOR

  @JvmStatic
  fun gcd(aInput: Double, bInput: Double): Double {
    if (aInput == 0.0) return 0.0

    var a = aInput
    var b = bInput
    // Make sure a is larger than b
    if (a < b) {
      val temp = a
      a = b
      b = temp
    }

    while (b > MINIMUM_DIVISOR) { // Minimum minecraft sensitivity
      val temp = a - (floor(a / b) * b)
      a = b
      b = temp
    }

    return a
  }
}
