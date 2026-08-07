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

import java.nio.ByteBuffer

object TickSchema {

  @Suppress("MagicNumber")
  enum class WireType(val size: Int) {
    BOOL(1),
    I16(2),
    I32(4),
    F32(4),
    F64(8),
  }

  class Field(
    val name: String,
    val writeCsv: (TickData, Appendable) -> Unit,
    val rawFloat: (TickData) -> Float,
    val wireType: WireType,
    val writeRaw: (TickData, ByteBuffer) -> Unit,
  )

  val fields: List<Field> =
    listOf(
      i32("sequence_id") { it.sequenceId },
      i32("tick_index") { it.tickIndex.toInt() },
      f32("dt_ms") { it.dtMs },
      i32("target_entity_id") { it.targetEntityId },
      f32hp("yaw") { it.yaw },
      f32hp("pitch") { it.pitch },
      f64("x") { it.x },
      f64("y") { it.y },
      f64("z") { it.z },
      bit("on_ground", 0),
      bit("sprinting", 1),
      bit("sneaking", 2),
      i16("active_item") { it.activeItem },
      bit("flying", 4),
      bit("swimming", 5),
      bit("gliding", 6),
      bit("in_vehicle", 7),
      bit("is_climbing", 8),
      bit("in_water", 9),
      i32("pose") { it.pose },
      bool("input_forward") { it.inputForward },
      bool("input_backward") { it.inputBackward },
      bool("input_left") { it.inputLeft },
      bool("input_right") { it.inputRight },
      bool("input_jump") { it.inputJump },
      bool("input_shift") { it.inputShift },
      bool("input_sprint") { it.inputSprint },
      i32("game_mode") { it.gameMode },
      i32("food_level") { it.foodLevel },
      f32("ground_friction") { it.groundFriction },
      f32("fall_distance") { it.fallDistance },
      f32("movement_speed") { it.movementSpeed },
      i32("jump_amplifier") { it.jumpAmplifier },
      bool("slow_falling") { it.slowFalling },
      i32("player_ping") { it.playerPing },
      i32("speed_amplifier") { it.speedAmplifier },
      i32("slowness_amplifier") { it.slownessAmplifier },
      i32("haste_amplifier") { it.hasteAmplifier },
      i32("mining_fatigue_amplifier") { it.miningFatigueAmplifier },
      f32("entity_interaction_range") { it.entityInteractionRange },
      f32("scale") { it.scale },
      f32("gravity") { it.gravity },
      f32("jump_strength") { it.jumpStrength },
      f32("step_height") { it.stepHeight },
      bool("target_valid") { it.targetValid },
      f32("target_distance") { it.targetDistance },
      f32("target_aim_angle") { it.targetAimAngle },
      f32("target_rel_x") { it.targetRelX },
      f32("target_rel_y") { it.targetRelY },
      f32("target_rel_z") { it.targetRelZ },
      i16("ticks_to_attack") { it.ticksToAttack },
      bool("attack_this_tick") { it.attackThisTick },
      f32("aim_error") { it.aimError },
      bool("same_target_as_prev_attack") { it.sameTargetAsPrevAttack },
      i32("targets_in_range_count") { it.targetsInRangeCount },
      bool("is_critical") { it.isCritical },
      bool("raytrace_hit") { it.raytraceHit },
      bool("attack_ray_occluded") { it.attackRayOccluded },
      f32("raytrace_miss_distance") { it.raytraceMissDistance },
      bool("input_valid") { it.inputValid },
      f32("hit_point_rel_x") { it.hitPointRelX },
      f32("hit_point_rel_y") { it.hitPointRelY },
      f32("hit_point_rel_z") { it.hitPointRelZ },
      i32("ticks_since_attack") { it.ticksSinceAttack },
      bool("swung_this_tick") { it.swungThisTick },
      f32("attack_cooldown_progress") { it.attackCooldownProgress },
      f32("attack_speed") { it.attackSpeed },
      bool("is_sprinting_on_attack") { it.isSprintingOnAttack },
      bool("received_velocity_this_tick") { it.receivedVelocityThisTick },
      f32("velocity_x") { it.velocityX },
      f32("velocity_y") { it.velocityY },
      f32("velocity_z") { it.velocityZ },
      i32("ticks_since_velocity") { it.ticksSinceVelocity },
      bool("received_explosion_this_tick") { it.receivedExplosionThisTick },
      i32("ticks_since_explosion") { it.ticksSinceExplosion },
      i32("ticks_since_use_item") { it.ticksSinceUseItem },
      bool("rotation_present") { it.rotationPresent },
      f32("mode_yaw") { it.modeYaw },
      f32("mode_pitch") { it.modePitch },
      bool("mode_yaw_valid") { it.modeYawValid },
      bool("mode_pitch_valid") { it.modePitchValid },
      f32("delta_dots_yaw") { it.deltaDotsYaw },
      f32("delta_dots_pitch") { it.deltaDotsPitch },
      i32("active_firework_count") { it.activeFireworkCount },
      f32("stuck_mult_x") { it.stuckMultX },
      f32("stuck_mult_y") { it.stuckMultY },
      f32("stuck_mult_z") { it.stuckMultZ },
      bool("on_slime") { it.onSlime },
      bool("on_honey") { it.onHoney },
      bool("on_soul_sand") { it.onSoulSand },
      bool("on_mud") { it.onMud },
      i32("levitation_amplifier") { it.levitationAmplifier },
      f32("swim_friction") { it.swimFriction },
      bool("riptide_active") { it.riptideActive },
      bool("teleport_tick") { it.teleportTick },
      f32("exposure_fraction") { it.exposureFraction },
      f32("box_inflation") { it.boxInflation },
      f32("health") { it.health },
      f32("damage_taken_this_tick") { it.damageTakenThisTick },
      i32("ticks_since_damage") { it.ticksSinceDamage },
      i16("window_start") { it.windowStartKind },
      i16("target_type") { it.targetType },
      i16("crystal_spawn_to_attack") { it.crystalSpawnToAttack },
      bool("use_offhand") { it.useOffhand },
      i32("nearby_crystal_count") { it.nearbyCrystalCount },
      i16("anchor_charge") { it.anchorCharge },
      i16("anchor_use_interval") { it.anchorUseInterval },
      i16("place_face") { it.placeFace },
      i16("place_block_class") { it.placeBlockClass },
      i32("place_block_x") { it.placeBlockX },
      i32("place_block_y") { it.placeBlockY },
      i32("place_block_z") { it.placeBlockZ },
      f32("place_cursor_x") { it.placeCursorX },
      f32("place_cursor_y") { it.placeCursorY },
      f32("place_cursor_z") { it.placeCursorZ },
      bool("place_inside_block") { it.placeInsideBlock },
      i16("places_this_tick") { it.placesThisTick },
      i16("held_item_class") { it.heldItemClass },
      i16("offhand_item_class") { it.offhandItemClass },
      i16("slot_switches_this_tick") { it.slotSwitchesThisTick },
      f32("explosion_kb_x") { it.explosionKbX },
      f32("explosion_kb_y") { it.explosionKbY },
      f32("explosion_kb_z") { it.explosionKbZ },
      i32("hittable_entities_count") { it.hittableEntitiesCount },
      f32("aim_error_yaw") { it.aimErrorYaw },
      f32("aim_error_pitch") { it.aimErrorPitch },
      i16("raytrace_dirs_hit_count") { it.raytraceDirsHitCount },
      bool("raytrace_hit_strict") { it.raytraceHitStrict },
    )

