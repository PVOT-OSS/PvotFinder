// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.prauga.finder.bluetooth.protocol.AdvertisementPayload
import org.prauga.finder.bluetooth.protocol.TemporaryId

data class DiscoveryStatus(
    val adapter: AdapterState,
    val permissions: PermissionState,
    val foreground: Boolean,
    val wantAdvertising: Boolean,
    val wantScanning: Boolean,
    val advertiser: AdvertiserState,
    val scanner: ScannerState,
)

private data class Wants(
    val adapter: AdapterState,
    val permissions: PermissionState,
    val foreground: Boolean,
    val advertising: Boolean,
    val scanning: Boolean,
)

/**
 * Runs advertising and scanning while the user wants them, Bluetooth is on, permissions are
 * granted and the app is in the foreground, and keeps the list of nearby devices.
 */
class DiscoveryController(
    private val scope: CoroutineScope,
    adapterStates: Flow<AdapterState>,
    private val permissionSource: () -> PermissionState,
    private val advertiser: BleAdvertiser,
    private val scanner: BleScanner,
    private val log: DiagnosticsLog,
    private val elapsedClock: () -> Long,
    private val newId: () -> TemporaryId = { TemporaryId.random() },
    private val tickMillis: Long = 1_000,
) {
    private val adapter = MutableStateFlow(AdapterState.OFF)
    private val permissions = MutableStateFlow(permissionSource())
    private val foreground = MutableStateFlow(false)
    private val wantAdvertising = MutableStateFlow(false)
    private val wantScanning = MutableStateFlow(false)

    private val _timeoutMillis = MutableStateFlow(DEFAULT_TIMEOUT_MILLIS)
    val timeoutMillis: StateFlow<Long> = _timeoutMillis.asStateFlow()

    private val tracker = NearbyTracker()
    private val lastLogged = HashMap<TemporaryId, Long>()
    private val _nearby = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearby: StateFlow<List<NearbyDevice>> = _nearby.asStateFlow()

    private val wants =
        combine(adapter, permissions, foreground, wantAdvertising, wantScanning, ::Wants)

    val status: StateFlow<DiscoveryStatus> =
        combine(wants, advertiser.state, scanner.state) { w, adv, scan ->
                DiscoveryStatus(
                    w.adapter,
                    w.permissions,
                    w.foreground,
                    w.advertising,
                    w.scanning,
                    adv,
                    scan,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, snapshot())

    init {
        scope.launch {
            adapterStates.collect { state ->
                log.add(DiagnosticEvent.Kind.ADAPTER, "Bluetooth $state")
                adapter.value = state
                if (state != AdapterState.ON) {
                    advertiser.reset()
                    scanner.reset()
                }
                reconcile()
            }
        }
        scope.launch { scanner.sightings.collect(::onSighting) }
        scope.launch {
            while (isActive) {
                delay(tickMillis)
                prune()
            }
        }
    }

    fun setAdvertising(on: Boolean) {
        wantAdvertising.value = on
        reconcile()
    }

    fun setScanning(on: Boolean) {
        wantScanning.value = on
        reconcile()
    }

    fun setForeground(on: Boolean) {
        foreground.value = on
        reconcile()
    }

    fun setTimeout(millis: Long) {
        _timeoutMillis.value = millis
        prune()
    }

    fun refreshPermissions() {
        val current = permissionSource()
        if (current != permissions.value) {
            log.add(
                DiagnosticEvent.Kind.PERMISSION,
                "Scan ${grant(current.canScan)}, advertise ${grant(current.canAdvertise)}, " +
                    "connect ${grant(current.canConnect)}",
            )
        }
        permissions.value = current
        reconcile()
    }

    private fun reconcile() {
        val on = adapter.value == AdapterState.ON
        val ready = on && foreground.value

        val advertiserState = advertiser.state.value
        val advertising =
            advertiserState is AdvertiserState.Starting ||
                advertiserState is AdvertiserState.Advertising
        if (wantAdvertising.value && ready && permissions.value.canAdvertise) {
            if (!advertising) advertiser.start(AdvertisementPayload(newId()))
        } else if (advertiserState != AdvertiserState.Stopped) {
            if (on) advertiser.stop() else advertiser.reset()
        }

        val scannerState = scanner.state.value
        val scanning =
            scannerState is ScannerState.Scanning || scannerState is ScannerState.Waiting
        if (wantScanning.value && ready && permissions.value.canScan) {
            if (!scanning) scanner.start()
        } else if (scannerState != ScannerState.Stopped) {
            if (on) scanner.stop() else scanner.reset()
        }
    }

    private fun onSighting(sighting: Sighting) {
        val id = sighting.temporaryId
        if (tracker.record(sighting)) {
            log.add(DiagnosticEvent.Kind.SIGHTING, "New device, ${sighting.rssi} dBm", id.toHex())
            lastLogged[id] = sighting.elapsedMillis
        } else if (sighting.elapsedMillis - (lastLogged[id] ?: 0) >= SIGHTING_LOG_MILLIS) {
            log.add(DiagnosticEvent.Kind.SIGHTING, "Seen, ${sighting.rssi} dBm", id.toHex())
            lastLogged[id] = sighting.elapsedMillis
        }
        _nearby.value = tracker.snapshot()
    }

    private fun prune() {
        val gone = tracker.prune(elapsedClock(), _timeoutMillis.value)
        if (gone.isEmpty()) return
        gone.forEach { id ->
            lastLogged.remove(id)
            log.add(DiagnosticEvent.Kind.SIGHTING, "Device out of range", id.toHex())
        }
        _nearby.value = tracker.snapshot()
    }

    private fun snapshot() =
        DiscoveryStatus(
            adapter.value,
            permissions.value,
            foreground.value,
            wantAdvertising.value,
            wantScanning.value,
            advertiser.state.value,
            scanner.state.value,
        )

    private fun grant(granted: Boolean) = if (granted) "granted" else "denied"

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val SIGHTING_LOG_MILLIS = 2_000L
    }
}
