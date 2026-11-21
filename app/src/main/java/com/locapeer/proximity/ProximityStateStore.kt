package com.locapeer.proximity

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// A dedicated DataStore rather than the settings one: the engine rewrites this as
// heartbeats arrive, and the settings flow must not re-emit (recomposing the UI) for
// engine bookkeeping.
private val Context.proximityStateStore by preferencesDataStore(name = "locapeer_proximity_state")

/**
 * Keeps each peer's nearby/cooldown state across process restarts, so a restart mid
 * visit can neither ring the same arrival again nor reset the 10-minute cooldown.
 */
@Singleton
class ProximityStateStore @Inject constructor(@ApplicationContext private val context: Context) :
    ProximityStatePersistence {

    override suspend fun read(peerId: String): ProximityPeerState? =
        decodeProximityPeerState(context.proximityStateStore.data.first()[key(peerId)])

    override suspend fun save(peerId: String, state: ProximityPeerState) {
        val encoded = encodeProximityPeerState(state)
        context.proximityStateStore.edit { prefs -> prefs[key(peerId)] = encoded }
    }

    override suspend fun clear(peerId: String) {
        context.proximityStateStore.edit { prefs -> prefs.remove(key(peerId)) }
    }

    private fun key(peerId: String) = stringPreferencesKey("peer_$peerId")
}