  val csvHeader: String = fields.joinToString(",") { it.name }

  val rowSize: Int = fields.sumOf { it.wireType.size }

  val fieldNames: List<String> = fields.map { it.name }

  private val indexByName: Map<String, Int> =
    fields.withIndex().associate { it.value.name to it.index }

  const val MASK_WORDS = 2
  const val MASK_BYTES = MASK_WORDS * Long.SIZE_BYTES

  init {
    require(fields.size <= MASK_BYTES * Byte.SIZE_BITS) {
      "TickSchema has ${fields.size} fields, wire mask holds ${MASK_BYTES * Byte.SIZE_BITS}"
    }
  }

  val fullMask: LongArray = maskOfIndices(fields.indices)

  fun maskOf(names: Collection<String>): LongArray? {
    val indices = names.map { indexByName[it] ?: return null }
    return maskOfIndices(indices)
  }

  fun namesOf(mask: LongArray): List<String> =
    fields.filterIndexed { i, _ -> mask.hasBit(i) }.map { it.name }

  fun columnCount(mask: LongArray): Int = mask.sumOf { java.lang.Long.bitCount(it) }

  fun rowSizeFor(mask: LongArray): Int =
    fields.withIndex().sumOf { (i, f) -> if (mask.hasBit(i)) f.wireType.size else 0 }

