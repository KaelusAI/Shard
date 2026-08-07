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

import ac.shard.checks.impl.combat.AimProcessor
import ac.shard.entity.PacketEntity
import ac.shard.player.ShardPlayer
import ac.shard.player.state.TrackingState
import ac.shard.utils.math.SimpleCollisionBox
import ac.shard.utils.math.Vector3dm
import ac.shard.utils.nmsutil.GetBoundingBox
import ac.shard.utils.nmsutil.ReachUtils
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.type.ItemType
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion

@Suppress("TooManyFunctions")
class TickData {

  var sequenceId: Int = 0
  var tickIndex: Long = 0
  var dtMs: Float = 0f
  var targetEntityId: Int = -1

  var yaw: Float = 0f
  var pitch: Float = 0f

  var x: Double = 0.0
  var y: Double = 0.0
  var z: Double = 0.0
  var deltaY: Double = 0.0

  var movementBitfield: Short = 0

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

  var targetValid: Boolean = false
  var targetDistance: Float = 0f
  var targetAimAngle: Float = 0f
  var targetRelX: Float = 0f
  var targetRelY: Float = 0f
  var targetRelZ: Float = 0f
  var ticksToAttack: Short = 0
  var attackThisTick: Boolean = false
  var aimError: Float = 0f
  var sameTargetAsPrevAttack: Boolean = false
  var targetsInRangeCount: Int = 0
  var isCritical: Boolean = false
  var raytraceHit: Boolean = false
  var attackRayOccluded: Boolean = false
  var raytraceMissDistance: Float = 0f
  var hitPointRelX: Float = 0f
  var hitPointRelY: Float = 0f
  var hitPointRelZ: Float = 0f

  var ticksSinceAttack: Int = 255
  var swungThisTick: Boolean = false
  var attackCooldownProgress: Float = 0f
  private var serverCooldownProgress: Float = 1f
  private val aimAxes = DoubleArray(2)
  var attackSpeed: Float = 4.0f
  var isSprintingOnAttack: Boolean = false

  var receivedVelocityThisTick: Boolean = false
  var velocityX: Float = 0f
  var velocityY: Float = 0f
  var velocityZ: Float = 0f
  var ticksSinceVelocity: Int = 255
  var receivedExplosionThisTick: Boolean = false
  var ticksSinceExplosion: Int = 255
  var ticksSinceUseItem: Int = 255
  var activeItem: Short = 0

  var rotationPresent: Boolean = false
  var modeYaw: Float = 0f
  var modePitch: Float = 0f
  var modeYawValid: Boolean = false
  var modePitchValid: Boolean = false
  var deltaDotsYaw: Float = 0f
  var deltaDotsPitch: Float = 0f

  var activeFireworkCount: Int = 0
  var teleportTick: Boolean = false
  var exposureFraction: Float = 0f
  var boxInflation: Float = 0f

  var stuckMultX: Float = 1.0f
  var stuckMultY: Float = 1.0f
  var stuckMultZ: Float = 1.0f

  var onSlime: Boolean = false
  var onHoney: Boolean = false
  var onSoulSand: Boolean = false
  var onMud: Boolean = false

  var levitationAmplifier: Int = -1
  var swimFriction: Float = 0.8f
  var riptideActive: Boolean = false

  var windowStartKind: Short = START_MELEE_PLAYER
  var targetType: Short = TARGET_PLAYER

  var crystalSpawnToAttack: Short = NO_TICKS
  var useOffhand: Boolean = false
  var nearbyCrystalCount: Int = 0
  var anchorCharge: Short = NO_CHARGE
  var anchorUseInterval: Short = NO_TICKS

  var placeFace: Short = NO_FACE
  var placeBlockClass: Short = PLACE_NONE
  var placeBlockX: Int = 0
  var placeBlockY: Int = 0
  var placeBlockZ: Int = 0
  var placeCursorX: Float = NO_CURSOR
  var placeCursorY: Float = NO_CURSOR
  var placeCursorZ: Float = NO_CURSOR
  var placeInsideBlock: Boolean = false
  var placesThisTick: Short = 0

  var heldItemClass: Short = ITEM_UNKNOWN
  var offhandItemClass: Short = ITEM_UNKNOWN
  var slotSwitchesThisTick: Short = 0

  var explosionKbX: Float = 0f
  var explosionKbY: Float = 0f
  var explosionKbZ: Float = 0f

