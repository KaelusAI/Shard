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
package ac.shard.data

import ac.shard.player.ShardPlayer

class TickBuffer(private val capacity: Int) {

  private val buffer = Array(capacity) { TickData() }
  private var writeIndex = 0
  private var ticksWritten = 0L
  private var attackBufferIndex = -1

  fun capacity(): Int = capacity

  fun currentSlot(): TickData = buffer[writeIndex]

  fun capture(player: ShardPlayer) {
    currentSlot().capture(player)
  }

  fun advance() {
    ticksWritten++
    writeIndex = (writeIndex + 1) % capacity
  }

  fun resetForSession() {
    ticksWritten = 0
    attackBufferIndex = -1
  }

  fun currentWriteIndex(): Int = writeIndex

  fun attackIndex(): Int = attackBufferIndex

  fun markAttack() {
    attackBufferIndex = writeIndex
  }

  fun extractWindow(
    preWindow: Int,
    postWindow: Int,
    ticksSinceAttack: Int,
    attackIdx: Int = attackBufferIndex,
  ): List<TickData>? {
    if (attackIdx < 0) return null
    if (ticksSinceAttack >= capacity) return null

    val actualPostTicks = ticksSinceAttack.coerceAtMost(postWindow)
    val availablePre =
      minOf(preWindow.toLong(), ticksWritten - 1, (capacity - ticksSinceAttack - 1).toLong())
        .toInt()
        .coerceAtLeast(0)
    val expectedSize = availablePre + actualPostTicks
    if (expectedSize == 0) return null

    val actualStartIdx = (attackIdx - availablePre + capacity) % capacity
    val attackSeqId = buffer[attackIdx].sequenceId
    if (attackSeqId == 0) return null

    val window = ArrayList<TickData>(expectedSize)
    for (i in 0 until expectedSize) {
      val bufIdx = (actualStartIdx + i) % capacity
      val tick = buffer[bufIdx]
      if (tick.sequenceId != attackSeqId) return null

      val copy = TickData()
      copyTickData(tick, copy)
      copy.ticksToAttack = (i - availablePre).toShort()
      window.add(copy)
    }

    return window
  }

  companion object {
    @Suppress("LongMethod")
    private fun copyTickData(src: TickData, dst: TickData) {
      dst.sequenceId = src.sequenceId
      dst.tickIndex = src.tickIndex
      dst.dtMs = src.dtMs
      dst.targetEntityId = src.targetEntityId
      dst.yaw = src.yaw
      dst.pitch = src.pitch
      dst.x = src.x
      dst.y = src.y
      dst.z = src.z
      dst.movementBitfield = src.movementBitfield
      dst.pose = src.pose
      dst.inputForward = src.inputForward
      dst.inputBackward = src.inputBackward
      dst.inputLeft = src.inputLeft
      dst.inputRight = src.inputRight
      dst.inputJump = src.inputJump
      dst.inputShift = src.inputShift
      dst.inputSprint = src.inputSprint
      dst.gameMode = src.gameMode
      dst.foodLevel = src.foodLevel
      dst.health = src.health
      dst.damageTakenThisTick = src.damageTakenThisTick
      dst.ticksSinceDamage = src.ticksSinceDamage
      dst.groundFriction = src.groundFriction
      dst.fallDistance = src.fallDistance
      dst.movementSpeed = src.movementSpeed
      dst.jumpAmplifier = src.jumpAmplifier
      dst.slowFalling = src.slowFalling
      dst.playerPing = src.playerPing
      dst.speedAmplifier = src.speedAmplifier
      dst.slownessAmplifier = src.slownessAmplifier
      dst.hasteAmplifier = src.hasteAmplifier
      dst.miningFatigueAmplifier = src.miningFatigueAmplifier
      dst.entityInteractionRange = src.entityInteractionRange
      dst.scale = src.scale
      dst.gravity = src.gravity
      dst.jumpStrength = src.jumpStrength
      dst.stepHeight = src.stepHeight
      dst.targetValid = src.targetValid
      dst.targetDistance = src.targetDistance
      dst.targetAimAngle = src.targetAimAngle
      dst.targetRelX = src.targetRelX
      dst.targetRelY = src.targetRelY
      dst.targetRelZ = src.targetRelZ
      dst.ticksToAttack = src.ticksToAttack
      dst.attackThisTick = src.attackThisTick
      dst.aimError = src.aimError
      dst.sameTargetAsPrevAttack = src.sameTargetAsPrevAttack
      dst.targetsInRangeCount = src.targetsInRangeCount
      dst.isCritical = src.isCritical
      dst.raytraceHit = src.raytraceHit
      dst.attackRayOccluded = src.attackRayOccluded
      dst.raytraceMissDistance = src.raytraceMissDistance
      dst.inputValid = src.inputValid
      dst.hitPointRelX = src.hitPointRelX
      dst.hitPointRelY = src.hitPointRelY
      dst.hitPointRelZ = src.hitPointRelZ
      dst.ticksSinceAttack = src.ticksSinceAttack
      dst.swungThisTick = src.swungThisTick
      dst.attackCooldownProgress = src.attackCooldownProgress
      dst.attackSpeed = src.attackSpeed
      dst.isSprintingOnAttack = src.isSprintingOnAttack
      dst.receivedVelocityThisTick = src.receivedVelocityThisTick
      dst.velocityX = src.velocityX
      dst.velocityY = src.velocityY
      dst.velocityZ = src.velocityZ
      dst.ticksSinceVelocity = src.ticksSinceVelocity
      dst.receivedExplosionThisTick = src.receivedExplosionThisTick
      dst.ticksSinceExplosion = src.ticksSinceExplosion
      dst.ticksSinceUseItem = src.ticksSinceUseItem
      dst.activeItem = src.activeItem
      dst.rotationPresent = src.rotationPresent
      dst.modeYaw = src.modeYaw
      dst.modePitch = src.modePitch
      dst.modeYawValid = src.modeYawValid
      dst.modePitchValid = src.modePitchValid
      dst.deltaDotsYaw = src.deltaDotsYaw
      dst.deltaDotsPitch = src.deltaDotsPitch
      dst.activeFireworkCount = src.activeFireworkCount
      dst.stuckMultX = src.stuckMultX
      dst.stuckMultY = src.stuckMultY
      dst.stuckMultZ = src.stuckMultZ
      dst.onSlime = src.onSlime
      dst.onHoney = src.onHoney
      dst.onSoulSand = src.onSoulSand
      dst.onMud = src.onMud
      dst.levitationAmplifier = src.levitationAmplifier
      dst.swimFriction = src.swimFriction
      dst.riptideActive = src.riptideActive
      dst.teleportTick = src.teleportTick
      dst.exposureFraction = src.exposureFraction
      dst.boxInflation = src.boxInflation
    }
  }
}
