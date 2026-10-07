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
package ac.shard.player

import ac.shard.api.Initiator
import ac.shard.api.event.exemption.ExemptionChangeEvent
import ac.shard.api.event.exemption.ExemptionChangeEvent.Change
import ac.shard.api.exemption.Exemption
import ac.shard.api.exemption.ExemptionScope
import ac.shard.api.impl.CommandInitiator
import ac.shard.api.impl.PluginInitiator
import ac.shard.api.impl.event.ExemptionChangeEventImpl
import ac.shard.api.impl.event.ShardEvents
import ac.shard.api.impl.nameOf
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

@Suppress("TooManyFunctions")
class ExemptManager(private val events: ShardEvents, private val server: org.bukkit.Server) :
  Listener {
  private val grants = ConcurrentHashMap<UUID, ConcurrentHashMap<Key, Grant>>()

  fun isDisabled(player: Player?): Boolean {
    return player?.hasPermission(DISABLE_PERMISSION) == true
  }

  fun isDisabled(player: ShardPlayer): Boolean =
    player.disabledByPermission || hasGrant(player.uuid, ExemptionScope.DETECTION)

  fun isExempt(player: ShardPlayer): Boolean =
    player.exemptByPermission || hasGrant(player.uuid, ExemptionScope.ENFORCEMENT)

  fun isMitigationExempt(player: ShardPlayer): Boolean =
    player.noMitigateByPermission || hasGrant(player.uuid, ExemptionScope.MITIGATION)

  fun isExempt(player: Player?): Boolean {
    if (player == null) {
      return false
    }
    return player.hasPermission(EXEMPT_PERMISSION) ||
      hasGrant(player.uniqueId, ExemptionScope.ENFORCEMENT)
  }

  fun hasGrant(playerId: UUID, scope: ExemptionScope): Boolean =
    active(playerId).any { it.scope().ordinal <= scope.ordinal }

  fun exemptions(playerId: UUID): List<Exemption> = active(playerId)

  @Suppress("LongParameterList")
  fun grant(
    owner: Initiator,
    playerId: UUID,
    scope: ExemptionScope,
    duration: Duration?,
    reason: String?,
  ): Exemption {
    val now = Instant.now()
    val grant = Grant(owner, playerId, scope, now, duration?.let(now::plus), reason)
    var displaced: Grant? = null
    grants.compute(playerId) { _, owned ->
      (owned ?: ConcurrentHashMap()).also { displaced = it.put(grant.key, grant) }
    }
    val previous = displaced
    val replaced = previous?.isActive() == true
    if (previous?.deactivate() == true && !replaced) announce(previous, Change.EXPIRED)
    announce(grant, if (replaced) Change.REPLACED else Change.GRANTED)
    return grant
  }

  fun revokeAll(owner: Initiator, playerId: UUID): Int =
    active(playerId).count { it.owner() == owner && it.revoke() }

  fun revokeAll(owner: Initiator): Int = grants.keys.toList().sumOf { revokeAll(owner, it) }

  fun addExemption(uuid: UUID, durationMillis: Long, sender: String) {
    removeExemption(uuid)
    val duration = if (durationMillis == -1L) null else Duration.ofMillis(durationMillis)
    grant(CommandInitiator(sender), uuid, ExemptionScope.ENFORCEMENT, duration, null)
  }

  fun removeExemption(uuid: UUID): Boolean =
    active(uuid).count { it.owner().kind() == Initiator.Kind.COMMAND && it.revoke() } > 0

  fun getExpiryTime(uuid: UUID): Long? {
    val grant = active(uuid).firstOrNull { it.owner().kind() == Initiator.Kind.COMMAND }
    return grant?.let { it.expiresAt()?.toEpochMilli() ?: -1L }
  }

  fun sweep() {
    for ((playerId, owned) in grants) {
      for (grant in owned.values) {
        if (grant.expired() && grant.deactivate()) {
          owned.remove(grant.key, grant)
          announce(grant, Change.EXPIRED)
        }
      }
      grants.computeIfPresent(playerId) { _, current -> current.takeIf { it.isNotEmpty() } }
    }
  }

  @EventHandler
  fun onPluginDisable(event: PluginDisableEvent) {
    revokeAll(PluginInitiator(event.plugin))
  }

  private fun active(playerId: UUID): List<Grant> =
    grants[playerId]?.values?.filter { it.isActive() }.orEmpty()

  private fun announce(grant: Grant, change: Change) {
    if (!events.wants(ExemptionChangeEvent::class.java)) return
    events.publish(ExemptionChangeEventImpl(nameOf(server, null, grant.playerId()), grant, change))
  }

  private data class Key(val owner: Initiator, val scope: ExemptionScope)

  private inner class Grant(
    private val owner: Initiator,
    private val playerId: UUID,
    private val scope: ExemptionScope,
    private val grantedAt: Instant,
    private val expiresAt: Instant?,
    private val reason: String?,
  ) : Exemption {
    val key = Key(owner, scope)
    private val active = AtomicBoolean(true)

    fun expired(): Boolean = expiresAt != null && Instant.now().isAfter(expiresAt)

    fun deactivate(): Boolean = active.compareAndSet(true, false)

    override fun owner(): Initiator = owner

    override fun playerId(): UUID = playerId

    override fun scope(): ExemptionScope = scope

    override fun grantedAt(): Instant = grantedAt

    override fun expiresAt(): Instant? = expiresAt

    override fun reason(): String? = reason

    override fun isActive(): Boolean = active.get() && !expired()

    override fun revoke(): Boolean {
      val lapsed = expired()
      if (!deactivate()) return false
      grants[playerId]?.remove(key, this)
      announce(this, if (lapsed) Change.EXPIRED else Change.REVOKED)
      return !lapsed
    }
  }

  companion object {
    const val EXEMPT_PERMISSION = "shard.exempt"
    const val DISABLE_PERMISSION = "shard.disable"
  }
}