  var hittableEntitiesCount: Int = 0
  var aimErrorYaw: Float = 0f
  var aimErrorPitch: Float = 0f
  var raytraceDirsHitCount: Short = 0
  var raytraceHitStrict: Boolean = false

  fun capture(player: ShardPlayer) {
    val m = player.movement
    val tracking = player.tracking
    val aim = player.checkManager.getCheck(AimProcessor::class.java)

    sequenceId = tracking.sequenceId
    tickIndex = tracking.tickIndex

    val nowMillis = System.currentTimeMillis()
    dtMs =
      if (tracking.lastCaptureAtMillis == 0L) 0f
      else (nowMillis - tracking.lastCaptureAtMillis).coerceIn(0L, DT_MS_MAX).toFloat()
    tracking.lastCaptureAtMillis = nowMillis
    targetEntityId = tracking.targetEntityId

    yaw = m.yaw
    pitch = m.pitch
    x = m.x
    y = m.y
    z = m.z

    teleportTick = !tracking.firstTickProcessed
    if (tracking.firstTickProcessed) {
      deltaY = m.y - tracking.lastY
    } else {
      deltaY = 0.0
      tracking.firstTickProcessed = true
      tracking.fallDistance = 0f
      aim?.reset()
    }
    tracking.lastY = m.y

    movementBitfield = tracking.buildMovementBitfield(player.packetStateData.packetPlayerOnGround)

    pose = tracking.pose

    inputForward = tracking.inputForward
    inputBackward = tracking.inputBackward
    inputLeft = tracking.inputLeft
    inputRight = tracking.inputRight
    inputJump = tracking.inputJump
    inputShift = tracking.inputShift
    inputSprint = tracking.inputSprint
    inputValid = tracking.inputValid

    rotationPresent = tracking.rotationThisTick

    activeItem = tracking.activeItem
    gameMode = tracking.gameMode
    foodLevel = tracking.foodLevel
    health = tracking.health
    damageTakenThisTick = tracking.damageTakenThisTick
    ticksSinceDamage = tracking.ticksSinceDamage
    groundFriction = tracking.groundFriction
    movementSpeed = tracking.movementSpeed

    updateFallDistance(player, tracking, deltaY)
    fallDistance = tracking.fallDistance

    jumpAmplifier = tracking.jumpAmplifier
    slowFalling = tracking.slowFalling
    playerPing = tracking.playerPing

    speedAmplifier = tracking.speedAmplifier
    slownessAmplifier = tracking.slownessAmplifier
    hasteAmplifier = tracking.hasteAmplifier
    miningFatigueAmplifier = tracking.miningFatigueAmplifier

    entityInteractionRange = tracking.entityInteractionRange
    scale = tracking.scale
    gravity = tracking.gravity
    jumpStrength = tracking.jumpStrength
    stepHeight = tracking.stepHeight

    captureAttackCooldown(player, tracking)
    captureCombatContext(player, tracking)

    attackThisTick = tracking.attackThisTick
    ticksSinceAttack = tracking.ticksSinceAttack
    swungThisTick = tracking.swungThisTick
    isSprintingOnAttack = tracking.isSprintingOnAttack
    attackSpeed = tracking.attackSpeed

    receivedVelocityThisTick = tracking.receivedVelocityThisTick
    if (tracking.receivedVelocityThisTick) {
      velocityX = tracking.velocityX
      velocityY = tracking.velocityY
      velocityZ = tracking.velocityZ
    } else {
      velocityX = 0f
      velocityY = 0f
      velocityZ = 0f
    }
    ticksSinceVelocity = tracking.ticksSinceVelocity
    receivedExplosionThisTick = tracking.receivedExplosionThisTick
    ticksSinceExplosion = tracking.ticksSinceExplosion
    ticksSinceUseItem = tracking.ticksSinceUseItem

    if (aim != null) {
      modeYaw = aim.modeX.toFloat()
      modePitch = aim.modeY.toFloat()
      modeYawValid = aim.modeYawValid
      modePitchValid = aim.modePitchValid
      val dots = aim.consumeDots()
      deltaDotsYaw = if (aim.modeYawValid) dots.yaw else 0f
      deltaDotsPitch = if (aim.modePitchValid) dots.pitch else 0f
    } else {
      modeYaw = 0f
      modePitch = 0f
      modeYawValid = false
      modePitchValid = false
      deltaDotsYaw = 0f
      deltaDotsPitch = 0f
    }

    activeFireworkCount = player.compensatedFireworks.count()

    stuckMultX = tracking.stuckMultX
    stuckMultY = tracking.stuckMultY
    stuckMultZ = tracking.stuckMultZ

    onSlime = tracking.onSlime
    onHoney = tracking.onHoney
    onSoulSand = tracking.onSoulSand
    onMud = tracking.onMud

    levitationAmplifier = tracking.levitationAmplifier
    swimFriction = computeSwimFriction(player, tracking)
    riptideActive = tracking.riptideActive

    captureCrystalContext(player, tracking)
  }

