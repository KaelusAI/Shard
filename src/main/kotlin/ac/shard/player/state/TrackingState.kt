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
package ac.shard.player.state

import ac.shard.data.TickData
import com.github.retrooper.packetevents.protocol.item.type.ItemType
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes

@Suppress("TooManyFunctions")
class TrackingState {

  var heldSlot: Int = 0
  val hotbarItems: Array<ItemType?> = arrayOfNulls(HOTBAR_SIZE)
  var offhandItem: ItemType? = null
  var usingOffhand: Boolean = false
  var activeItem: Short = ACTIVE_ITEM_NONE

  @Volatile var pendingSequenceBreak: Boolean = false
  @Volatile var pendingBufferReset: Boolean = false

  var sequenceId: Int = 1
  var tickIndex: Long = 0

  var lastY: Double = 0.0
  var firstTickProcessed: Boolean = false
  var rotationThisTick: Boolean = false
  var lastCaptureAtMillis: Long = 0L

  var floodCreditsMs: Double = FLOOD_BURST_MS
  var floodLastNanos: Long = 0L
  var floodDroppedCaptures: Long = 0L

  var sprinting: Boolean = false
  var sneaking: Boolean = false
  var isUsingItem: Boolean = false
  var flying: Boolean = false
  var swimming: Boolean = false
  var gliding: Boolean = false
  var inVehicle: Boolean = false
  var isClimbing: Boolean = false
  var inWater: Boolean = false

  var pose: Int = -1

  var inputForward: Boolean = false
  var inputBackward: Boolean = false
  var inputLeft: Boolean = false
  var inputRight: Boolean = false
  var inputJump: Boolean = false
  var inputShift: Boolean = false
  var inputSprint: Boolean = false
  var inputValid: Boolean = false

  var gameMode: Int = 0
  var foodLevel: Int = 20
  var health: Float = DEFAULT_HEALTH
  var damageTakenThisTick: Float = 0.0f
  var ticksSinceDamage: Int = 255
  var groundFriction: Float = 0.6f
  var fallDistance: Float = 0.0f
  var movementSpeed: Float = 0.1f

  var jumpAmplifier: Int = -1
  var slowFalling: Boolean = false
  var hasBlindness: Boolean = false
  var playerPing: Int = 0

  var speedAmplifier: Int = -1
  var slownessAmplifier: Int = -1
  var hasteAmplifier: Int = -1
  var miningFatigueAmplifier: Int = -1

  var entityInteractionRange: Float = 3.0f
  var scale: Float = 1.0f
  var gravity: Float = 0.08f
  var jumpStrength: Float = 0.42f
  var stepHeight: Float = 0.6f

  var targetEntityId: Int = -1
  var lastTargetEntityId: Int = -1

  var ticksSinceAttack: Int = 255
  var cooldownTicks: Int = 255
  var preAttackCooldownProgress: Float = 1.0f
  var swungThisTick: Boolean = false
  var attackThisTick: Boolean = false
  var windowStartThisTick: Boolean = false
  var windowStartKind: Short = 0
  var enabledWindowStarts: Int = MELEE_PLAYER_ONLY
  var isSprintingOnAttack: Boolean = false
  var attackSpeed: Float = 4.0f

  var receivedVelocityThisTick: Boolean = false
  var velocityX: Float = 0.0f
  var velocityY: Float = 0.0f
  var velocityZ: Float = 0.0f
  var ticksSinceVelocity: Int = 255
  var receivedExplosionThisTick: Boolean = false
  var ticksSinceExplosion: Int = 255
  var explosionKbX: Float = 0.0f
  var explosionKbY: Float = 0.0f
  var explosionKbZ: Float = 0.0f

  var ticksSinceUseItem: Int = 255

  var slotSwitchesThisTick: Int = 0
  var placesThisTick: Int = 0
  var placeFace: Int = -1
  var placeBlockClass: Int = 0
  var placeBlockX: Int = 0
  var placeBlockY: Int = 0
  var placeBlockZ: Int = 0
  var placeCursorX: Float = -1.0f
  var placeCursorY: Float = -1.0f
  var placeCursorZ: Float = -1.0f
  var placeInsideBlock: Boolean = false

  var crystalSpawnToAttack: Int = -1
  var placeOffhand: Boolean = false
  var attackTargetType: Short = TickData.TARGET_PLAYER
  var anchorCharge: Int = -1
  var anchorUseInterval: Int = -1

  var stuckMultX: Float = 1.0f
  var stuckMultY: Float = 1.0f
  var stuckMultZ: Float = 1.0f

