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

import ac.shard.ai.TickSerializer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class TickSchemaByteIdentityTest {

  private fun samples(): List<TickData> {
    val defaults = TickData()

    val distinct =
      TickData().apply {
        sequenceId = 12345
        tickIndex = 9_876_543_210L
        dtMs = 42.5f
        targetEntityId = 4242
        yaw = 12.3456f
        pitch = -5.6789f
        x = 100.123456789
        y = -64.987654321
        z = 0.000000001
        deltaY = 0.25
        movementBitfield = 0b10_1010_1010.toShort()
        pose = 5
        inputForward = true
        inputBackward = false
        inputLeft = true
        inputRight = false
        inputJump = true
        inputShift = true
        inputSprint = false
        gameMode = 1
        foodLevel = 17
        groundFriction = 0.91f
        fallDistance = 3.5f
        movementSpeed = 0.13f
        jumpAmplifier = 2
        slowFalling = true
        playerPing = 42
        speedAmplifier = 1
        slownessAmplifier = 0
        hasteAmplifier = 3
        miningFatigueAmplifier = -1
        entityInteractionRange = 3.05f
        scale = 1.5f
        gravity = 0.079f
        jumpStrength = 0.42f
        stepHeight = 0.6f
        targetValid = true
        targetDistance = 2.718281f
        targetAimAngle = 12.5f
        targetRelX = -1.0f
        targetRelY = 0.5f
        targetRelZ = -2.25f
        ticksToAttack = (-7).toShort()
        attackThisTick = true
        aimError = 0.4375f
        sameTargetAsPrevAttack = true
        targetsInRangeCount = 4
        isCritical = true
        raytraceHit = true
        attackRayOccluded = true
        raytraceMissDistance = 0.333333f
        inputValid = true
        hitPointRelX = -0.1f
        hitPointRelY = 0.2f
        hitPointRelZ = -0.3f
        ticksSinceAttack = 11
        swungThisTick = true
        attackCooldownProgress = 0.875f
        attackSpeed = 1.6f
        isSprintingOnAttack = true
        receivedVelocityThisTick = true
        velocityX = 0.4f
        velocityY = -0.1f
        velocityZ = 0.4f
        ticksSinceVelocity = 3
        receivedExplosionThisTick = true
        ticksSinceExplosion = 7
        ticksSinceUseItem = 99
        activeItem = 3
        rotationPresent = true
        modeYaw = 0.0123f
        modePitch = 0.0098f
        modeYawValid = true
        modePitchValid = false
        deltaDotsYaw = 1.5f
        deltaDotsPitch = -2.5f
        activeFireworkCount = 1
        stuckMultX = 0.0f
        stuckMultY = 1.0f
        stuckMultZ = 0.5f
        onSlime = true
        onHoney = false
        onSoulSand = true
        onMud = false
        levitationAmplifier = 5
        swimFriction = 0.96f
        riptideActive = true
        teleportTick = true
        exposureFraction = 0.555556f
        boxInflation = 1.234f
      }

    val nonFinite =
      TickData().apply {
        dtMs = Float.NaN
        yaw = Float.NaN
        pitch = Float.POSITIVE_INFINITY
        x = Double.NEGATIVE_INFINITY
        y = Double.NaN
        swimFriction = Float.NEGATIVE_INFINITY
        targetDistance = Float.POSITIVE_INFINITY
        attackSpeed = Float.NEGATIVE_INFINITY
        exposureFraction = Float.NaN
        boxInflation = Float.POSITIVE_INFINITY
      }

    val roundingEdges =
      TickData().apply {
        movementBitfield = (-1).toShort()
        activeItem = 5
        targetRelX = -0.0000001f // rounds to scaled 0 -> no minus sign
        targetRelY = -1.0000005f
        groundFriction = 0.9999995f
        x = -0.00000000001
        yaw = -0.00000000001f
        exposureFraction = 0.9999995f
        boxInflation = -0.0000001f
      }

    return listOf(defaults, distinct, nonFinite, roundingEdges)
  }

  @Test
  fun soaRawIsByteIdentical() {
    val ticks = samples()
    val ref =
      ByteBuffer.allocate(RAW_HEADER + referenceRowBytes * ticks.size)
        .order(ByteOrder.LITTLE_ENDIAN)
    ref.put(TickSerializer.RAW_MAGIC)
    ref.put(TickSerializer.SCHEMA_VERSION.toByte())
    ref.putShort(ticks.size.toShort())
    ref.putShort(MASKED_NCOLS_MARKER.toShort())
    ref.putShort(TEST_CLIENT_PROTOCOL.toShort())
    ref.putShort(TEST_SERVER_PROTOCOL.toShort())
    ref.putLong(FULL_MASK_LOW)
    ref.putLong(FULL_MASK_HIGH)
    for (field in 0 until FIELD_COUNT) {
      for (tick in ticks) referenceWriteRaw(ref, tick, field)
    }
    assertEquals(
      ref.array().toList(),
      TickSerializer.serialize(ticks, TEST_CLIENT_PROTOCOL, TEST_SERVER_PROTOCOL).toList(),
    )
  }

  @Test
  fun maskedRawKeepsSelectedColumnsByteIdentical() {
    val ticks = samples()
    val selected = listOf(0, 4, 5, 63, 64, 99)
    val names = selected.map { TickSchema.fieldNames[it] }
    val mask = requireNotNull(TickSchema.maskOf(names))
    val rowBytes = selected.sumOf { referenceFieldSize(it) }
    val ref = ByteBuffer.allocate(RAW_HEADER + rowBytes * ticks.size).order(ByteOrder.LITTLE_ENDIAN)
    ref.put(TickSerializer.RAW_MAGIC)
    ref.put(TickSerializer.SCHEMA_VERSION.toByte())
    ref.putShort(ticks.size.toShort())
    ref.putShort(MASKED_NCOLS_MARKER.toShort())
    ref.putShort(TEST_CLIENT_PROTOCOL.toShort())
    ref.putShort(TEST_SERVER_PROTOCOL.toShort())
    ref.putLong((1L shl 0) or (1L shl 4) or (1L shl 5) or (1L shl 63))
    ref.putLong((1L shl 0) or (1L shl 35))
    for (field in selected) {
      for (tick in ticks) referenceWriteRaw(ref, tick, field)
    }
    assertEquals(
      ref.array().toList(),
      TickSerializer.serialize(ticks, TEST_CLIENT_PROTOCOL, TEST_SERVER_PROTOCOL, mask).toList(),
    )
    assertEquals(names, TickSchema.namesOf(mask))
    assertEquals(rowBytes, TickSchema.rowSizeFor(mask))
  }

  @Test
  fun maskOfRejectsUnknownColumn() {
    assertEquals(null, TickSchema.maskOf(listOf("yaw", "definitely_not_a_column")))
  }

  @Test
  fun csvDataColumnsAreIdentical() {
    for (tick in samples()) {
      val reference = StringBuilder().also { referenceAppendCsv(it, tick) }.toString()
      val actual = StringBuilder().also { TickSchema.appendCsvRow(it, tick) }.toString()
      // Drop columns 1-2 (sequence_id/tick_index).
      val refData = reference.split(',').drop(2)
      val actualData = actual.split(',').drop(2)
      assertEquals(refData, actualData)
    }
  }

  @Test
  fun csvHeaderHasExpectedColumns() {
    assertEquals(FIELD_COUNT, TickSchema.csvHeader.split(',').size)
  }

  // Wire types (B=bool1, S=i16, I=i32, L=i64, F=f32, D=f64), spelled out on purpose: this is the
  // second source the test compares TickSchema against.
  private val referenceTypes =
    "IIFIFFDDDBBBSBBBBBBIBBBBBBBIIFFFIBIIIIIFFFFFBFFFFFSBFBIBBBFBFFFIBFFBBFFFIBIIBFFBBFFIFFFBBBBIFBBFFFFI"

  private val referenceRowBytes = referenceTypes.indices.sumOf { referenceFieldSize(it) }

  private fun referenceFieldSize(field: Int) =
    when (referenceTypes[field]) {
      'B' -> 1
      'S' -> 2
      'D',
      'L' -> 8
      else -> 4
    }

  private fun referenceWriteRaw(b: ByteBuffer, t: TickData, field: Int) {
    when (referenceTypes[field]) {
      'B' -> b.put(if (referenceRawFloat(t, field) != 0f) 1 else 0)
      'S' -> b.putShort(referenceRawFloat(t, field).toInt().toShort())
      'I' -> b.putInt(if (field == 1) t.tickIndex.toInt() else referenceRawFloat(t, field).toInt())
      'L' -> b.putLong(referenceRawFloat(t, field).toLong())
      'D' ->
        b.putDouble(
          when (field) {
            6 -> t.x
            7 -> t.y
            else -> t.z
          }
        )
      else -> b.putFloat(referenceRawFloat(t, field))
    }
  }

  @Suppress("CyclomaticComplexMethod", "LongMethod")
  private fun referenceRawFloat(t: TickData, field: Int): Float {
    val bits = t.movementBitfield.toInt()
    return when (field) {
      0 -> t.sequenceId.toFloat()
      1 -> t.tickIndex.toFloat()
      2 -> t.dtMs
      3 -> t.targetEntityId.toFloat()
      4 -> t.yaw
      5 -> t.pitch
      6 -> t.x.toFloat()
      7 -> t.y.toFloat()
      8 -> t.z.toFloat()
      12 -> t.activeItem.toFloat()
      in 9..18 -> if ((bits shr (field - 9)) and 1 == 1) 1f else 0f
      19 -> t.pose.toFloat()
      20 -> if (t.inputForward) 1f else 0f
      21 -> if (t.inputBackward) 1f else 0f
      22 -> if (t.inputLeft) 1f else 0f
      23 -> if (t.inputRight) 1f else 0f
      24 -> if (t.inputJump) 1f else 0f
      25 -> if (t.inputShift) 1f else 0f
      26 -> if (t.inputSprint) 1f else 0f
      27 -> t.gameMode.toFloat()
      28 -> t.foodLevel.toFloat()
      29 -> t.groundFriction
      30 -> t.fallDistance
      31 -> t.movementSpeed
      32 -> t.jumpAmplifier.toFloat()
      33 -> if (t.slowFalling) 1f else 0f
      34 -> t.playerPing.toFloat()
      35 -> t.speedAmplifier.toFloat()
      36 -> t.slownessAmplifier.toFloat()
      37 -> t.hasteAmplifier.toFloat()
      38 -> t.miningFatigueAmplifier.toFloat()
      39 -> t.entityInteractionRange
      40 -> t.scale
      41 -> t.gravity
      42 -> t.jumpStrength
      43 -> t.stepHeight
      44 -> if (t.targetValid) 1f else 0f
      45 -> t.targetDistance
      46 -> t.targetAimAngle
      47 -> t.targetRelX
      48 -> t.targetRelY
      49 -> t.targetRelZ
      50 -> t.ticksToAttack.toFloat()
      51 -> if (t.attackThisTick) 1f else 0f
      52 -> t.aimError
      53 -> if (t.sameTargetAsPrevAttack) 1f else 0f
      54 -> t.targetsInRangeCount.toFloat()
      55 -> if (t.isCritical) 1f else 0f
      56 -> if (t.raytraceHit) 1f else 0f
      57 -> if (t.attackRayOccluded) 1f else 0f
      58 -> t.raytraceMissDistance
      59 -> if (t.inputValid) 1f else 0f
      60 -> t.hitPointRelX
      61 -> t.hitPointRelY
      62 -> t.hitPointRelZ
      63 -> t.ticksSinceAttack.toFloat()
      64 -> if (t.swungThisTick) 1f else 0f
      65 -> t.attackCooldownProgress
      66 -> t.attackSpeed
      67 -> if (t.isSprintingOnAttack) 1f else 0f
      68 -> if (t.receivedVelocityThisTick) 1f else 0f
      69 -> t.velocityX
      70 -> t.velocityY
      71 -> t.velocityZ
      72 -> t.ticksSinceVelocity.toFloat()
      73 -> if (t.receivedExplosionThisTick) 1f else 0f
      74 -> t.ticksSinceExplosion.toFloat()
      75 -> t.ticksSinceUseItem.toFloat()
      76 -> if (t.rotationPresent) 1f else 0f
      77 -> t.modeYaw
      78 -> t.modePitch
      79 -> if (t.modeYawValid) 1f else 0f
      80 -> if (t.modePitchValid) 1f else 0f
      81 -> t.deltaDotsYaw
      82 -> t.deltaDotsPitch
      83 -> t.activeFireworkCount.toFloat()
      84 -> t.stuckMultX
      85 -> t.stuckMultY
      86 -> t.stuckMultZ
      87 -> if (t.onSlime) 1f else 0f
      88 -> if (t.onHoney) 1f else 0f
      89 -> if (t.onSoulSand) 1f else 0f
      90 -> if (t.onMud) 1f else 0f
      91 -> t.levitationAmplifier.toFloat()
      92 -> t.swimFriction
      93 -> if (t.riptideActive) 1f else 0f
      94 -> if (t.teleportTick) 1f else 0f
      95 -> t.exposureFraction
      96 -> t.boxInflation
      97 -> t.health
      98 -> t.damageTakenThisTick
      99 -> t.ticksSinceDamage.toFloat()
      else -> 0f
    }
  }

  private fun referenceAppendCsv(out: Appendable, t: TickData) {
    out.append(t.sequenceId.toString()).append(',')
    out.append(t.tickIndex.toString()).append(',')
    refFloat(out, t.dtMs)
    out.append(',')
    out.append(t.targetEntityId.toString())
    out.append(',')
    refDouble(out, t.yaw.toDouble())
    out.append(',')
    refDouble(out, t.pitch.toDouble())
    out.append(',')
    refDouble(out, t.x)
    out.append(',')
    refDouble(out, t.y)
    out.append(',')
    refDouble(out, t.z)
    out.append(',')
    val bits = t.movementBitfield.toInt()
    for (bit in 0..9) {
      if (bit == 3) {
        out.append(t.activeItem.toString())
      } else {
        out.append(if ((bits shr bit) and 1 == 1) '1' else '0')
      }
      out.append(',')
    }
    out.append(t.pose.toString()).append(',')
    refBool(out, t.inputForward)
    out.append(',')
    refBool(out, t.inputBackward)
    out.append(',')
    refBool(out, t.inputLeft)
    out.append(',')
    refBool(out, t.inputRight)
    out.append(',')
    refBool(out, t.inputJump)
    out.append(',')
    refBool(out, t.inputShift)
    out.append(',')
    refBool(out, t.inputSprint)
    out.append(',')
    out.append(t.gameMode.toString()).append(',')
    out.append(t.foodLevel.toString()).append(',')
    refFloat(out, t.groundFriction)
    out.append(',')
    refFloat(out, t.fallDistance)
    out.append(',')
    refFloat(out, t.movementSpeed)
    out.append(',')
    out.append(t.jumpAmplifier.toString()).append(',')
    refBool(out, t.slowFalling)
    out.append(',')
    out.append(t.playerPing.toString()).append(',')
    out.append(t.speedAmplifier.toString()).append(',')
    out.append(t.slownessAmplifier.toString()).append(',')
    out.append(t.hasteAmplifier.toString()).append(',')
    out.append(t.miningFatigueAmplifier.toString()).append(',')
    refFloat(out, t.entityInteractionRange)
    out.append(',')
    refFloat(out, t.scale)
    out.append(',')
    refFloat(out, t.gravity)
    out.append(',')
    refFloat(out, t.jumpStrength)
    out.append(',')
    refFloat(out, t.stepHeight)
    out.append(',')
    refBool(out, t.targetValid)
    out.append(',')
    refFloat(out, t.targetDistance)
    out.append(',')
    refFloat(out, t.targetAimAngle)
    out.append(',')
    refFloat(out, t.targetRelX)
    out.append(',')
    refFloat(out, t.targetRelY)
    out.append(',')
    refFloat(out, t.targetRelZ)
    out.append(',')
    out.append(t.ticksToAttack.toString()).append(',')
    refBool(out, t.attackThisTick)
    out.append(',')
    refFloat(out, t.aimError)
    out.append(',')
    refBool(out, t.sameTargetAsPrevAttack)
    out.append(',')
    out.append(t.targetsInRangeCount.toString()).append(',')
    refBool(out, t.isCritical)
    out.append(',')
    refBool(out, t.raytraceHit)
    out.append(',')
    refBool(out, t.attackRayOccluded)
    out.append(',')
    refFloat(out, t.raytraceMissDistance)
    out.append(',')
    refBool(out, t.inputValid)
    out.append(',')
    refFloat(out, t.hitPointRelX)
    out.append(',')
    refFloat(out, t.hitPointRelY)
    out.append(',')
    refFloat(out, t.hitPointRelZ)
    out.append(',')
    out.append(t.ticksSinceAttack.toString()).append(',')
    refBool(out, t.swungThisTick)
    out.append(',')
    refFloat(out, t.attackCooldownProgress)
    out.append(',')
    refFloat(out, t.attackSpeed)
    out.append(',')
    refBool(out, t.isSprintingOnAttack)
    out.append(',')
    refBool(out, t.receivedVelocityThisTick)
    out.append(',')
    refFloat(out, t.velocityX)
    out.append(',')
    refFloat(out, t.velocityY)
    out.append(',')
    refFloat(out, t.velocityZ)
    out.append(',')
    out.append(t.ticksSinceVelocity.toString()).append(',')
    refBool(out, t.receivedExplosionThisTick)
    out.append(',')
    out.append(t.ticksSinceExplosion.toString()).append(',')
    out.append(t.ticksSinceUseItem.toString()).append(',')
    refBool(out, t.rotationPresent)
    out.append(',')
    refFloat(out, t.modeYaw)
    out.append(',')
    refFloat(out, t.modePitch)
    out.append(',')
    refBool(out, t.modeYawValid)
    out.append(',')
    refBool(out, t.modePitchValid)
    out.append(',')
    refFloat(out, t.deltaDotsYaw)
    out.append(',')
    refFloat(out, t.deltaDotsPitch)
    out.append(',')
    out.append(t.activeFireworkCount.toString()).append(',')
    refFloat(out, t.stuckMultX)
    out.append(',')
    refFloat(out, t.stuckMultY)
    out.append(',')
    refFloat(out, t.stuckMultZ)
    out.append(',')
    refBool(out, t.onSlime)
    out.append(',')
    refBool(out, t.onHoney)
    out.append(',')
    refBool(out, t.onSoulSand)
    out.append(',')
    refBool(out, t.onMud)
    out.append(',')
    out.append(t.levitationAmplifier.toString()).append(',')
    refFloat(out, t.swimFriction)
    out.append(',')
    refBool(out, t.riptideActive)
    out.append(',')
    refBool(out, t.teleportTick)
    out.append(',')
    refFloat(out, t.exposureFraction)
    out.append(',')
    refFloat(out, t.boxInflation)
    out.append(',')
    refFloat(out, t.health)
    out.append(',')
    refFloat(out, t.damageTakenThisTick)
    out.append(',')
    out.append(t.ticksSinceDamage.toString())
  }

  private fun refBool(out: Appendable, value: Boolean) {
    out.append(if (value) '1' else '0')
  }

  private fun refFloat(out: Appendable, value: Float) {
    if (!value.isFinite()) {
      out.append("0.000000")
      return
    }
    var v = value.toDouble()
    val negative = v < 0.0
    if (negative) v = -v
    val scaled = (v * 1_000_000L + 0.5).toLong()
    val intPart = scaled / 1_000_000L
    val fracPart = (scaled % 1_000_000L).toInt()
    if (negative && scaled > 0) out.append('-')
    out.append(intPart.toString())
    out.append('.')
    val fracText = fracPart.toString()
    repeat(6 - fracText.length) { out.append('0') }
    out.append(fracText)
  }

  private fun refDouble(out: Appendable, value: Double) {
    if (!value.isFinite()) {
      out.append("0.0000000000")
      return
    }
    var v = value
    val negative = v < 0.0
    if (negative) v = -v
    val scaleD = 10_000_000_000L
    val scaled = (v * scaleD + 0.5).toLong()
    val intPart = scaled / scaleD
    val fracPart = (scaled % scaleD)
    if (negative && scaled > 0) out.append('-')
    out.append(intPart.toString())
    out.append('.')
    val fracText = fracPart.toString()
    repeat(10 - fracText.length) { out.append('0') }
    out.append(fracText)
  }

  private companion object {
    const val FIELD_COUNT = 100
    const val RAW_HEADER = 26
    const val MASKED_NCOLS_MARKER = 0
    const val FULL_MASK_LOW = -1L
    const val FULL_MASK_HIGH = 0xF_FFFF_FFFFL
    const val TEST_CLIENT_PROTOCOL = 769
    const val TEST_SERVER_PROTOCOL = 769
  }
}
