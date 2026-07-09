package com.locapeer.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Shared geocoding helper for the map pin sheet, the history report, and the
 * geofence editor's address search.
 *
 * Both directions query the OS geocoding backend (Google on most devices):
 * - [reverseGeocode] sends *tracked coordinates* off-device, so callers must keep
 *   it behind the reverse-geocoding opt-in setting.
 * - [forwardGeocode] sends only the text the user just typed, and only when they
 *   explicitly run a search, so it is not gated behind that setting.
 */
object Geocoding {

    /** A forward-geocoding match: a display label plus the resolved coordinate. */
    data class Match(val label: String, val lat: Double, val lng: Double)

    /** Whether the device has a geocoding backend at all (emulators/de-Googled ROMs may not). */
    fun isAvailable(): Boolean = Geocoder.isPresent()

    /**
     * Resolves a free-text address/place query to candidate coordinates.
     * Returns an empty list when the geocoder is unavailable, errors, or finds nothing.
     */
    @Suppress("DEPRECATION")
    suspend fun forwardGeocode(context: Context, query: String, maxResults: Int = 5): List<Match> =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext emptyList()
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        // Resume on both callbacks; see reverseGeocode for why onError matters.
                        geocoder.getFromLocationName(query, maxResults, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses)
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        })
                    }
                } else {
                    geocoder.getFromLocationName(query, maxResults) ?: emptyList()
                }
                addresses.mapNotNull { addr ->
                    if (!addr.hasLatitude() || !addr.hasLongitude()) return@mapNotNull null
                    val label = addr.getAddressLine(0)?.takeIf { it.isNotBlank() }
                        ?: formatAddress(addr)
                        ?: return@mapNotNull null
                    Match(label, addr.latitude, addr.longitude)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    /** Street-level address for a coordinate, or null when unavailable/failed. */
    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        // Resume on both callbacks: on failure the platform calls onError instead
                        // of onGeocode, and without an onError branch the continuation would never
                        // resume, hanging the lookup (and any loop awaiting it).
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                } else {
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                }
                addr?.let { formatAddress(it) }
            } catch (e: Exception) {
                null
            }
        }

    private fun formatAddress(addr: Address): String? {
        val parts = listOfNotNull(addr.thoroughfare, addr.locality, addr.adminArea)
            .joinToString(", ")
        // getAddressLine(0) can be null; treat null/blank as "no address" so callers
        // get null instead of an empty label.
        return (parts.ifBlank { addr.getAddressLine(0) })?.takeIf { it.isNotBlank() }
    }
}
