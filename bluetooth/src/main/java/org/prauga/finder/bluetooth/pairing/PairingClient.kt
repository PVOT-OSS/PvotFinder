// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.ScanStartGuard
import org.prauga.finder.bluetooth.gatt.GattMessageClient
import org.prauga.finder.bluetooth.gatt.MessageLink
import org.prauga.finder.pairing.PairingProtocol

/** The scanning phone's side: finds the showing phone's session and connects to it. */
@SuppressLint("MissingPermission")
class PairingClient(
    context: Context,
    private val manager: BluetoothManager,
    private val guard: ScanStartGuard,
    private val log: DiagnosticsLog,
) {
    private val gatt = GattMessageClient(context, log)

    /** Finds the phone advertising [sessionId] and opens a link to it. */
    suspend fun connect(sessionId: ByteArray): MessageLink {
        val device = withTimeout(FIND_TIMEOUT_MILLIS) { find(sessionId) }
        log.add(DiagnosticEvent.Kind.SCANNER, "Pairing phone found")
        return gatt.connect(
            device,
            PairingProtocol.SERVICE_UUID,
            PairingProtocol.MESSAGE_CHARACTERISTIC_UUID,
        )
    }

    private suspend fun find(sessionId: ByteArray): BluetoothDevice {
        val scanner = manager.adapter?.bluetoothLeScanner ?: throw IOException("Cannot scan")
        val wait = guard.delayBeforeStart(SystemClock.elapsedRealtime())
        if (wait > 0) delay(wait)
        val expected = PairingProtocol.advertisement(sessionId)
        val filter =
            ScanFilter.Builder()
                .setServiceData(
                    ParcelUuid(PairingProtocol.SERVICE_UUID),
                    expected,
                    ByteArray(expected.size) { -1 },
                )
                .build()
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return suspendCancellableCoroutine { continuation ->
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val data =
                            result.scanRecord?.getServiceData(ParcelUuid(PairingProtocol.SERVICE_UUID))
                        if (data != null && data.contentEquals(expected) && continuation.isActive) {
                            runCatching { scanner.stopScan(this) }
                            continuation.resumeWith(Result.success(result.device))
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(IOException("Pairing scan failed: $errorCode"))
                            )
                        }
                    }
                }
            guard.recordStart(SystemClock.elapsedRealtime())
            scanner.startScan(listOf(filter), settings, callback)
            continuation.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
        }
    }

    private companion object {
        const val FIND_TIMEOUT_MILLIS = 30_000L
    }
}