  var onSlime: Boolean = false
  var onHoney: Boolean = false
  var onSoulSand: Boolean = false
  var onMud: Boolean = false

  var levitationAmplifier: Int = -1
  var dolphinsGrace: Boolean = false
  var waterMovementEfficiency: Float = 0.0f
  var riptideActive: Boolean = false

  fun onTickStart() {
    if (pendingSequenceBreak) {
      pendingSequenceBreak = false
      onSequenceBreak()
    }
    tickIndex++
  }

  fun onTickEnd() {
    if (ticksSinceAttack < 255) ticksSinceAttack++
    if (cooldownTicks < 255) cooldownTicks++
    if (ticksSinceVelocity < 255) ticksSinceVelocity++
    if (ticksSinceExplosion < 255) ticksSinceExplosion++
    if (ticksSinceUseItem < 255) ticksSinceUseItem++
    if (ticksSinceDamage < 255) ticksSinceDamage++

    swungThisTick = false
    attackThisTick = false
    windowStartThisTick = false
    windowStartKind = 0
    damageTakenThisTick = 0.0f
    receivedVelocityThisTick = false
    receivedExplosionThisTick = false
    isSprintingOnAttack = false
    rotationThisTick = false
    slotSwitchesThisTick = 0
    placesThisTick = 0
    placeFace = -1
    placeBlockClass = 0
    placeBlockX = 0
    placeBlockY = 0
    placeBlockZ = 0
    placeCursorX = -1.0f
    placeCursorY = -1.0f
    placeCursorZ = -1.0f
    placeInsideBlock = false
    crystalSpawnToAttack = -1
    placeOffhand = false
    attackTargetType = TickData.TARGET_PLAYER
    anchorCharge = -1
    anchorUseInterval = -1
  }

  fun onTickAborted() {
    swungThisTick = false
    attackThisTick = false
    windowStartThisTick = false
    windowStartKind = 0
    damageTakenThisTick = 0.0f
    receivedVelocityThisTick = false
    receivedExplosionThisTick = false
    isSprintingOnAttack = false
    rotationThisTick = false
    slotSwitchesThisTick = 0
    placesThisTick = 0
    placeFace = -1
    placeBlockClass = 0
    placeBlockX = 0
    placeBlockY = 0
    placeBlockZ = 0
    placeCursorX = -1.0f
    placeCursorY = -1.0f
    placeCursorZ = -1.0f
    placeInsideBlock = false
    crystalSpawnToAttack = -1
    placeOffhand = false
    attackTargetType = TickData.TARGET_PLAYER
    anchorCharge = -1
    anchorUseInterval = -1
  }

  fun raiseWindowStart(kind: Short) {
    if (enabledWindowStarts and (1 shl kind.toInt()) == 0) return
    if (!windowStartThisTick || kind < windowStartKind) {
      windowStartThisTick = true
      windowStartKind = kind
    }
  }

  fun onAttack(entityId: Int, isSprinting: Boolean) {
    attackThisTick = true
    lastTargetEntityId = targetEntityId
    targetEntityId = entityId
    isSprintingOnAttack = isSprinting
    val cooldownPeriod = 20.0f / attackSpeed
    preAttackCooldownProgress = ((cooldownTicks + 0.5f) / cooldownPeriod).coerceIn(0f, 1f)
    ticksSinceAttack = 0
    cooldownTicks = 0
  }

  fun onSwing() {
    swungThisTick = true
    cooldownTicks = 0
  }

  fun onKnockback(vx: Double, vy: Double, vz: Double) {
    receivedVelocityThisTick = true
    velocityX = vx.toFloat()
    velocityY = vy.toFloat()
    velocityZ = vz.toFloat()
    ticksSinceVelocity = 0
  }

  fun onExplosion(kbX: Float, kbY: Float, kbZ: Float) {
    receivedExplosionThisTick = true
    ticksSinceExplosion = 0
    explosionKbX = kbX
    explosionKbY = kbY
    explosionKbZ = kbZ
  }

  fun onSlotSwitch() {
    slotSwitchesThisTick++
  }

  fun swapOffhand() {
    val held = hotbarItems.getOrNull(heldSlot)
    if (heldSlot in hotbarItems.indices) {
      hotbarItems[heldSlot] = offhandItem
    }
    offhandItem = held
    isUsingItem = false
    updateActiveItem()
  }

