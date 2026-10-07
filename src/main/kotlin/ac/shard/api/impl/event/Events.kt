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
package ac.shard.api.impl.event

import ac.shard.api.Initiator
import ac.shard.api.alert.AlertType
import ac.shard.api.detection.LabelBuffer
import ac.shard.api.detection.LabelId
import ac.shard.api.detection.ModelState
import ac.shard.api.detection.Verdict
import ac.shard.api.event.alert.AlertEvent
import ac.shard.api.event.config.ConfigurationArea
import ac.shard.api.event.config.ConfigurationChangeEvent
import ac.shard.api.event.detection.BufferResetEvent
import ac.shard.api.event.detection.FlagEvent
import ac.shard.api.event.detection.VerdictEvent
import ac.shard.api.event.exemption.ExemptionChangeEvent
import ac.shard.api.event.mitigation.MitigationApplyEvent
import ac.shard.api.event.mitigation.MitigationChangeEvent
import ac.shard.api.event.player.ClientBrandEvent
import ac.shard.api.event.player.SessionEndEvent
import ac.shard.api.event.player.SessionStartEvent
import ac.shard.api.event.punishment.PunishmentEvent
import ac.shard.api.event.punishment.ViolationLevelResetEvent
import ac.shard.api.exemption.Exemption
import ac.shard.api.mitigation.MitigationChannel
import ac.shard.api.mitigation.MitigationTier
import ac.shard.api.player.ShardPlayer
import ac.shard.api.punishment.PunishmentGroup
import java.util.Collections
import java.util.UUID
import org.bukkit.entity.Entity

abstract class SessionEventBase(private val session: ShardPlayer) {
  fun player(): ShardPlayer = session

  fun playerId(): UUID = session.playerId()

  fun playerName(): String = session.name()
}

class SessionStartEventImpl(session: ShardPlayer) : SessionEventBase(session), SessionStartEvent

class SessionEndEventImpl(session: ShardPlayer) : SessionEventBase(session), SessionEndEvent

class ClientBrandEventImpl(session: ShardPlayer, private val brand: String) :
  SessionEventBase(session), ClientBrandEvent {
  override fun brand(): String = brand
}

class VerdictEventImpl(
  session: ShardPlayer,
  private val verdict: Verdict,
  private val state: ModelState,
) : SessionEventBase(session), VerdictEvent {
  override fun verdict(): Verdict = verdict

  override fun state(): ModelState = state
}

class FlagEventImpl(
  private val session: ShardPlayer,
  private val modelId: String,
  private val crossed: List<LabelBuffer>,
  private val groups: List<PunishmentGroup>,
) : CancellableEvent(), FlagEvent {
  override fun player(): ShardPlayer = session

  override fun playerId(): UUID = session.playerId()

  override fun playerName(): String = session.name()

  override fun modelId(): String = modelId

  override fun crossed(): List<LabelBuffer> = Collections.unmodifiableList(crossed)

  override fun groups(): List<PunishmentGroup> = Collections.unmodifiableList(groups)
}

class BufferResetEventImpl(
  private val playerId: UUID,
  private val playerName: String,
  private val initiator: Initiator,
  private val modelId: String?,
  private val label: LabelId?,
) : BufferResetEvent {
  override fun playerId(): UUID = playerId

  override fun playerName(): String = playerName

  override fun initiator(): Initiator = initiator

  override fun modelId(): String? = modelId

  override fun label(): LabelId? = label
}

@Suppress("LongParameterList")
class PunishmentEventImpl(
  private val playerId: UUID,
  private val playerName: String,
  private val group: PunishmentGroup,
  private val violationLevel: Int,
  private val labels: Set<LabelId>,
  private val session: ShardPlayer?,
) : CancellableEvent(), PunishmentEvent {
  override fun playerId(): UUID = playerId

  override fun playerName(): String = playerName

  override fun group(): PunishmentGroup = group

  override fun violationLevel(): Int = violationLevel

  override fun labels(): Set<LabelId> = Collections.unmodifiableSet(labels)

  override fun session(): ShardPlayer? = session
}

class ViolationLevelResetEventImpl(
  private val playerId: UUID,
  private val playerName: String,
  private val initiator: Initiator,
  private val groupKey: String?,
) : ViolationLevelResetEvent {
  override fun playerId(): UUID = playerId

  override fun playerName(): String = playerName

  override fun initiator(): Initiator = initiator

  override fun groupKey(): String? = groupKey
}

@Suppress("LongParameterList")
class MitigationApplyEventImpl(
  private val session: ShardPlayer,
  private val channel: MitigationChannel,
  private val tier: MitigationTier,
  private val ruleId: String?,
  private val effect: Double,
  private val counterpart: Entity?,
) : CancellableEvent(), MitigationApplyEvent {
  override fun player(): ShardPlayer = session

  override fun playerId(): UUID = session.playerId()

  override fun playerName(): String = session.name()

  override fun channel(): MitigationChannel = channel

  override fun tier(): MitigationTier = tier

  override fun ruleId(): String? = ruleId

  override fun effect(): Double = effect

  override fun counterpart(): Entity? = counterpart
}

@Suppress("LongParameterList")
class MitigationChangeEventImpl(
  session: ShardPlayer,
  private val previousTier: MitigationTier,
  private val tier: MitigationTier,
  private val previousRuleId: String?,
  private val ruleId: String?,
  private val initiator: Initiator?,
) : SessionEventBase(session), MitigationChangeEvent {
  override fun previousTier(): MitigationTier = previousTier

  override fun tier(): MitigationTier = tier

  override fun previousRuleId(): String? = previousRuleId

  override fun ruleId(): String? = ruleId

  override fun initiator(): Initiator? = initiator
}

class ExemptionChangeEventImpl(
  private val playerName: String,
  private val exemption: Exemption,
  private val change: ExemptionChangeEvent.Change,
) : ExemptionChangeEvent {
  override fun playerId(): UUID = exemption.playerId()

  override fun playerName(): String = playerName

  override fun exemption(): Exemption = exemption

  override fun change(): ExemptionChangeEvent.Change = change
}

@Suppress("LongParameterList")
class AlertEventImpl(
  private val type: AlertType,
  private val subject: UUID?,
  private val plainText: String,
  private val originServer: String,
  private val remote: Boolean,
  private val initiator: Initiator?,
) : CancellableEvent(), AlertEvent {
  override fun type(): AlertType = type

  override fun subject(): UUID? = subject

  override fun plainText(): String = plainText

  override fun originServer(): String = originServer

  override fun isRemote(): Boolean = remote

  override fun initiator(): Initiator? = initiator
}

class ConfigurationChangeEventImpl(private val areas: Set<ConfigurationArea>) :
  ConfigurationChangeEvent {
  override fun areas(): Set<ConfigurationArea> = Collections.unmodifiableSet(areas)
}
