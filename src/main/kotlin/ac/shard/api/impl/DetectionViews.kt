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

import ac.shard.ai.label.DetectionKey
import ac.shard.ai.label.LabelCatalog
import ac.shard.ai.label.LabelKey
import ac.shard.ai.stream.BlockReason
import ac.shard.ai.stream.ModelSpec
import ac.shard.ai.stream.RoleSpec
import ac.shard.ai.stream.StreamProfile
import ac.shard.api.detection.DetectionSnapshot
import ac.shard.api.detection.DetectionStatus
import ac.shard.api.detection.Label
import ac.shard.api.detection.LabelBuffer
import ac.shard.api.detection.LabelId
import ac.shard.api.detection.Model
import ac.shard.api.detection.ModelRole
import ac.shard.api.detection.ModelState
import ac.shard.api.detection.Verdict
import ac.shard.config.LocalAiSettings
import ac.shard.detection.EffectiveSettings
import ac.shard.detection.ModelVerdict
import ac.shard.player.ShardPlayer
import java.time.Instant
import java.util.Collections
import java.util.Optional

private val MODEL_ID = Regex("[a-z0-9_]{1,64}")

internal fun labelIdOf(modelId: String, key: String): LabelId? =
  if (MODEL_ID.matches(modelId) && !LabelKey.isReserved(key) && LabelKey.canonical(key) == key) {
    LabelId(modelId, key)
  } else {
    null
  }

internal fun labelIdOfAddress(address: String, primaryModel: String): LabelId? {
  val key = DetectionKey.parseAddress(address) ?: DetectionKey(primaryModel, address)
  return labelIdOf(key.model, key.label)
}

private fun rolesOf(role: ac.shard.ai.stream.ModelRole, covers: (RoleSpec) -> Boolean) = buildSet {
  role.alert?.takeIf(covers)?.let { add(ModelRole.ALERT) }
  role.mitigate?.takeIf(covers)?.let { add(ModelRole.MITIGATE) }
  role.punish?.takeIf(covers)?.let { add(ModelRole.PUNISH) }
}

internal class LabelView(
  private val id: LabelId,
  private val displayName: String,
  private val legit: Boolean,
  private val flagThreshold: Double,
  private val roles: Set<ModelRole>,
) : Label {
  override fun id(): LabelId = id

  override fun displayName(): String = displayName

  override fun isLegit(): Boolean = legit

  override fun flagThreshold(): Double = flagThreshold

  override fun roles(): Set<ModelRole> = Collections.unmodifiableSet(roles)
}

@Suppress("LongParameterList")
internal class ModelView(
  private val id: String,
  private val displayName: String,
  private val shortName: String,
  private val primary: Boolean,
  private val roles: Set<ModelRole>,
  private val flagThreshold: Double,
  private val labels: List<Label>,
) : Model {
  override fun id(): String = id

  override fun displayName(): String = displayName

  override fun shortName(): String = shortName

  override fun isPrimary(): Boolean = primary

  override fun roles(): Set<ModelRole> = Collections.unmodifiableSet(roles)

  override fun flagThreshold(): Double = flagThreshold

  override fun labels(): List<Label> = Collections.unmodifiableList(labels)

  override fun label(key: String): Optional<Label> =
    Optional.ofNullable(labels.firstOrNull { it.id().key() == key })

  companion object {
    fun of(
      spec: ModelSpec,
      profile: StreamProfile,
      local: LocalAiSettings,
      catalog: LabelCatalog,
    ): Model? {
      if (!MODEL_ID.matches(spec.id)) return null
      val settings = EffectiveSettings.of(spec, local)
      val primary = spec.id == profile.primary.id
      val labels =
        spec.labels.mapNotNull { key ->
          val id = labelIdOf(spec.id, key) ?: return@mapNotNull null
          val address = if (primary) key else DetectionKey(spec.id, key).address()
          LabelView(
            id,
            catalog.displayName(address),
            key in spec.legitLabels,
            settings.buffer(key).flag,
            rolesOf(settings.role) { it.covers(key) },
          )
        }
      return ModelView(
        spec.id,
        spec.title,
        spec.displayTitle,
        primary,
        rolesOf(settings.role) { true },
        settings.buffer.flag,
        labels,
      )
    }
  }
}

