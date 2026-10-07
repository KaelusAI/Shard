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

import ac.shard.api.event.Cancellable
import ac.shard.api.event.EventBus
import ac.shard.api.event.Priority
import ac.shard.api.event.ShardEvent
import ac.shard.api.event.Subscription
import ac.shard.api.event.SubscriptionBuilder
import ac.shard.api.event.detection.VerdictEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin

abstract class CancellableEvent : Cancellable {
  private val cancelled = AtomicBoolean()
  @Volatile internal var inMonitor = false
  @Volatile private var finished = false

  override fun isCancelled(): Boolean = cancelled.get()

  override fun setCancelled(cancelled: Boolean) {
    check(!inMonitor) { "A monitor handler may not change the outcome" }
    check(!finished) { "Dispatch of this event has ended" }
    this.cancelled.set(cancelled)
  }

  internal fun finish() {
    inMonitor = false
    finished = true
  }
}

@Suppress("TooManyFunctions")
class ShardEvents(private val logger: Logger) : EventBus, Listener {
  private val sequence = AtomicLong()
  private val subscriptions = CopyOnWriteArrayList<Sub<*>>()
  @Volatile private var routes = ConcurrentHashMap<Class<*>, Array<Sub<*>>>()
  private val queue = ArrayBlockingQueue<ShardEvent>(QUEUE_CAPACITY)
  private val lastDropWarning = AtomicLong()
  @Volatile private var closed = false
  private val worker: Thread by lazy {
    Thread(::drain, "Shard-Events").apply {
      isDaemon = true
      start()
    }
  }

  override fun <E : ShardEvent> subscribe(
    owner: Plugin,
    type: Class<E>,
    handler: Consumer<in E>,
  ): Subscription = subscription(owner, type).subscribe(handler)

  override fun <E : ShardEvent> subscription(
    owner: Plugin,
    type: Class<E>,
  ): SubscriptionBuilder<E> = Builder(owner, type)

  override fun subscriptions(owner: Plugin): List<Subscription> =
    java.util.Collections.unmodifiableList(subscriptions.filter { it.owner() === owner })

  override fun unsubscribeAll(owner: Plugin): Int =
    subscriptions.filter { it.owner() === owner }.count { it.deactivate() }

  fun wants(type: Class<out ShardEvent>): Boolean = route(type).isNotEmpty()

  fun <E : ShardEvent> fire(event: E): E {
    dispatch(event)
    return event
  }

  fun publish(event: ShardEvent) {
    if (closed || !wants(event.javaClass)) return
    worker
    val queued =
      queue.offer(event) || (event !is VerdictEvent && dropOneVerdict() && queue.offer(event))
    if (!queued) warnDropped()
  }

  @EventHandler
  fun onPluginDisable(event: PluginDisableEvent) {
    unsubscribeAll(event.plugin)
  }

  fun shutdown() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_SECONDS)
    while (queue.isNotEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(DRAIN_POLL_MILLIS)
    }
    closed = true
    queue.clear()
    subscriptions.toList().forEach { it.deactivate() }
  }

  private fun dropOneVerdict(): Boolean {
    val iterator = queue.iterator()
    while (iterator.hasNext()) {
      if (iterator.next() is VerdictEvent) {
        iterator.remove()
        return true
      }
    }
    return false
  }

  private fun warnDropped() {
    val now = System.currentTimeMillis()
    val last = lastDropWarning.get()
    if (now - last >= DROP_WARNING_MILLIS && lastDropWarning.compareAndSet(last, now)) {
      logger.warning("Shard API event queue is full, a slow event handler is dropping events")
    }
  }

  private fun drain() {
    try {
      while (!closed) {
        queue.poll(1, TimeUnit.SECONDS)?.let(::dispatch)
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun route(type: Class<*>): Array<Sub<*>> =
    routes.computeIfAbsent(type) {
      subscriptions
        .filter { sub -> sub.isActive() && sub.eventType().isAssignableFrom(type) }
        .sortedWith(compareBy<Sub<*>>({ it.isMonitor() }, { it.priority() }, { it.order }))
        .toTypedArray()
    }

  @Suppress("TooGenericExceptionCaught")
  private fun dispatch(event: ShardEvent) {
    val decision = event as? CancellableEvent
    for (sub in route(event.javaClass)) {
      val skipped = sub.skipCancelled && decision?.isCancelled == true
      if (!sub.isActive() || skipped) continue
      decision?.inMonitor = sub.isMonitor()
      try {
        sub.call(event)
      } catch (error: VirtualMachineError) {
        throw error
      } catch (error: Throwable) {
        sub
          .owner()
          .logger
          .log(Level.SEVERE, "Handler for Shard ${event.javaClass.simpleName} failed", error)
      }
    }
    decision?.finish()
  }

  private fun invalidate() {
    routes = ConcurrentHashMap()
  }

  private inner class Builder<E : ShardEvent>(
    private val owner: Plugin,
    private val type: Class<E>,
  ) : SubscriptionBuilder<E> {
    private var priority = Priority.NORMAL
    private var ignoreCancelled = false
    private var monitor = false

    override fun priority(priority: Int): SubscriptionBuilder<E> = apply {
      this.priority = priority
    }

    override fun ignoreCancelled(ignore: Boolean): SubscriptionBuilder<E> = apply {
      ignoreCancelled = ignore
    }

    override fun monitor(): SubscriptionBuilder<E> = apply { monitor = true }

    override fun subscribe(handler: Consumer<in E>): Subscription {
      require(owner.isEnabled) { "${owner.name} is not enabled" }
      val sub =
        Sub(owner, type, priority, monitor, ignoreCancelled, handler, sequence.incrementAndGet())
      subscriptions += sub
      invalidate()
      return sub
    }
  }

  @Suppress("LongParameterList")
  private inner class Sub<E : ShardEvent>(
    private val owner: Plugin,
    private val type: Class<E>,
    private val priority: Int,
    private val monitor: Boolean,
    val skipCancelled: Boolean,
    private val handler: Consumer<in E>,
    val order: Long,
  ) : Subscription {
    private val active = AtomicBoolean(true)

    override fun owner(): Plugin = owner

    override fun eventType(): Class<out ShardEvent> = type

    override fun priority(): Int = priority

    override fun isMonitor(): Boolean = monitor

    override fun isActive(): Boolean = active.get()

    override fun close() {
      deactivate()
    }

    fun deactivate(): Boolean {
      if (!active.compareAndSet(true, false)) return false
      subscriptions.remove(this)
      invalidate()
      return true
    }

    fun call(event: ShardEvent) {
      handler.accept(type.cast(event))
    }
  }

  private companion object {
    const val QUEUE_CAPACITY = 65_536
    const val DRAIN_SECONDS = 2L
    const val DRAIN_POLL_MILLIS = 10L
    const val DROP_WARNING_MILLIS = 60_000L
  }
}
