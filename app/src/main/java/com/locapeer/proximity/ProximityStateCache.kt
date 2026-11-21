package com.locapeer.proximity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Storage for [ProximityStateCache]; [ProximityStateStore] is the production implementation. */
internal interface ProximityStatePersistence {
    suspend fun read(peerId: String): ProximityPeerState?
    suspend fun save(peerId: String, state: ProximityPeerState)
    suspend fun clear(peerId: String)
}

/** A peer's state at the moment an evaluation began, plus the generation it belongs to. */
internal data class ProximityStateSnapshot(
    val generation: Long,
    val nearby: Boolean?,
    val lastNotifiedAtMs: Long
)

/**
 * Per-peer proximity state, serialized against removal.
 *
 * [remove] awaits the persisted clear and bumps the peer's generation. A save from an
 * evaluation that started before the removal still carries the old generation and is
 * refused, so a fire-and-forget clear can't be overtaken by stale nearby state; an
 * evaluation that starts after the removal seeds again from the (cleared) store.
 */
internal class ProximityStateCache(private val persistence: ProximityStatePersistence) {

    private val mutex = Mutex()
    private val states = mutableMapOf<String, ProximityPeerState?>()
    private val generations = mutableMapOf<String, Long>()

    suspend fun snapshot(peerId: String): ProximityStateSnapshot = mutex.withLock {
        if (!states.containsKey(peerId)) states[peerId] = persistence.read(peerId)
        val saved = states[peerId]
        ProximityStateSnapshot(
            generation = generations[peerId] ?: 0L,
            nearby = saved?.nearby,
            lastNotifiedAtMs = saved?.lastNotifiedAtMs ?: 0L
        )
    }

    /** Persists [state] unless the peer was removed after [snapshot] was taken. */
    suspend fun save(peerId: String, snapshot: ProximityStateSnapshot, state: ProximityPeerState) {
        mutex.withLock {
            if ((generations[peerId] ?: 0L) != snapshot.generation) return@withLock
            states[peerId] = state
            persistence.save(peerId, state)
        }
    }

    /** Forgets the peer in memory and awaits the persisted clear, refusing older saves. */
    suspend fun remove(peerId: String) {
        mutex.withLock {
            generations[peerId] = (generations[peerId] ?: 0L) + 1
            states.remove(peerId)
            persistence.clear(peerId)
        }
    }
}