  fun packColumnsRaw(buf: ByteBuffer, ticks: List<TickData>, mask: LongArray = fullMask) {
    for ((i, f) in fields.withIndex()) {
      if (!mask.hasBit(i)) continue
      for (tick in ticks) f.writeRaw(tick, buf)
    }
  }

  private fun maskOfIndices(indices: Iterable<Int>): LongArray {
    val mask = LongArray(MASK_WORDS)
    for (i in indices) mask[i / Long.SIZE_BITS] =
      mask[i / Long.SIZE_BITS] or (1L shl (i % Long.SIZE_BITS))
    return mask
  }

  private fun LongArray.hasBit(index: Int): Boolean =
    (this[index / Long.SIZE_BITS] shr (index % Long.SIZE_BITS)) and 1L == 1L

  fun appendCsvRow(out: Appendable, tick: TickData) {
    for (i in fields.indices) {
      if (i > 0) out.append(',')
      fields[i].writeCsv(tick, out)
    }
  }

  private const val FLOAT_SCALE = 1_000_000L
  private const val DOUBLE_SCALE = 10_000_000_000L

  private fun i32(name: String, get: (TickData) -> Int) =
    Field(
      name,
      { t, o -> o.append(get(t).toString()) },
      { t -> get(t).toFloat() },
      WireType.I32,
      { t, b -> b.putInt(get(t)) },
    )

  private fun i16(name: String, get: (TickData) -> Short) =
    Field(
      name,
      { t, o -> o.append(get(t).toString()) },
      { t -> get(t).toFloat() },
      WireType.I16,
      { t, b -> b.putShort(get(t)) },
    )

  private fun f32(name: String, get: (TickData) -> Float) =
    Field(
      name,
      { t, o -> appendFloat(o, get(t)) },
      { t -> get(t) },
      WireType.F32,
      { t, b -> b.putFloat(get(t)) },
    )

  private fun f32hp(name: String, get: (TickData) -> Float) =
    Field(
      name,
      { t, o -> appendDouble(o, get(t).toDouble()) },
      { t -> get(t) },
      WireType.F32,
      { t, b -> b.putFloat(get(t)) },
    )

  private fun f64(name: String, get: (TickData) -> Double) =
    Field(
      name,
      { t, o -> appendDouble(o, get(t)) },
      { t -> get(t).toFloat() },
      WireType.F64,
      { t, b -> b.putDouble(get(t)) },
    )

  private fun bool(name: String, get: (TickData) -> Boolean) =
    Field(
      name,
      { t, o -> o.append(if (get(t)) '1' else '0') },
      { t -> if (get(t)) 1f else 0f },
      WireType.BOOL,
      { t, b -> b.put(if (get(t)) 1 else 0) },
    )

  private fun bit(name: String, index: Int) =
    Field(
      name,
      { t, o -> o.append(if ((t.movementBitfield.toInt() shr index) and 1 == 1) '1' else '0') },
      { t -> if ((t.movementBitfield.toInt() shr index) and 1 == 1) 1f else 0f },
      WireType.BOOL,
      { t, b -> b.put(if ((t.movementBitfield.toInt() shr index) and 1 == 1) 1 else 0) },
    )

  private fun appendFloat(out: Appendable, value: Float) {
    if (!value.isFinite()) {
      out.append("0.000000")
      return
    }
    var v = value.toDouble()
    val negative = v < 0.0
    if (negative) v = -v
    val scaled = (v * FLOAT_SCALE + 0.5).toLong()
    val intPart = scaled / FLOAT_SCALE
    val fracPart = (scaled % FLOAT_SCALE).toInt()
    if (negative && scaled > 0) out.append('-')
    out.append(intPart.toString())
    out.append('.')
    val fracText = fracPart.toString()
    repeat(6 - fracText.length) { out.append('0') }
    out.append(fracText)
  }

  private fun appendDouble(out: Appendable, value: Double) {
    if (!value.isFinite()) {
      out.append("0.0000000000")
      return
    }
    var v = value
    val negative = v < 0.0
    if (negative) v = -v
    val scaled = (v * DOUBLE_SCALE + 0.5).toLong()
    val intPart = scaled / DOUBLE_SCALE
    val fracPart = (scaled % DOUBLE_SCALE)
    if (negative && scaled > 0) out.append('-')
    out.append(intPart.toString())
    out.append('.')
    val fracText = fracPart.toString()
    repeat(10 - fracText.length) { out.append('0') }
    out.append(fracText)
  }
}
