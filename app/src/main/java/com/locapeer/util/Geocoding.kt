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
 * Shared reverse-geocoding helper for the map pin sheet and the history report.
 * Sends the queried coordinates to the OS geocoding backend, so callers must keep
 * it behind the reverse-geocoding opt-in setting.
 */
object Geocoding {

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
