// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prauga.finder.bluetooth.protocol.AdvertisementPayload
import org.prauga.finder.bluetooth.protocol.RotatingId
import org.prauga.finder.bluetooth.protocol.TemporaryId

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryControllerTest {
    private class FakeAdvertiser : BleAdvertiser {
        override val state = MutableStateFlow<AdvertiserState>(AdvertiserState.Stopped)
        val started = mutableListOf<AdvertisementPayload>()
        var stops = 0
        var resets = 0

        override fun start(payload: AdvertisementPayload) {
            started += payload
            state.value = AdvertiserState.Advertising(payload)
        }

        override fun stop() {
            stops++
            state.value = AdvertiserState.Stopped
        }

        override fun reset() {
            resets++
            state.value = AdvertiserState.Stopped
        }
    }

    private class FakeScanner : BleScanner {
        override val state = MutableStateFlow<ScannerState>(ScannerState.Stopped)
        override val sightings = MutableSharedFlow<Sighting>(extraBufferCapacity = 16)
        var starts = 0
        var stops = 0
        var resets = 0

        override fun start() {
            starts++
            state.value = ScannerState.Scanning
        }

        override fun stop() {
            stops++
            state.value = ScannerState.Stopped
        }

        override fun reset() {
            resets++
            state.value = ScannerState.Stopped
        }
    }

    private val adapter = MutableStateFlow(AdapterState.ON)
    private var permissions = PermissionState(canScan = true, canAdvertise = true)
    private val advertiser = FakeAdvertiser()
    private val scanner = FakeScanner()
    private var now = 0L
    private var nextId = 0
    private val idRequests = mutableListOf<Long>()
    private val wallStart = 10L * RotatingId.PERIOD_SECONDS + 1_000

    private fun TestScope.controller(): DiscoveryController =
        DiscoveryController(
            scope = backgroundScope,
            adapterStates = adapter,
            permissionSource = { permissions },
            advertiser = advertiser,
            scanner = scanner,
            log = DiagnosticsLog(logcat = false),
            elapsedClock = { now },
            advertisedId = { seconds ->
                idRequests += seconds
                TemporaryId(ByteArray(TemporaryId.SIZE) { (nextId++).toByte() })
            },
            wallSeconds = { wallStart + testScheduler.currentTime / 1_000 },
        )

    private fun runControllerTest(body: suspend TestScope.(DiscoveryController) -> Unit) =
        runTest(UnconfinedTestDispatcher()) { body(controller()) }

    @Test
    fun `nothing runs until wanted and in the foreground`() = runControllerTest { c ->
        c.setAdvertising(true)
        c.setScanning(true)
        assertEquals(0, advertiser.started.size)
        assertEquals(0, scanner.starts)

        c.setForeground(true)

        assertEquals(1, advertiser.started.size)
        assertEquals(1, scanner.starts)
    }

    @Test
    fun `leaving the foreground stops both`() = runControllerTest { c ->
        c.setForeground(true)
        c.setAdvertising(true)
        c.setScanning(true)

        c.setForeground(false)

        assertEquals(1, advertiser.stops)
        assertEquals(1, scanner.stops)
        assertEquals(AdvertiserState.Stopped, c.status.value.advertiser)
    }

    @Test
    fun `turning Bluetooth off resets, turning it on restarts with a new ID`() =
        runControllerTest { c ->
            c.setForeground(true)
            c.setAdvertising(true)
            c.setScanning(true)

            adapter.value = AdapterState.TURNING_OFF
            assertEquals(1, advertiser.resets)
            assertEquals(1, scanner.resets)

            adapter.value = AdapterState.ON
            assertEquals(2, advertiser.started.size)
            assertEquals(2, scanner.starts)
            assertNotEquals(advertiser.started[0].temporaryId, advertiser.started[1].temporaryId)
        }

    @Test
    fun `missing permissions keep each side off until granted`() = runControllerTest { c ->
        permissions = PermissionState(canScan = false, canAdvertise = true)
        c.refreshPermissions()
        c.setForeground(true)
        c.setAdvertising(true)
        c.setScanning(true)
        assertEquals(1, advertiser.started.size)
        assertEquals(0, scanner.starts)

        permissions = PermissionState(canScan = true, canAdvertise = false)
        c.refreshPermissions()

        assertEquals(1, scanner.starts)
        assertEquals(1, advertiser.stops)
    }

    @Test
    fun `a failure is retried on the next change`() = runControllerTest { c ->
        c.setForeground(true)
        c.setScanning(true)
        scanner.state.value = ScannerState.Failed(ScanError.INTERNAL_ERROR)

        c.setScanning(false)
        c.setScanning(true)

        assertEquals(2, scanner.starts)
    }

    @Test
    fun `sightings fill the list and silent devices time out`() = runControllerTest { c ->
        val id = TemporaryId(ByteArray(TemporaryId.SIZE) { 7 })
        c.setTimeout(10_000)

        scanner.sightings.emit(Sighting(id, -60, elapsedMillis = 0, txPower = null))
        scanner.sightings.emit(Sighting(id, -50, elapsedMillis = 1_000, txPower = null))
        assertEquals(-50, c.nearby.value.single().rssi)

        now = 11_000
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, c.nearby.value.size)

        now = 11_001
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(c.nearby.value.isEmpty())
    }

    @Test
    fun `advertising restarts with a new identifier when the period ends`() = runControllerTest { c ->
        c.setForeground(true)
        c.setAdvertising(true)
        assertEquals(1, advertiser.started.size)

        advanceTimeBy(23_000)
        runCurrent()
        assertEquals(1, advertiser.started.size)

        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(2, advertiser.started.size)
        assertEquals(1, advertiser.stops)
        assertEquals(RotatingId.period(idRequests[0]) + 1, RotatingId.period(idRequests[1]))
        assertNotEquals(advertiser.started[0].temporaryId, advertiser.started[1].temporaryId)
    }

    @Test
    fun `background advertising keeps advertising without a screen but stops scanning`() =
        runControllerTest { c ->
            c.setAdvertising(true)
            c.setScanning(true)
            c.setBackgroundAdvertising(true)

            assertEquals(1, advertiser.started.size)
            assertEquals(0, scanner.starts)
        }

    @Test
    fun `sightings of a paired phone are grouped under it`() = runControllerTest { c ->
        val first = TemporaryId(ByteArray(TemporaryId.SIZE) { 1 })
        val second = TemporaryId(ByteArray(TemporaryId.SIZE) { 2 })
        val stranger = TemporaryId(ByteArray(TemporaryId.SIZE) { 3 })
        c.resolver = PeerResolver { if (it == first || it == second) "peer" else null }

        scanner.sightings.emit(Sighting(first, -60, elapsedMillis = 0, txPower = null))
        scanner.sightings.emit(Sighting(second, -50, elapsedMillis = 10, txPower = null))
        scanner.sightings.emit(Sighting(stranger, -70, elapsedMillis = 20, txPower = null))

        assertEquals(
            listOf(NearbyKey.Peer("peer"), NearbyKey.Unknown(stranger)),
            c.nearby.value.map { it.key },
        )
        assertEquals(second, c.nearby.value.first().advertisedId)
    }
}
