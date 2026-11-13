// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prauga.finder.bluetooth.protocol.TemporaryId

class NearbyTrackerTest {
    private val a = TemporaryId(ByteArray(TemporaryId.SIZE) { 1 })
    private val b = TemporaryId(ByteArray(TemporaryId.SIZE) { 2 })
    private val tracker = NearbyTracker(historyWindowMillis = 10_000, maxHistory = 3)

    private fun seen(id: TemporaryId, at: Long, rssi: Int = -50) =
        Sighting(id, rssi, at, txPower = null)

    @Test
    fun `first sighting is new, later ones update the device`() {
        assertTrue(tracker.record(seen(a, 1_000, -70)))
        assertFalse(tracker.record(seen(a, 2_000, -40)))

        val device = tracker.snapshot().single()
        assertEquals(-40, device.rssi)
        assertEquals(1_000, device.firstSeenMillis)
        assertEquals(2_000, device.lastSeenMillis)
        assertEquals(2, device.sightings)
    }

    @Test
    fun `keeps devices in the order first heard`() {
        tracker.record(seen(b, 1_000))
        tracker.record(seen(a, 2_000))
        tracker.record(seen(b, 3_000))

        assertEquals(listOf(NearbyKey.Unknown(b), NearbyKey.Unknown(a)), tracker.snapshot().map { it.key })
    }

    @Test
    fun `a late sighting does not move last seen or the current RSSI back`() {
        tracker.record(seen(a, 5_000, -40))
        tracker.record(seen(a, 4_000, -90))

        val device = tracker.snapshot().single()
        assertEquals(5_000, device.lastSeenMillis)
        assertEquals(-40, device.rssi)
        assertEquals(listOf(4_000L, 5_000L), device.history.map { it.elapsedMillis })
    }

    @Test
    fun `history keeps the window and the size limit`() {
        listOf(0L, 9_000, 10_500, 11_000, 12_000).forEach { tracker.record(seen(a, it)) }

        assertEquals(
            listOf(10_500L, 11_000, 12_000),
            tracker.snapshot().single().history.map { it.elapsedMillis },
        )
    }

    @Test
    fun `prune drops devices silent for longer than the timeout`() {
        tracker.record(seen(a, 1_000))
        tracker.record(seen(b, 5_000))

        assertEquals(emptyList<NearbyKey>(), tracker.prune(now = 31_000, timeoutMillis = 30_000))
        assertEquals(listOf(NearbyKey.Unknown(a)), tracker.prune(now = 31_001, timeoutMillis = 30_000))
        assertEquals(listOf(NearbyKey.Unknown(b)), tracker.snapshot().map { it.key })
    }

    @Test
    fun `sightings of one peer under rotating identifiers stay one device`() {
        val peer = NearbyKey.Peer("aa")
        assertTrue(tracker.record(seen(a, 1_000, -60), peer))
        assertFalse(tracker.record(seen(b, 2_000, -50), peer))

        val device = tracker.snapshot().single()
        assertEquals(peer, device.key)
        assertEquals(b, device.advertisedId)
        assertEquals(2, device.sightings)
    }
}