  private fun captureCrystalContext(player: ShardPlayer, tracking: TrackingState) {
    windowStartKind = START_MELEE_PLAYER

    crystalSpawnToAttack = tracking.crystalSpawnToAttack.toShort()
    useOffhand = tracking.placeOffhand || (tracking.isUsingItem && tracking.usingOffhand)
    anchorCharge = tracking.anchorCharge.toShort()
    anchorUseInterval = tracking.anchorUseInterval.toShort()

    placeFace = tracking.placeFace.toShort()
    placeBlockClass = tracking.placeBlockClass.toShort()
    placeBlockX = tracking.placeBlockX
    placeBlockY = tracking.placeBlockY
    placeBlockZ = tracking.placeBlockZ
    placeCursorX = tracking.placeCursorX
    placeCursorY = tracking.placeCursorY
    placeCursorZ = tracking.placeCursorZ
    placeInsideBlock = tracking.placeInsideBlock
    placesThisTick = tracking.placesThisTick.toShort()

    targetType = tracking.attackTargetType
    heldItemClass = classifyItem(tracking.hotbarItems.getOrNull(tracking.heldSlot))
    offhandItemClass = classifyItem(tracking.offhandItem)
    slotSwitchesThisTick = tracking.slotSwitchesThisTick.toShort()

    if (tracking.receivedExplosionThisTick) {
      explosionKbX = tracking.explosionKbX
      explosionKbY = tracking.explosionKbY
      explosionKbZ = tracking.explosionKbZ
    } else {
      explosionKbX = 0f
      explosionKbY = 0f
      explosionKbZ = 0f
    }

    var crystals = 0
    var hittable = 0
    for (entity in player.compensatedEntities.entityMap.values) {
      if (!entity.canHit()) continue
      val pos = entity.trackedServerPosition.pos
      val dx = pos.x - player.movement.x
      val dy = pos.y - player.movement.y
      val dz = pos.z - player.movement.z
      val distSq = dx * dx + dy * dy + dz * dz
      if (distSq <= TARGETS_IN_RANGE_RADIUS_SQ) hittable++
      if (entity.type == EntityTypes.END_CRYSTAL && distSq <= CRYSTAL_RADIUS_SQ) crystals++
    }
    nearbyCrystalCount = crystals
    hittableEntitiesCount = hittable
  }

  private fun computeSwimFriction(player: ShardPlayer, tracking: TrackingState): Float {
    val isNew = player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_13)
    var friction = if (tracking.sprinting && isNew) SWIM_FRICTION_SPRINT else SWIM_FRICTION_BASE

    var efficiency = tracking.waterMovementEfficiency
    if (!player.packetStateData.packetPlayerOnGround) efficiency *= 0.5f
    if (efficiency > 0f) friction += (DEPTH_STRIDER_FRICTION - friction) * efficiency

