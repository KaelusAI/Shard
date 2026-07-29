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
package ac.shard.utils.nmsutil

import ac.shard.player.ShardPlayer
import ac.shard.utils.math.SimpleCollisionBox
import ac.shard.utils.math.VanillaMath
import ac.shard.utils.math.Vector3dm
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

object ReachUtils {

  private const val DEGREES_TO_RADIANS = 0.017453292f
  private const val EPSILON = 1.0000000116860974E-7
  private const val OCCLUSION_STEP = 0.25
  private const val OCCLUSION_MAX_SAMPLES = 24
  private const val HALF = 0.5
  private const val EXPOSURE_SAMPLE_POINTS = 9f
  private const val BOX_CORNERS = 8
  private const val CORNER_BIT_X = 1
  private const val CORNER_BIT_Y = 2
  private const val CORNER_BIT_Z = 4

  fun calculateIntercept(box: SimpleCollisionBox, origin: Vector3dm, end: Vector3dm): Vector3dm? {
    var minX = getIntermediateWithXValue(origin, end, box.minX)
    var maxX = getIntermediateWithXValue(origin, end, box.maxX)
    var minY = getIntermediateWithYValue(origin, end, box.minY)
    var maxY = getIntermediateWithYValue(origin, end, box.maxY)
    var minZ = getIntermediateWithZValue(origin, end, box.minZ)
    var maxZ = getIntermediateWithZValue(origin, end, box.maxZ)

    if (!isVecInYZ(box, minX)) minX = null
    if (!isVecInYZ(box, maxX)) maxX = null
    if (!isVecInXZ(box, minY)) minY = null
    if (!isVecInXZ(box, maxY)) maxY = null
    if (!isVecInXY(box, minZ)) minZ = null
    if (!isVecInXY(box, maxZ)) maxZ = null

    var best: Vector3dm? = null

    if (minX != null) best = minX
    if (
      maxX != null && (best == null || origin.distanceSquared(maxX) < origin.distanceSquared(best))
    )
      best = maxX
    if (
      minY != null && (best == null || origin.distanceSquared(minY) < origin.distanceSquared(best))
    )
      best = minY
    if (
      maxY != null && (best == null || origin.distanceSquared(maxY) < origin.distanceSquared(best))
    )
      best = maxY
    if (
      minZ != null && (best == null || origin.distanceSquared(minZ) < origin.distanceSquared(best))
    )
      best = minZ
    if (
      maxZ != null && (best == null || origin.distanceSquared(maxZ) < origin.distanceSquared(best))
    )
      best = maxZ

    return best
  }

  fun isVecInside(box: SimpleCollisionBox, vec: Vector3dm): Boolean =
    vec.x > box.minX &&
      vec.x < box.maxX &&
      vec.y > box.minY &&
      vec.y < box.maxY &&
      vec.z > box.minZ &&
      vec.z < box.maxZ

  fun getMinReachToBox(player: ShardPlayer, targetBox: SimpleCollisionBox): Double {
    var lowest = Double.MAX_VALUE
    for (eyeHeight in getPossibleEyeHeights()) {
      val closest =
        cutBoxToVector(
          player.movement.x,
          player.movement.y + eyeHeight,
          player.movement.z,
          targetBox,
        )
      lowest =
        min(
          lowest,
          closest.distance(player.movement.x, player.movement.y + eyeHeight, player.movement.z),
        )
    }
    return lowest
  }

  fun minDistanceRayToBox(
    origin: Vector3dm,
    dir: Vector3dm,
    maxDist: Double,
    box: SimpleCollisionBox,
  ): Double {
    if (
      calculateIntercept(
        box,
        origin,
        Vector3dm(
          origin.x + dir.x * maxDist,
          origin.y + dir.y * maxDist,
          origin.z + dir.z * maxDist,
        ),
      ) != null
    )
      return 0.0
    if (isVecInside(box, origin)) return 0.0

    val samples = 32
    var minD2 = Double.MAX_VALUE
    for (i in 0..samples) {
      val t = maxDist * i / samples
      val px = origin.x + dir.x * t
      val py = origin.y + dir.y * t
      val pz = origin.z + dir.z * t
      val cx = px.coerceIn(box.minX, box.maxX)
      val cy = py.coerceIn(box.minY, box.maxY)
      val cz = pz.coerceIn(box.minZ, box.maxZ)
      val dx = px - cx
      val dy = py - cy
      val dz = pz - cz
      val d2 = dx * dx + dy * dy + dz * dz
      if (d2 < minD2) minD2 = d2
    }
    return sqrt(minD2)
  }

