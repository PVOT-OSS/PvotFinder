// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import android.bluetooth.BluetoothDevice
import org.prauga.finder.bluetooth.protocol.TemporaryId

data class RssiSample(val elapsedMillis: Long, val rssi: Int)

/** Who a sighting belongs to: a paired phone, or an identifier nobody here can place. */
sealed interface NearbyKey {
    data class Peer(val deviceIdHex: String) : NearbyKey

    data class Unknown(val id: TemporaryId) : NearbyKey
}

/** Maps a received identifier to a paired phone's device ID, or null. */
fun interface PeerResolver {
    fun resolve(id: TemporaryId): String?
}

data class NearbyDevice(
    val key: NearbyKey,
    val advertisedId: TemporaryId,
    val rssi: Int,
    val txPower: Int?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val sightings: Int,
    val history: List<RssiSample>,
    val device: BluetoothDevice? = null,
)

/** Devices heard recently; times are on the elapsed-realtime clock. */
class NearbyTracker(
    private val historyWindowMillis: Long = 60_000,
    private val maxHistory: Int = 600,
) {
    private val devices = LinkedHashMap<NearbyKey, NearbyDevice>()

    /** Returns true when [sighting] is the first under [key]. */
    fun record(
        sighting: Sighting,
        key: NearbyKey = NearbyKey.Unknown(sighting.temporaryId),
    ): Boolean {
        val previous = devices[key]
        val sample = RssiSample(sighting.elapsedMillis, sighting.rssi)
        if (previous == null) {
            devices[key] =
                NearbyDevice(
                    key = key,
                    advertisedId = sighting.temporaryId,
                    rssi = sighting.rssi,
                    txPower = sighting.txPower,
                    firstSeenMillis = sighting.elapsedMillis,
                    lastSeenMillis = sighting.elapsedMillis,
                    sightings = 1,
                    history = listOf(sample),
                    device = sighting.device,
                )
            return true
        }
        val latest = sighting.elapsedMillis >= previous.lastSeenMillis
        val lastSeen = maxOf(previous.lastSeenMillis, sighting.elapsedMillis)
        val history =
            (previous.history + sample)
                .sortedBy { it.elapsedMillis }
                .filter { lastSeen - it.elapsedMillis <= historyWindowMillis }
                .takeLast(maxHistory)
        devices[key] =
            previous.copy(
                advertisedId = if (latest) sighting.temporaryId else previous.advertisedId,
                rssi = if (latest) sighting.rssi else previous.rssi,
                txPower = if (latest) sighting.txPower else previous.txPower,
                lastSeenMillis = lastSeen,
                sightings = previous.sightings + 1,
                history = history,
                device = if (latest) sighting.device ?: previous.device else previous.device,
            )
        return false
    }

    /** Drops devices not heard for [timeoutMillis] and returns their keys. */
    fun prune(now: Long, timeoutMillis: Long): List<NearbyKey> {
        val gone = devices.values.filter { now - it.lastSeenMillis > timeoutMillis }
        gone.forEach { devices.remove(it.key) }
        return gone.map { it.key }
    }

    fun clear() {
        devices.clear()
    }

    /** Devices in the order they were first heard, so the list does not jump around. */
    fun snapshot(): List<NearbyDevice> = devices.values.toList()
}
