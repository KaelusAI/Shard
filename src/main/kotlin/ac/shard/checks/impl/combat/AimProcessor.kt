/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * This file contains code derived from GrimAC.
 * The original authors of GrimAC are credited below.
 *
 * Copyright (c) 2021-2026 GrimAC, DefineOutside and contributors.
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
package ac.shard.checks.impl.combat

import ac.shard.checks.AbstractCheck
import ac.shard.checks.CheckData
import ac.shard.checks.CheckFactory
import ac.shard.checks.type.RotationCheck
import ac.shard.player.ShardPlayer
import ac.shard.utils.data.HeadRotation
import ac.shard.utils.lists.RunningMode
import ac.shard.utils.math.ShardMath
import ac.shard.utils.update.RotationUpdate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ulp

@CheckData(name = "AimProcessor_Internal")
class AimProcessor(shardPlayer: ShardPlayer) : AbstractCheck(shardPlayer), RotationCheck {
  var modeX: Double = 0.0
    private set

  var modeY: Double = 0.0
    private set

  var deltaDotsX: Double = 0.0
    private set

  var deltaDotsY: Double = 0.0
    private set

  private val xRotMode = RunningMode(TOTAL_SAMPLES_THRESHOLD)
  private val yRotMode = RunningMode(TOTAL_SAMPLES_THRESHOLD)
  private var lastXRot = 0.0
  private var lastYRot = 0.0
  private var dotsFresh = false

  val modeYawValid: Boolean
    get() = xRotMode.modeCount > SIGNIFICANT_SAMPLES_THRESHOLD

  val modePitchValid: Boolean
    get() = yRotMode.modeCount > SIGNIFICANT_SAMPLES_THRESHOLD

  interface Factory : CheckFactory {
    override fun create(player: ShardPlayer): AimProcessor
  }

  override fun process(rotationUpdate: RotationUpdate) {
    val deltaYawAbs = abs(rotationUpdate.deltaYaw).toDouble()
    val deltaPitchAbs = abs(rotationUpdate.deltaPitch).toDouble()

    val divisorX = ShardMath.gcd(deltaYawAbs, lastXRot)
    if (
      deltaYawAbs > 0 && deltaYawAbs < MAX_SAMPLE_DELTA && divisorX > ShardMath.getMinimumDivisor()
    ) {
      val yawUlp = max(abs(rotationUpdate.from.yaw), abs(rotationUpdate.to.yaw)).ulp.toDouble()
      if (yawUlp < divisorX * YAW_ULP_DIVISOR_FRACTION) {
        if (lastXRot != 0.0) {
          xRotMode.add(divisorX, max(RunningMode.THRESHOLD, yawUlp * YAW_BUCKET_ULP_FACTOR))
        }
        lastXRot = deltaYawAbs
      }
    }

    val divisorY = ShardMath.gcd(deltaPitchAbs, lastYRot)
    if (
      deltaPitchAbs > 0 &&
        deltaPitchAbs < MAX_SAMPLE_DELTA &&
        divisorY > ShardMath.getMinimumDivisor()
    ) {
      if (lastYRot != 0.0) {
        yRotMode.add(divisorY)
      }
      lastYRot = deltaPitchAbs
    }

    if (xRotMode.size() > SIGNIFICANT_SAMPLES_THRESHOLD) {
      xRotMode.updateMode()
      if (xRotMode.modeCount > SIGNIFICANT_SAMPLES_THRESHOLD) {
        modeX = xRotMode.modeValue
      }
    }

    if (yRotMode.size() > SIGNIFICANT_SAMPLES_THRESHOLD) {
      yRotMode.updateMode()
      if (yRotMode.modeCount > SIGNIFICANT_SAMPLES_THRESHOLD) {
        modeY = yRotMode.modeValue
      }
    }

    if (modeX > 0) {
      deltaDotsX = deltaYawAbs / modeX
    }
    if (modeY > 0) {
      deltaDotsY = deltaPitchAbs / modeY
    }

    dotsFresh = true
  }

  fun consumeDots(): HeadRotation {
    val dots =
      if (dotsFresh) HeadRotation(deltaDotsX.toFloat(), deltaDotsY.toFloat())
      else HeadRotation(0f, 0f)
    dotsFresh = false
    return dots
  }

  fun reset() {
    xRotMode.clear()
    yRotMode.clear()
    lastXRot = 0.0
    lastYRot = 0.0
    modeX = 0.0
    modeY = 0.0
    deltaDotsX = 0.0
    deltaDotsY = 0.0
    dotsFresh = false
  }

  private companion object {
    const val SIGNIFICANT_SAMPLES_THRESHOLD = 15
    const val TOTAL_SAMPLES_THRESHOLD = 80
    const val MAX_SAMPLE_DELTA = 5.0
    const val YAW_ULP_DIVISOR_FRACTION = 1.0 / 16.0
    const val YAW_BUCKET_ULP_FACTOR = 2.0
  }
}