  fun getMinAngleToBox(player: ShardPlayer, box: SimpleCollisionBox): Double {
    val lookDirs = getPossibleLookDirs(player)
    val eyeHeights = getPossibleEyeHeights()

    val points =
      arrayOf(
        doubleArrayOf(
          (box.minX + box.maxX) / 2,
          (box.minY + box.maxY) / 2,
          (box.minZ + box.maxZ) / 2,
        ),
        doubleArrayOf(box.minX, box.minY, box.minZ),
        doubleArrayOf(box.maxX, box.minY, box.minZ),
        doubleArrayOf(box.minX, box.maxY, box.minZ),
        doubleArrayOf(box.maxX, box.maxY, box.minZ),
        doubleArrayOf(box.minX, box.minY, box.maxZ),
        doubleArrayOf(box.maxX, box.minY, box.maxZ),
        doubleArrayOf(box.minX, box.maxY, box.maxZ),
        doubleArrayOf(box.maxX, box.maxY, box.maxZ),
      )

    var minAngle = Double.MAX_VALUE
    for (lookVec in lookDirs) {
      for (eye in eyeHeights) {
        val ex = player.movement.x
        val ey = player.movement.y + eye
        val ez = player.movement.z
        for (p in points) {
          val dx = p[0] - ex
          val dy = p[1] - ey
          val dz = p[2] - ez
          val len = sqrt(dx * dx + dy * dy + dz * dz)
          if (len < 0.001) return 0.0
          val dot = lookVec.x * (dx / len) + lookVec.y * (dy / len) + lookVec.z * (dz / len)
          val clamped = dot.coerceIn(-1.0, 1.0)
          minAngle = min(minAngle, Math.toDegrees(acos(clamped)))
        }
      }
    }
    return minAngle
  }

  fun aimError(player: ShardPlayer, box: SimpleCollisionBox): Double {
    val cx = (box.minX + box.maxX) / 2
    val cy = (box.minY + box.maxY) / 2
    val cz = (box.minZ + box.maxZ) / 2

    var minAngle = Double.MAX_VALUE
    var distAtMin = 0.0
    for (lookVec in getPossibleLookDirs(player)) {
      for (eye in getPossibleEyeHeights()) {
        val dx = cx - player.movement.x
        val dy = cy - (player.movement.y + eye)
        val dz = cz - player.movement.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.001) return 0.0
        val dot = lookVec.x * (dx / len) + lookVec.y * (dy / len) + lookVec.z * (dz / len)
        val angle = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
        if (angle < minAngle) {
          minAngle = angle
          distAtMin = len
        }
      }
    }
    if (minAngle == Double.MAX_VALUE) return 0.0

