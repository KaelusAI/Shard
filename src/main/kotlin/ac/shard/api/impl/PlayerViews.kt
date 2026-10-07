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
package ac.shard.api.impl

import ac.shard.api.detection.DetectionSnapshot
import ac.shard.api.exemption.Exemption
import ac.shard.api.exemption.ExemptionReason
import ac.shard.api.exemption.ExemptionScope
import ac.shard.api.exemption.ExemptionStatus
import ac.shard.api.mitigation.MitigationChannel
import ac.shard.api.mitigation.MitigationSkipReason
import ac.shard.api.mitigation.MitigationSnapshot
import ac.shard.api.mitigation.MitigationTier
import ac.shard.api.player.ClientInfo
import ac.shard.api.player.KnownPlayer
import ac.shard.config.ConfigManager
import ac.shard.mitigation.MitigationSkip
import ac.shard.mitigation.SkipReason
import ac.shard.player.ShardPlayer
import ac.shard.region.RegionCheckMode
import java.time.Instant
import java.util.Collections
import java.util.UUID
import org.bukkit.entity.Player

internal fun apiTier(name: String?): MitigationTier? =
  MitigationTier.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

@Suppress("TooManyFunctions")
class SessionView(
  internal val internal: ShardPlayer,
  private val config: ConfigManager,
  private val skip: MitigationSkip,
) : ac.shard.api.player.ShardPlayer {
  override fun playerId(): UUID = internal.uuid

  override fun name(): String = internal.name

  override fun bukkitPlayer(): Player = internal.player

  override fun joinedAt(): Instant = Instant.ofEpochMilli(internal.joinTime)

  override fun client(): ClientInfo =
    ClientInfoView(
      internal.user.clientVersion.protocolVersion,
      internal.brand.takeIf { internal.brandReceived },
      internal.isBedrock,
    )

  override fun isValid(): Boolean = internal.isAttached && !internal.sessionEnded

  override fun isReady(): Boolean = internal.buffersRestored

  override fun detection(): DetectionSnapshot =
    DetectionSnapshotView.of(internal, config.settings.localAi)

  override fun mitigation(): MitigationSnapshot = MitigationSnapshotView.of(internal, skip)

  override fun exemption(): ExemptionStatus = ExemptionStatusView.of(internal, config)

  override fun equals(other: Any?): Boolean = other is SessionView && other.internal === internal

  override fun hashCode(): Int = System.identityHashCode(internal)

  override fun toString(): String = "ShardPlayer(${internal.name})"
}

internal class ClientInfoView(
  private val protocolVersion: Int,
  private val brand: String?,
  private val bedrock: Boolean,
) : ClientInfo {
  override fun protocolVersion(): Int = protocolVersion

  override fun brand(): String? = brand

  override fun isBedrock(): Boolean = bedrock
}

internal class KnownPlayerView(
  private val playerId: UUID,
  private val name: String,
  private val lastSeen: Instant?,
) : KnownPlayer {
  override fun playerId(): UUID = playerId

  override fun name(): String = name

  override fun lastSeen(): Instant? = lastSeen

  companion object {
    fun of(known: ac.shard.database.KnownPlayer): KnownPlayer =
      KnownPlayerView(
        known.uuid,
        known.name,
        known.lastSeen.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
      )
  }
}

internal class MitigationSnapshotView(
  private val tier: MitigationTier,
  private val ruleId: String?,
  private val score: Double,
  private val effects: Map<MitigationChannel, Double>,
  private val skipReason: MitigationSkipReason?,
) : MitigationSnapshot {
  override fun tier(): MitigationTier = tier

  override fun ruleId(): String? = ruleId

  override fun score(): Double = score

  override fun effects(): Map<MitigationChannel, Double> = Collections.unmodifiableMap(effects)

  override fun skipReason(): MitigationSkipReason? = skipReason

  companion object {
    fun of(player: ShardPlayer, skip: MitigationSkip): MitigationSnapshot {
      val state = player.mitigation
      val effects = state.activeEffects.mapKeys { MitigationChannel.valueOf(it.key.name) }
      return MitigationSnapshotView(
        apiTier(state.appliedTier.name) ?: MitigationTier.NONE,
        state.applied?.id,
        state.score,
        effects,
        skip.skipReason(player)?.let(::apiSkip),
      )
    }

    private fun apiSkip(reason: SkipReason): MitigationSkipReason =
      when (reason) {
        SkipReason.TURNED_OFF -> MitigationSkipReason.TURNED_OFF
        SkipReason.EXEMPT -> MitigationSkipReason.EXEMPT
        SkipReason.CHECKS_DISABLED -> MitigationSkipReason.DETECTION_DISABLED
        SkipReason.NO_MITIGATE -> MitigationSkipReason.NO_MITIGATE
        SkipReason.BEDROCK -> MitigationSkipReason.BEDROCK
        SkipReason.DISABLED_REGION -> MitigationSkipReason.REGION
        SkipReason.TOO_FEW_ANSWERS -> MitigationSkipReason.NOT_ENOUGH_DATA
      }
  }
}

internal class ExemptionStatusView(
  private val reasons: Map<ExemptionScope, Set<ExemptionReason>>,
  private val exemptions: List<Exemption>,
) : ExemptionStatus {
  override fun isExempt(scope: ExemptionScope): Boolean = reasons(scope).isNotEmpty()

  override fun reasons(scope: ExemptionScope): Set<ExemptionReason> =
    Collections.unmodifiableSet(
      ExemptionScope.entries
        .filter { it.ordinal <= scope.ordinal }
        .flatMapTo(mutableSetOf()) { reasons[it].orEmpty() }
    )

  override fun exemptions(): List<Exemption> = exemptions

  companion object {
    fun of(player: ShardPlayer, config: ConfigManager): ExemptionStatus {
      val grants = player.exemptManager.exemptions(player.uuid)
      val reasons = HashMap<ExemptionScope, MutableSet<ExemptionReason>>()
      fun add(scope: ExemptionScope, reason: ExemptionReason) {
        reasons.getOrPut(scope) { mutableSetOf() } += reason
      }
      if (player.disabledByPermission) add(ExemptionScope.DETECTION, ExemptionReason.PERMISSION)
      if (player.exemptByPermission) add(ExemptionScope.ENFORCEMENT, ExemptionReason.PERMISSION)
      if (player.noMitigateByPermission) add(ExemptionScope.MITIGATION, ExemptionReason.PERMISSION)
      if (player.isBedrockExempt) add(ExemptionScope.DETECTION, ExemptionReason.BEDROCK)
      if (player.ai.inDisabledRegion) {
        val scope =
          if (config.settings.regions.mode == RegionCheckMode.SKIP_DETECTION)
            ExemptionScope.DETECTION
          else ExemptionScope.ENFORCEMENT
        add(scope, ExemptionReason.REGION)
      }
      grants.forEach { add(it.scope(), ExemptionReason.GRANT) }
      return ExemptionStatusView(reasons, grants)
    }
  }
}