  @Suppress("LongParameterList")
  fun onBlockPlace(
    face: Int,
    blockClass: Int,
    blockX: Int,
    blockY: Int,
    blockZ: Int,
    cursor: FloatArray,
    insideBlock: Boolean,
    offhand: Boolean,
  ) {
    placesThisTick++
    if (offhand) placeOffhand = true
    placeFace = face
    placeBlockClass = blockClass
    placeBlockX = blockX
    placeBlockY = blockY
    placeBlockZ = blockZ
    placeCursorX = cursor[0]
    placeCursorY = cursor[1]
    placeCursorZ = cursor[2]
    placeInsideBlock = insideBlock
  }

  fun onSequenceBreak() {
    sequenceId++
    usingOffhand = false
    activeItem = ACTIVE_ITEM_NONE
    targetEntityId = -1
    lastTargetEntityId = -1
    firstTickProcessed = false
    lastCaptureAtMillis = 0L
    fallDistance = 0f
    sprinting = false
    sneaking = false
    gliding = false
    swimming = false
    inVehicle = false
    isClimbing = false
    inWater = false
    attackSpeed = 4.0f
    entityInteractionRange = 3.0f
    scale = 1.0f
    gravity = 0.08f
    jumpStrength = 0.42f
    stepHeight = 0.6f
    movementSpeed = 0.1f
    ticksSinceAttack = 255
    cooldownTicks = 255
    health = DEFAULT_HEALTH
    ticksSinceDamage = 255
    damageTakenThisTick = 0.0f
    swungThisTick = false
    attackThisTick = false
    windowStartThisTick = false
    windowStartKind = 0
    damageTakenThisTick = 0.0f
    receivedVelocityThisTick = false
    receivedExplosionThisTick = false
    isSprintingOnAttack = false
    inputForward = false
    inputBackward = false
    inputLeft = false
    inputRight = false
    inputJump = false
    inputShift = false
    inputSprint = false
    inputValid = false
  }

  // Tracks packet arrival timing, not game sequence.
  fun floodTryConsume(nowNanos: Long): Boolean {
    if (floodLastNanos != 0L) {
      val elapsedMs = (nowNanos - floodLastNanos) / 1_000_000.0
      floodCreditsMs = (floodCreditsMs + elapsedMs).coerceAtMost(FLOOD_BURST_MS)
    }
    floodLastNanos = nowNanos
    if (floodCreditsMs < FLOOD_TICK_COST_MS) return false
    floodCreditsMs -= FLOOD_TICK_COST_MS
    return true
  }

  fun buildMovementBitfield(clientOnGround: Boolean): Short {
    var bits = 0
    if (clientOnGround) bits = bits or (1 shl 0)
    if (sprinting) bits = bits or (1 shl 1)
    if (sneaking) bits = bits or (1 shl 2)
    if (flying) bits = bits or (1 shl 4)
    if (swimming) bits = bits or (1 shl 5)
    if (gliding) bits = bits or (1 shl 6)
    if (inVehicle) bits = bits or (1 shl 7)
    if (isClimbing) bits = bits or (1 shl 8)
    if (inWater) bits = bits or (1 shl 9)
    return bits.toShort()
  }

  fun updateActiveItem() {
    if (!isUsingItem) {
      activeItem = ACTIVE_ITEM_NONE
      return
    }
    val item = if (usingOffhand) offhandItem else hotbarItems.getOrNull(heldSlot)
    activeItem =
      when {
        item == null -> ACTIVE_ITEM_OTHER
        item == ItemTypes.SHIELD -> ACTIVE_ITEM_SHIELD
        item == ItemTypes.BOW || item == ItemTypes.CROSSBOW -> ACTIVE_ITEM_RANGED
        item == ItemTypes.TRIDENT -> ACTIVE_ITEM_TRIDENT
        item.hasAttribute(ItemTypes.ItemAttribute.EDIBLE) -> ACTIVE_ITEM_CONSUMABLE
        item == ItemTypes.POTION ||
          item == ItemTypes.MILK_BUCKET ||
          item == ItemTypes.HONEY_BOTTLE -> ACTIVE_ITEM_CONSUMABLE
        else -> ACTIVE_ITEM_OTHER
      }
  }

  companion object {
    const val MELEE_PLAYER_ONLY = 1

    const val DEFAULT_HEALTH = 20.0f
    const val HOTBAR_SIZE = 9
    const val ACTIVE_ITEM_NONE: Short = 0
    const val ACTIVE_ITEM_SHIELD: Short = 1
    const val ACTIVE_ITEM_CONSUMABLE: Short = 2
    const val ACTIVE_ITEM_RANGED: Short = 3
    const val ACTIVE_ITEM_TRIDENT: Short = 4
    const val ACTIVE_ITEM_OTHER: Short = 5
    private const val FLOOD_TICK_COST_MS = 50.0
    private const val FLOOD_BURST_MS = 1000.0
  }
}