    val halfExtent = maxOf(box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ) / 2
    val angularRadius = Math.toDegrees(atan2(halfExtent, distAtMin))
    return if (angularRadius > 1e-6) minAngle / angularRadius else 0.0
  }

  private fun radians(degrees: Float): Float = degrees * DEGREES_TO_RADIANS

  fun getLook(player: ShardPlayer, yaw: Float, pitch: Float): Vector3dm {
    val modern = player.user.clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_11)
    val look =
      if (player.user.clientVersion.isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
        val yawRad = radians(-yaw) - Math.PI.toFloat()
        val pitchRad = radians(-pitch)
        val pitchCos = -VanillaMath.cos(pitchRad, modern)
        Vector3dm(
          (VanillaMath.sin(yawRad, modern) * pitchCos).toDouble(),
          VanillaMath.sin(pitchRad, modern).toDouble(),
          (VanillaMath.cos(yawRad, modern) * pitchCos).toDouble(),
        )
      } else {
        val pitchRad = radians(pitch)
        val yawRad = radians(-yaw)
        val pitchCos = VanillaMath.cos(pitchRad, modern)
        Vector3dm(
          (VanillaMath.sin(yawRad, modern) * pitchCos).toDouble(),
          (-VanillaMath.sin(pitchRad, modern)).toDouble(),
          (VanillaMath.cos(yawRad, modern) * pitchCos).toDouble(),
        )
      }
    // The table leaves sin^2+cos^2 off unity by up to 8e-5, and callers take acos of a dot product
    // with this vector: near zero the missing length shows up as a fake fraction of a degree.
    val length = sqrt(look.x * look.x + look.y * look.y + look.z * look.z)
    if (length == 0.0) return look
    return Vector3dm(look.x / length, look.y / length, look.z / length)
  }

  fun getPossibleLookDirs(player: ShardPlayer): List<Vector3dm> {
    val m = player.movement
    val version = player.user.clientVersion
    // 1.7 clients are always on the latest look vector, 1.9+ can be a tick behind on skipped ticks.
    if (version.isOlderThan(ClientVersion.V_1_8)) {
      return listOf(getLook(player, m.yaw, m.pitch))
    }
    val dirs = mutableListOf(getLook(player, m.yaw, m.pitch), getLook(player, m.lastYaw, m.pitch))
    if (version.isNewerThanOrEquals(ClientVersion.V_1_9)) {
      dirs.add(getLook(player, m.lastYaw, m.lastPitch))
    }
    return dirs
  }

  fun getPossibleEyeHeights(): DoubleArray = doubleArrayOf(1.62, 1.27, 0.4)

  fun cutBoxToVector(x: Double, y: Double, z: Double, box: SimpleCollisionBox): Vector3dm =
    Vector3dm(
      x.coerceIn(box.minX, box.maxX),
      y.coerceIn(box.minY, box.maxY),
      z.coerceIn(box.minZ, box.maxZ),
    )

  private fun getIntermediateWithXValue(self: Vector3dm, other: Vector3dm, x: Double): Vector3dm? {
    val dx = other.x - self.x
    val dy = other.y - self.y
    val dz = other.z - self.z
    if (dx * dx < EPSILON) return null
    val t = (x - self.x) / dx
    return if (t in 0.0..1.0) Vector3dm(self.x + dx * t, self.y + dy * t, self.z + dz * t) else null
  }

  private fun getIntermediateWithYValue(self: Vector3dm, other: Vector3dm, y: Double): Vector3dm? {
    val dx = other.x - self.x
    val dy = other.y - self.y
    val dz = other.z - self.z
    if (dy * dy < EPSILON) return null
    val t = (y - self.y) / dy
    return if (t in 0.0..1.0) Vector3dm(self.x + dx * t, self.y + dy * t, self.z + dz * t) else null
  }

  private fun getIntermediateWithZValue(self: Vector3dm, other: Vector3dm, z: Double): Vector3dm? {
    val dx = other.x - self.x
    val dy = other.y - self.y
    val dz = other.z - self.z
    if (dz * dz < EPSILON) return null
    val t = (z - self.z) / dz
    return if (t in 0.0..1.0) Vector3dm(self.x + dx * t, self.y + dy * t, self.z + dz * t) else null
  }

  fun exposureFraction(player: ShardPlayer, tightBox: SimpleCollisionBox): Float {
    val eye = eyeOrigin(player)
    val cx = (tightBox.minX + tightBox.maxX) * HALF
    val cy = (tightBox.minY + tightBox.maxY) * HALF
    val cz = (tightBox.minZ + tightBox.maxZ) * HALF
    var visible = 0
    if (!isRayOccluded(player, eye, cx, cy, cz)) visible++
    for (corner in 0 until BOX_CORNERS) {
      val px = if (corner and CORNER_BIT_X == 0) tightBox.minX else tightBox.maxX
      val py = if (corner and CORNER_BIT_Y == 0) tightBox.minY else tightBox.maxY
      val pz = if (corner and CORNER_BIT_Z == 0) tightBox.minZ else tightBox.maxZ
      if (!isRayOccluded(player, eye, px, py, pz)) visible++
    }
    return visible / EXPOSURE_SAMPLE_POINTS
  }

  fun eyeOrigin(player: ShardPlayer): Vector3dm {
    val m = player.movement
    val eyeHeight = getPossibleEyeHeights().first()
    return Vector3dm(m.x, m.y + eyeHeight, m.z)
  }

  fun isRayOccluded(
    player: ShardPlayer,
    origin: Vector3dm,
    tx: Double,
    ty: Double,
    tz: Double,
  ): Boolean {
    val dx = tx - origin.x
    val dy = ty - origin.y
    val dz = tz - origin.z
    val dist = sqrt(dx * dx + dy * dy + dz * dz)
    if (dist < OCCLUSION_STEP) return false

    val samples = min((dist / OCCLUSION_STEP).toInt(), OCCLUSION_MAX_SAMPLES)
    val scale = OCCLUSION_STEP / dist
    val sx = dx * scale
    val sy = dy * scale
    val sz = dz * scale

    return (1 until samples).any { step ->
      val block =
        player.compensatedWorld.getBlock(
          floor(origin.x + sx * step).toInt(),
          floor(origin.y + sy * step).toInt(),
          floor(origin.z + sz * step).toInt(),
        )
      block != null && block.type.isSolid && !block.type.isAir
    }
  }

  fun boxInflationExtent(union: SimpleCollisionBox, tight: SimpleCollisionBox): Double =
    ((union.maxX - union.minX) - (tight.maxX - tight.minX)) +
      ((union.maxY - union.minY) - (tight.maxY - tight.minY)) +
      ((union.maxZ - union.minZ) - (tight.maxZ - tight.minZ))

  private fun isVecInYZ(box: SimpleCollisionBox, vec: Vector3dm?): Boolean =
    vec != null && vec.y >= box.minY && vec.y <= box.maxY && vec.z >= box.minZ && vec.z <= box.maxZ

  private fun isVecInXZ(box: SimpleCollisionBox, vec: Vector3dm?): Boolean =
    vec != null && vec.x >= box.minX && vec.x <= box.maxX && vec.z >= box.minZ && vec.z <= box.maxZ

  private fun isVecInXY(box: SimpleCollisionBox, vec: Vector3dm?): Boolean =
    vec != null && vec.x >= box.minX && vec.x <= box.maxX && vec.y >= box.minY && vec.y <= box.maxY
}
