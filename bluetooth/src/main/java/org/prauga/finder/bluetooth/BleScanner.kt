// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.prauga.finder.bluetooth.protocol.AdvertisementCodec
import org.prauga.finder.bluetooth.protocol.DecodeResult
import org.prauga.finder.bluetooth.protocol.FinderProtocol
import org.prauga.finder.bluetooth.protocol.TemporaryId

/** One received advertisement. [elapsedMillis] is on the [SystemClock.elapsedRealtime] clock. */
data class Sighting(
    val temporaryId: TemporaryId,
    val rssi: Int,
    val elapsedMillis: Long,
    val txPower: Int?,
)

sealed interface ScannerState {
    data object Stopped : ScannerState

    data class Waiting(val untilElapsedMillis: Long) : ScannerState

    data object Scanning : ScannerState

    data class Failed(val error: ScanError) : ScannerState
}

enum class ScanError {
    ALREADY_STARTED,
    APPLICATION_REGISTRATION_FAILED,
    INTERNAL_ERROR,
    FEATURE_UNSUPPORTED,
    OUT_OF_HARDWARE_RESOURCES,
    SCANNING_TOO_FREQUENTLY,
    PERMISSION_DENIED,
    UNKNOWN;

    companion object {
        fun fromCode(code: Int): ScanError =
            when (code) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> ALREADY_STARTED
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    APPLICATION_REGISTRATION_FAILED
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> INTERNAL_ERROR
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> FEATURE_UNSUPPORTED
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> OUT_OF_HARDWARE_RESOURCES
                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> SCANNING_TOO_FREQUENTLY
                else -> UNKNOWN
            }
    }
}

interface BleScanner {
    val state: StateFlow<ScannerState>
    val sightings: SharedFlow<Sighting>

    fun start()

    fun stop()

    /** Forgets the running scan after Bluetooth went off, which ends it silently. */
    fun reset()
}

class AndroidBleScanner(
    private val adapter: BluetoothAdapter?,
    private val permissions: BluetoothPermissions,
    private val log: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val guard: ScanStartGuard = ScanStartGuard(),
) : BleScanner {
    private val _state = MutableStateFlow<ScannerState>(ScannerState.Stopped)
    override val state: StateFlow<ScannerState> = _state.asStateFlow()

    private val _sightings =
        MutableSharedFlow<Sighting>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val sightings: SharedFlow<Sighting> = _sightings.asSharedFlow()

    private val serviceUuid = ParcelUuid(FinderProtocol.SERVICE_UUID)
    private var callback: ScanCallback? = null
    private var pendingStart: Job? = null

    override fun start() {
        if (callback != null || pendingStart != null) return
        val wait = guard.delayBeforeStart(elapsedClock())
        if (wait > 0) {
            _state.value = ScannerState.Waiting(elapsedClock() + wait)
            log.add(DiagnosticEvent.Kind.SCANNER, "Scan held back ${wait / 1000 + 1} s")
            pendingStart =
                scope.launch {
                    delay(wait)
                    pendingStart = null
                    startNow()
                }
        } else {
            startNow()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNow() {
        if (!permissions.current().canScan) return fail(ScanError.PERMISSION_DENIED)
        val scanner = adapter?.bluetoothLeScanner ?: return fail(ScanError.FEATURE_UNSUPPORTED)
        val filters = listOf(ScanFilter.Builder().setServiceData(serviceUuid, ByteArray(0)).build())
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build()
        val cb =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (callback === this) handle(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    if (callback === this) results.forEach(::handle)
                }

                override fun onScanFailed(errorCode: Int) {
                    if (callback !== this) return
                    callback = null
                    fail(ScanError.fromCode(errorCode))
                }
            }
        callback = cb
        guard.recordStart(elapsedClock())
        try {
            scanner.startScan(filters, settings, cb)
            _state.value = ScannerState.Scanning
            log.add(DiagnosticEvent.Kind.SCANNER, "Scanning started")
        } catch (e: SecurityException) {
            callback = null
            fail(ScanError.PERMISSION_DENIED)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        pendingStart?.cancel()
        pendingStart = null
        val cb = callback
        callback = null
        if (cb != null) {
            try {
                if (permissions.current().canScan) adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (e: SecurityException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Stopping the scan was not permitted")
            } catch (e: IllegalStateException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Bluetooth was off when the scan stopped")
            }
            log.add(DiagnosticEvent.Kind.SCANNER, "Scanning stopped")
        }
        _state.value = ScannerState.Stopped
    }

    override fun reset() {
        pendingStart?.cancel()
        pendingStart = null
        if (callback == null && _state.value == ScannerState.Stopped) return
        callback = null
        _state.value = ScannerState.Stopped
        log.add(DiagnosticEvent.Kind.SCANNER, "Scanning ended with Bluetooth")
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(serviceUuid) ?: return
        when (val decoded = AdvertisementCodec.decode(data)) {
            is DecodeResult.Valid -> {
                val sighting =
                    Sighting(
                        temporaryId = decoded.payload.temporaryId,
                        rssi = result.rssi,
                        elapsedMillis = result.timestampNanos / 1_000_000,
                        txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
                    )
                _sightings.tryEmit(sighting)
            }
            is DecodeResult.Invalid ->
                log.add(DiagnosticEvent.Kind.REJECTED, "Advertisement rejected: ${decoded.reason}")
        }
    }

    private fun fail(error: ScanError) {
        _state.value = ScannerState.Failed(error)
        log.add(DiagnosticEvent.Kind.ERROR, "Scanning failed: $error")
    }
}
