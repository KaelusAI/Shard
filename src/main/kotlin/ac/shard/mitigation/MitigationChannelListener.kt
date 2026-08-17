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
@file:Suppress("ReturnCount")

package ac.shard.mitigation

import ac.shard.api.event.MitigationEvent
import ac.shard.player.PlayerDataManager
import ac.shard.player.ShardPlayer
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import kotlin.random.Random
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityRegainHealthEvent

private const val SURVIVES_BY = 0.5

@Suppress("TooManyFunctions")
class MitigationChannelListener(
  private val playerDataManager: PlayerDataManager,
  private val stamps: HitStamps,
  private val random: Random = Random.Default,
) : Listener {

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  fun onDamage(event: EntityDamageByEntityEvent) {
    val victim = event.entity as? Player ?: return
    val direct = event.damager
    val attacker = causingPlayer(direct)

    if (attacker == null) {
      scaleStamped(event, victim, direct?.uniqueId)
      boostIncoming(event, victim)
      return
    }
    if (attacker.uniqueId == victim.uniqueId) return
    boostIncoming(event, victim)

    val shardPlayer = playerDataManager.getPlayer(attacker) ?: return

    if (direct !is Projectile && cancelled(shardPlayer)) {
      event.isCancelled = true
      return
    }

    val channel =
      if (direct is Projectile) MitigationSettings.PROJECTILE else MitigationSettings.MELEE
    scale(event, shardPlayer, channel, multiplierFrom(shardPlayer, direct, channel))
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onLaunch(event: PlayerLaunchProjectileEvent) {
    val shardPlayer = playerDataManager.getPlayer(event.player) ?: return
    stamps.remember(
      event.projectile.uniqueId,
      shardPlayer.uuid,
      multiplier(shardPlayer, MitigationSettings.PROJECTILE),
    )
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onCrystalHit(event: EntityDamageByEntityEvent) {
    val crystal = event.entity as? EnderCrystal ?: return
    val attacker = causingPlayer(event.damager) ?: return
    val shardPlayer = playerDataManager.getPlayer(attacker) ?: return
    stamps.remember(
      crystal.uniqueId,
      shardPlayer.uuid,
      multiplier(shardPlayer, MitigationSettings.CRYSTAL),
    )
  }

  private fun causingPlayer(direct: org.bukkit.entity.Entity?): Player? =
    when (direct) {
      is Player -> direct
      is Projectile -> direct.shooter as? Player
      is TNTPrimed -> direct.source as? Player
      else -> null
    }

  private fun multiplierFrom(
    shardPlayer: ShardPlayer,
    direct: org.bukkit.entity.Entity?,
    channel: String,
  ): Double =
    if (direct is Projectile) {
      stamps.take(direct.uniqueId)?.multiplier ?: 1.0
    } else {
      multiplier(shardPlayer, channel)
    }

  private fun scaleStamped(
    event: EntityDamageByEntityEvent,
    victim: Player,
    subject: java.util.UUID?,
  ) {
    val stamp = subject?.let { stamps.peek(it) } ?: return
    if (stamp.owner == victim.uniqueId) return
    val shardPlayer = playerDataManager.getPlayer(stamp.owner) ?: return
    scale(event, shardPlayer, MitigationSettings.CRYSTAL, stamp.multiplier)
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  fun onHeal(event: EntityRegainHealthEvent) {
    val player = event.entity as? Player ?: return
    val shardPlayer = playerDataManager.getPlayer(player) ?: return
    val multiplier = multiplier(shardPlayer, MitigationSettings.HEALING)
    if (multiplier >= 1.0) return
    if (vetoed(shardPlayer, MitigationSettings.HEALING)) return
    event.amount = event.amount * multiplier
  }

  private fun cancelled(shardPlayer: ShardPlayer): Boolean {
    val chance = shardPlayer.mitigation.chanceFor(MitigationSettings.CANCEL)
    if (chance <= 0.0 || random.nextDouble() >= chance) return false
    return !vetoed(shardPlayer, MitigationSettings.CANCEL)
  }

  private fun boostIncoming(event: EntityDamageByEntityEvent, victim: Player) {
    val shardPlayer = playerDataManager.getPlayer(victim) ?: return
    val multiplier = multiplier(shardPlayer, MitigationSettings.INCOMING)
    if (multiplier <= 1.0) return
    if (vetoed(shardPlayer, MitigationSettings.INCOMING)) return

    val survivable = event.finalDamage < victim.health
    event.damage = event.damage * multiplier

    if (!survivable || event.finalDamage < victim.health) return
    val room = (victim.health - SURVIVES_BY) / event.finalDamage
    event.damage = event.damage * room.coerceIn(0.0, 1.0)
  }

  private fun scale(
    event: EntityDamageByEntityEvent,
    shardPlayer: ShardPlayer,
    channel: String,
    multiplier: Double,
  ) {
    if (multiplier >= 1.0) return
    if (vetoed(shardPlayer, channel)) return
    event.damage = event.damage * multiplier
  }

  private fun multiplier(shardPlayer: ShardPlayer, channel: String): Double =
    shardPlayer.mitigation.multiplierFor(channel)

  private fun vetoed(shardPlayer: ShardPlayer, channel: String): Boolean {
    val state = shardPlayer.mitigation
    val event =
      MitigationEvent(
        shardPlayer.uuid,
        shardPlayer.player.name,
        state.applied?.id ?: channel,
        state.appliedTier.name,
        state.score,
      )
    shardPlayer.eventBus.post(event)
    return event.cancelled
  }
}