    if (tracking.dolphinsGrace) friction = DOLPHINS_GRACE_FRICTION
    return friction
  }

  private fun captureCombatContext(player: ShardPlayer, tracking: TrackingState) {
    val targetId = tracking.targetEntityId
    val entity: PacketEntity? =
      if (targetId >= 0) {
        player.compensatedEntities.getEntity(targetId)
      } else {
        null
      }

    if (entity != null && entity.canHit()) {
      targetValid = true
      sameTargetAsPrevAttack = tracking.targetEntityId == tracking.lastTargetEntityId

      val targetBox = entity.getPossibleCollisionBoxes()
      targetDistance = ReachUtils.getMinReachToBox(player, targetBox).toFloat()
      targetAimAngle = ReachUtils.getMinAngleToBox(player, targetBox).toFloat()

      val targetPos = entity.trackedServerPosition.pos
      targetRelX = (targetPos.x - player.movement.x).toFloat()
      targetRelY = (targetPos.y - player.movement.y).toFloat()
      targetRelZ = (targetPos.z - player.movement.z).toFloat()

      val tightBox =
        GetBoundingBox.getPacketEntityBoundingBox(targetPos.x, targetPos.y, targetPos.z, entity)
      boxInflation = ReachUtils.boxInflationExtent(targetBox, tightBox).toFloat().coerceAtLeast(0f)
      aimError = ReachUtils.aimError(player, tightBox).toFloat()

      if (tracking.attackThisTick) {
        val hitPointUnion = computeRaytraceHitPoint(player, targetBox)
        raytraceHit = hitPointUnion != null
        val hitPointTight = computeRaytraceHitPoint(player, tightBox)
        raytraceHitStrict = hitPointTight != null
        raytraceDirsHitCount = countRaytraceHits(player, targetBox).toShort()
        ReachUtils.aimErrorAxes(player, tightBox, aimAxes)
        aimErrorYaw = aimAxes[0].toFloat()
        aimErrorPitch = aimAxes[1].toFloat()
        raytraceMissDistance =
          if (hitPointTight != null) 0f else computeRaytraceMissDistance(player, tightBox).toFloat()
        if (hitPointTight != null) {
          val boxCenterX = (tightBox.minX + tightBox.maxX) * 0.5
          val boxCenterY = (tightBox.minY + tightBox.maxY) * 0.5
          val boxCenterZ = (tightBox.minZ + tightBox.maxZ) * 0.5
          hitPointRelX = (hitPointTight.x - boxCenterX).toFloat()
          hitPointRelY = (hitPointTight.y - boxCenterY).toFloat()
          hitPointRelZ = (hitPointTight.z - boxCenterZ).toFloat()
        } else {
          hitPointRelX = 0f
          hitPointRelY = 0f
          hitPointRelZ = 0f
        }
        isCritical = computeIsCritical(player, tracking)
        attackRayOccluded = computeAttackRayOccluded(player, targetBox)
        exposureFraction = ReachUtils.exposureFraction(player, tightBox)
      } else {
        raytraceHit = false
        raytraceMissDistance = 0f
        hitPointRelX = 0f
        hitPointRelY = 0f
        hitPointRelZ = 0f
        isCritical = false
        attackRayOccluded = false
        exposureFraction = 0f
        raytraceHitStrict = false
        raytraceDirsHitCount = 0
        aimErrorYaw = 0f
        aimErrorPitch = 0f
      }

      targetsInRangeCount = countTargetsInRange(player)
    } else {
      targetValid = false
      sameTargetAsPrevAttack = false
      targetDistance = 0f
      targetAimAngle = 0f
      targetRelX = 0f
      targetRelY = 0f
      targetRelZ = 0f
      raytraceHit = false
      raytraceMissDistance = 0f
      hitPointRelX = 0f
      hitPointRelY = 0f
      hitPointRelZ = 0f
      isCritical = false
      attackRayOccluded = false
      exposureFraction = 0f
      boxInflation = 0f
      aimError = 0f
      targetsInRangeCount = 0
      raytraceHitStrict = false
      raytraceDirsHitCount = 0
      aimErrorYaw = 0f
      aimErrorPitch = 0f
    }
  }

  private fun computeAttackRayOccluded(
    player: ShardPlayer,
    targetBox: SimpleCollisionBox,
  ): Boolean {
    return ReachUtils.isRayOccluded(
      player,
      ReachUtils.eyeOrigin(player),
      (targetBox.minX + targetBox.maxX) * 0.5,
      (targetBox.minY + targetBox.maxY) * 0.5,
      (targetBox.minZ + targetBox.maxZ) * 0.5,
    )
  }

  private fun captureAttackCooldown(player: ShardPlayer, tracking: TrackingState) {
    val isLegacyCombat =
      !modernCombatServer || player.user.clientVersion.isOlderThan(ClientVersion.V_1_9)
    serverCooldownProgress =
      if (tracking.attackThisTick) {
        tracking.preAttackCooldownProgress
      } else {
        val cooldownPeriod = TICKS_PER_SECOND / tracking.attackSpeed
        ((tracking.cooldownTicks + 0.5f) / cooldownPeriod).coerceIn(0f, 1f)
      }
    attackCooldownProgress = if (isLegacyCombat) 0f else serverCooldownProgress
  }

  private fun countRaytraceHits(player: ShardPlayer, targetBox: SimpleCollisionBox): Int {
    val maxDistance = raytraceMaxDistance(player)
    val m = player.movement
    var hits = 0
    for (lookVec in ReachUtils.getPossibleLookDirs(player)) {
      for (eyeHeight in ReachUtils.getPossibleEyeHeights()) {
        val origin = Vector3dm(m.x, m.y + eyeHeight, m.z)
        if (ReachUtils.isVecInside(targetBox, origin)) {
          hits++
          continue
        }
        val end =
          Vector3dm(
            origin.x + lookVec.x * maxDistance,
            origin.y + lookVec.y * maxDistance,
            origin.z + lookVec.z * maxDistance,
          )
        if (ReachUtils.calculateIntercept(targetBox, origin, end) != null) hits++
      }
    }
    return hits
  }

  private fun computeRaytraceHitPoint(
    player: ShardPlayer,
    targetBox: SimpleCollisionBox,
  ): Vector3dm? {
    val lookDirs = ReachUtils.getPossibleLookDirs(player)
    val eyeHeights = ReachUtils.getPossibleEyeHeights()
    val maxDistance = raytraceMaxDistance(player)

    for (lookVec in lookDirs) {
      for (eyeHeight in eyeHeights) {
        val m = player.movement
        val origin = Vector3dm(m.x, m.y + eyeHeight, m.z)
        if (ReachUtils.isVecInside(targetBox, origin)) return origin
        val end =
          Vector3dm(
            origin.x + lookVec.x * maxDistance,
            origin.y + lookVec.y * maxDistance,
            origin.z + lookVec.z * maxDistance,
          )
        val intercept = ReachUtils.calculateIntercept(targetBox, origin, end)
        if (intercept != null) return intercept
      }
    }
    return null
  }

  private fun computeRaytraceMissDistance(
    player: ShardPlayer,
    targetBox: SimpleCollisionBox,
  ): Double {
    val lookDirs = ReachUtils.getPossibleLookDirs(player)
    val eyeHeights = ReachUtils.getPossibleEyeHeights()
    val maxDistance = raytraceMaxDistance(player)
    var minDist = Double.MAX_VALUE
    for (lookVec in lookDirs) {
      for (eye in eyeHeights) {
        val m = player.movement
        val origin = Vector3dm(m.x, m.y + eye, m.z)
        val d = ReachUtils.minDistanceRayToBox(origin, lookVec, maxDistance, targetBox)
        if (d < minDist) minDist = d
        if (minDist == 0.0) return 0.0
      }
    }
    return minDist
  }

  private fun raytraceMaxDistance(player: ShardPlayer): Double =
    (player.tracking.entityInteractionRange + RAYTRACE_RANGE_MARGIN).coerceAtLeast(
      RAYTRACE_MIN_RANGE
    )

  private fun computeIsCritical(player: ShardPlayer, tracking: TrackingState): Boolean {
    if (serverCooldownProgress <= CRIT_COOLDOWN_THRESHOLD) return false
    if (tracking.fallDistance <= 0f) return false
    if (player.packetStateData.packetPlayerOnGround) return false
    if (tracking.isClimbing) return false
    if (tracking.inWater) return false
    if (tracking.inVehicle) return false
    if (tracking.sprinting) return false
    if (tracking.hasBlindness) return false
    return true
  }

  private fun classifyItem(item: ItemType?): Short {
    if (item == null || item == ItemTypes.AIR) return ITEM_EMPTY
    val exact = classifyExactItem(item)
    return when {
      exact != ITEM_UNKNOWN -> exact
      item.hasAttribute(ItemTypes.ItemAttribute.SWORD) ||
        item.hasAttribute(ItemTypes.ItemAttribute.AXE) -> ITEM_MELEE_WEAPON
      item.hasAttribute(ItemTypes.ItemAttribute.EDIBLE) -> ITEM_CONSUMABLE
      item.placedType != null -> ITEM_PLACEABLE_BLOCK
      else -> ITEM_UNKNOWN
    }
  }

  private fun classifyExactItem(item: ItemType): Short =
    when (item) {
      ItemTypes.END_CRYSTAL -> ITEM_END_CRYSTAL
      ItemTypes.RESPAWN_ANCHOR -> ITEM_RESPAWN_ANCHOR
      ItemTypes.GLOWSTONE -> ITEM_GLOWSTONE
      ItemTypes.TOTEM_OF_UNDYING -> ITEM_TOTEM
      ItemTypes.SHIELD -> ITEM_SHIELD
      ItemTypes.OBSIDIAN,
      ItemTypes.CRYING_OBSIDIAN,
      ItemTypes.BEDROCK -> ITEM_CRYSTAL_BASE
      ItemTypes.BOW,
      ItemTypes.CROSSBOW,
      ItemTypes.TRIDENT -> ITEM_RANGED
      ItemTypes.ENDER_PEARL,
      ItemTypes.SNOWBALL,
      ItemTypes.EGG -> ITEM_PROJECTILE
      else -> ITEM_UNKNOWN
    }

  private fun countTargetsInRange(player: ShardPlayer): Int {
    var count = 0
    for (entity in player.compensatedEntities.entityMap.values) {
      if (!entity.isLivingEntity || !entity.canHit()) continue
      val pos = entity.trackedServerPosition.pos
      val dx = pos.x - player.movement.x
      val dy = pos.y - player.movement.y
      val dz = pos.z - player.movement.z
      if (dx * dx + dy * dy + dz * dz <= TARGETS_IN_RANGE_RADIUS_SQ) count++
    }
    return count
  }

  companion object {
    const val START_MELEE_PLAYER: Short = 0
    const val START_MELEE_LIVING_OTHER: Short = 1
    const val START_ATTACK_END_CRYSTAL: Short = 2
    const val START_ATTACK_ENTITY_OTHER: Short = 3
    const val START_USE_RESPAWN_ANCHOR: Short = 4
    const val START_PLACE_END_CRYSTAL: Short = 5
    const val START_EXPLOSION_RECEIVED: Short = 6

    const val TARGET_PLAYER: Short = 0
    const val TARGET_LIVING_OTHER: Short = 1
    const val TARGET_END_CRYSTAL: Short = 2
    const val TARGET_VEHICLE: Short = 3
    const val TARGET_OTHER: Short = 4
    const val TARGET_UNKNOWN: Short = 5

    const val ITEM_UNKNOWN: Short = 0
    const val ITEM_EMPTY: Short = 1
    const val ITEM_MELEE_WEAPON: Short = 2
    const val ITEM_END_CRYSTAL: Short = 3
    const val ITEM_CRYSTAL_BASE: Short = 4
    const val ITEM_RESPAWN_ANCHOR: Short = 5
    const val ITEM_GLOWSTONE: Short = 6
    const val ITEM_TOTEM: Short = 7
    const val ITEM_PLACEABLE_BLOCK: Short = 8
    const val ITEM_RANGED: Short = 9
    const val ITEM_PROJECTILE: Short = 10
    const val ITEM_CONSUMABLE: Short = 11
    const val ITEM_SHIELD: Short = 12

    const val PLACE_NONE: Short = 0
    const val PLACE_OBSIDIAN: Short = 1
    const val PLACE_BEDROCK: Short = 2
    const val PLACE_RESPAWN_ANCHOR: Short = 3
    const val PLACE_OTHER_SOLID: Short = 4
    const val PLACE_UNKNOWN: Short = 5

    const val NO_TICKS: Short = -1
    const val NO_CHARGE: Short = -1
    const val NO_FACE: Short = -1
    const val NO_CURSOR = -1f

    private val modernCombatServer by lazy {
      PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_9)
    }
    private const val DEFAULT_HEALTH = 20.0f
    private const val SLOW_FALL_CUTOFF = -0.5
    private const val SLOW_FALL_CLAMP = 1.0f

    private fun updateFallDistance(player: ShardPlayer, tracking: TrackingState, deltaY: Double) {
      if (player.packetStateData.packetPlayerOnGround || tracking.isClimbing || tracking.inWater) {
        tracking.fallDistance = 0f
      } else if (deltaY < 0) {
        tracking.fallDistance += (-deltaY).toFloat()
      }
      if (
        tracking.gliding && deltaY > SLOW_FALL_CUTOFF && tracking.fallDistance > SLOW_FALL_CLAMP
      ) {
        tracking.fallDistance = SLOW_FALL_CLAMP
      }
      if (tracking.slowFalling || tracking.levitationAmplifier >= 0) {
        tracking.fallDistance = 0f
      }
    }

    private const val SWIM_FRICTION_BASE = 0.8f
    private const val SWIM_FRICTION_SPRINT = 0.9f
    private const val DEPTH_STRIDER_FRICTION = 0.546f
    private const val DOLPHINS_GRACE_FRICTION = 0.96f
    private const val TICKS_PER_SECOND = 20.0f
    private const val CRYSTAL_RADIUS_SQ = 64.0
    private const val RAYTRACE_RANGE_MARGIN = 3.0
    private const val RAYTRACE_MIN_RANGE = 6.0
    private const val CRIT_COOLDOWN_THRESHOLD = 0.9f
    private const val TARGETS_IN_RANGE_RADIUS_SQ = 36.0
    private const val DT_MS_MAX = 10_000L
  }
}
