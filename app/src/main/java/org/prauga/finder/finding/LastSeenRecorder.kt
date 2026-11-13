// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.prauga.finder.bluetooth.NearbyDevice
import org.prauga.finder.bluetooth.NearbyKey
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.LastSeen

/** Remembers where this phone was whenever it hears a paired phone, at most once a minute each. */
class LastSeenRecorder(
    private val scope: CoroutineScope,
    private val nearby: StateFlow<List<NearbyDevice>>,
    private val identity: IdentityRepository,
    private val locations: LocationFinder,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastRecorded = HashMap<String, Long>()

    fun start() {
        scope.launch {
            nearby.collect { devices ->
                val now = wallMillis()
                for (device in devices) {
                    val key = device.key as? NearbyKey.Peer ?: continue
                    val previous = lastRecorded[key.deviceIdHex]
                    if (previous != null && now - previous < INTERVAL_MILLIS) continue
                    lastRecorded[key.deviceIdHex] = now
                    scope.launch { record(key.deviceIdHex, device.rssi, now) }
                }
            }
        }
    }

    private suspend fun record(deviceIdHex: String, rssi: Int, at: Long) {
        val location = locations.locate()
        identity.recordSeen(
            deviceIdHex,
            LastSeen(
                seenAtMillis = at,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracyMetres = location?.takeIf { it.hasAccuracy() }?.accuracy,
                rssi = rssi,
            ),
        )
    }

    private companion object {
        const val INTERVAL_MILLIS = 60_000L
    }
}