internal class LabelBufferView(
  private val modelId: String,
  private val label: LabelId?,
  private val value: Double,
  private val flagThreshold: Double,
) : LabelBuffer {
  override fun modelId(): String = modelId

  override fun label(): LabelId? = label

  override fun value(): Double = value

  override fun flagThreshold(): Double = flagThreshold
}

internal class ModelStateView(
  private val modelId: String,
  private val buffer: Double,
  private val buffers: List<LabelBuffer>,
  private val partial: Boolean,
) : ModelState {
  override fun modelId(): String = modelId

  override fun buffer(): Double = buffer

  override fun buffers(): List<LabelBuffer> = Collections.unmodifiableList(buffers)

  override fun isPartial(): Boolean = partial
}

internal class VerdictView(
  private val modelId: String,
  private val receivedAt: Instant,
  private val probability: Double,
  private val probabilities: Map<LabelId, Double>,
) : Verdict {
  override fun modelId(): String = modelId

  override fun receivedAt(): Instant = receivedAt

  override fun probability(): Double = probability

  override fun probabilities(): Map<LabelId, Double> = Collections.unmodifiableMap(probabilities)

  companion object {
    fun of(verdict: ModelVerdict): Verdict =
      VerdictView(
        verdict.modelId,
        Instant.ofEpochMilli(verdict.receivedAtMillis),
        verdict.probability,
        verdict.labels.entries
          .mapNotNull { (key, p) -> labelIdOf(verdict.modelId, key)?.let { it to p } }
          .toMap(),
      )
  }
}

internal class DetectionSnapshotView(
  private val status: DetectionStatus,
  private val models: List<ModelState>,
  private val verdicts: Map<String, Verdict>,
) : DetectionSnapshot {
  override fun status(): DetectionStatus = status

  override fun models(): List<ModelState> = Collections.unmodifiableList(models)

  override fun model(modelId: String): Optional<ModelState> =
    Optional.ofNullable(models.firstOrNull { it.modelId() == modelId })

  override fun buffer(label: LabelId): Optional<LabelBuffer> =
    Optional.ofNullable(
      models.firstNotNullOfOrNull { state -> state.buffers().firstOrNull { it.label() == label } }
    )

  override fun lastVerdict(modelId: String): Optional<Verdict> =
    Optional.ofNullable(verdicts[modelId])

  companion object {
    fun of(player: ShardPlayer, local: LocalAiSettings): DetectionSnapshot {
      val ai = player.detection
      val profile = ai.currentProfile()
      val buffers = ai.bufferSnapshot()
      val models =
        profile
          ?.active
          .orEmpty()
          .filter { MODEL_ID.matches(it.id) }
          .map { spec ->
            stateOf(spec, buffers, EffectiveSettings.of(spec, local))
          }
      val verdicts = ai.lastVerdicts.mapValues { VerdictView.of(it.value) }
      return DetectionSnapshotView(statusOf(player, profile), models, verdicts)
    }

    fun stateOf(
      spec: ModelSpec,
      buffers: Map<DetectionKey, Double>,
      settings: EffectiveSettings,
    ): ModelState {
      val own = buffers.filterKeys { it.model == spec.id }
      val views =
        own.entries
          .sortedByDescending { it.value }
          .map { (key, value) ->
            LabelBufferView(
              spec.id,
              labelIdOf(spec.id, key.label),
              value,
              settings.buffer(key.label).flag,
            )
          }
      return ModelStateView(
        spec.id,
        own.values.maxOrNull() ?: 0.0,
        views,
        own.size >= settings.maxTracked,
      )
    }

    private fun statusOf(player: ShardPlayer, profile: StreamProfile?): DetectionStatus =
      when {
        player.isBedrockExempt -> DetectionStatus.BEDROCK
        player.detectionDisabled -> DetectionStatus.DISABLED
        profile == null || !player.buffersRestored -> DetectionStatus.NOT_READY
        else ->
          when (player.ai.streamBlockReason) {
            null -> DetectionStatus.ACTIVE
            BlockReason.NO_PROFILE -> DetectionStatus.NOT_READY
            BlockReason.DISABLED -> DetectionStatus.IDLE
            BlockReason.VEHICLE -> DetectionStatus.VEHICLE
            BlockReason.REGION -> DetectionStatus.REGION
            BlockReason.COOLDOWN -> DetectionStatus.COOLDOWN
            BlockReason.STREAM_LIMIT -> DetectionStatus.CAPACITY
            BlockReason.PROTOCOL -> DetectionStatus.UNREADABLE_REPLY
          }
      }
  }
}
