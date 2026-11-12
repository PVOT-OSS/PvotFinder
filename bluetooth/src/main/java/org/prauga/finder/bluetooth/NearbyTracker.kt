// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import org.prauga.finder.bluetooth.protocol.TemporaryId

data class RssiSample(val elapsedMillis: Long, val rssi: Int)

data class NearbyDevice(
    val temporaryId: TemporaryId,
    val rssi: Int,
    val txPower: Int?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val sightings: Int,
    val history: List<RssiSample>,
)

/** Devices heard recently, keyed by temporary ID; times are on the elapsed-realtime clock. */
class NearbyTracker(
    private val historyWindowMillis: Long = 60_000,
    private val maxHistory: Int = 600,
) {
    private val devices = LinkedHashMap<TemporaryId, NearbyDevice>()

    /** Returns true when [sighting] is the first from its device. */
    fun record(sighting: Sighting): Boolean {
        val previous = devices[sighting.temporaryId]
        val sample = RssiSample(sighting.elapsedMillis, sighting.rssi)
        if (previous == null) {
            devices[sighting.temporaryId] =
                NearbyDevice(
                    temporaryId = sighting.temporaryId,
                    rssi = sighting.rssi,
                    txPower = sighting.txPower,
                    firstSeenMillis = sighting.elapsedMillis,
                    lastSeenMillis = sighting.elapsedMillis,
                    sightings = 1,
                    history = listOf(sample),
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
        devices[sighting.temporaryId] =
            previous.copy(
                rssi = if (latest) sighting.rssi else previous.rssi,
                txPower = if (latest) sighting.txPower else previous.txPower,
                lastSeenMillis = lastSeen,
                sightings = previous.sightings + 1,
                history = history,
            )
        return false
    }

    /** Drops devices not heard for [timeoutMillis] and returns their IDs. */
    fun prune(now: Long, timeoutMillis: Long): List<TemporaryId> {
        val gone = devices.values.filter { now - it.lastSeenMillis > timeoutMillis }
        gone.forEach { devices.remove(it.temporaryId) }
        return gone.map { it.temporaryId }
    }

    fun clear() {
        devices.clear()
    }

    /** Devices in the order they were first heard, so the list does not jump around. */
    fun snapshot(): List<NearbyDevice> = devices.values.toList()
}
