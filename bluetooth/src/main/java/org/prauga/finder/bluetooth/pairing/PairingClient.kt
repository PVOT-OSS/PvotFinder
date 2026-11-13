// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.ScanStartGuard
import org.prauga.finder.pairing.Framing
import org.prauga.finder.pairing.PairingProtocol

/** The scanning phone's side: finds the showing phone's session and connects to it. */
@SuppressLint("MissingPermission")
class PairingClient(
    private val context: Context,
    private val manager: BluetoothManager,
    private val guard: ScanStartGuard,
    private val log: DiagnosticsLog,
) {
    /** Finds the phone advertising [sessionId] and opens a link to it. */
    suspend fun connect(sessionId: ByteArray): PairingLink {
        val device = withTimeout(FIND_TIMEOUT_MILLIS) { find(sessionId) }
        log.add(DiagnosticEvent.Kind.SCANNER, "Pairing phone found")
        var lastError: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val link = ClientLink(device)
            try {
                withTimeout(CONNECT_TIMEOUT_MILLIS) { link.open() }
                return link
            } catch (e: TimeoutCancellationException) {
                lastError = IOException("Timed out connecting", e)
                link.close()
                log.add(DiagnosticEvent.Kind.ERROR, "Pairing connection attempt ${attempt + 1} timed out")
            } catch (e: IOException) {
                lastError = e
                link.close()
                log.add(DiagnosticEvent.Kind.ERROR, "Pairing connection attempt ${attempt + 1}: ${e.message}")
                delay(RETRY_DELAY_MILLIS)
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }
        throw lastError ?: IOException("Could not connect")
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

    private inner class ClientLink(private val device: BluetoothDevice) : PairingLink {
        private var gatt: BluetoothGatt? = null
        private var characteristic: BluetoothGattCharacteristic? = null
        private var mtu = 23
        private val connected = CompletableDeferred<Int>()
        private val mtuChanged = CompletableDeferred<Int>()
        private val servicesFound = CompletableDeferred<Int>()
        private val descriptorWritten = CompletableDeferred<Int>()
        private val written = Channel<Int>(Channel.UNLIMITED)
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private val reassembler = Framing.Reassembler()

        private val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        connected.complete(status)
                    } else {
                        connected.complete(if (status == BluetoothGatt.GATT_SUCCESS) -1 else status)
                        fail()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    mtuChanged.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    servicesFound.complete(status)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    descriptorWritten.complete(status)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    written.trySend(status)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    try {
                        reassembler.add(value).forEach { incoming.trySend(it) }
                    } catch (e: Exception) {
                        fail()
                    }
                }
            }

        suspend fun open() {
            val g =
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw IOException("connectGatt refused")
            gatt = g
            val status = connected.await()
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Connection failed: $status")
            if (!g.requestMtu(REQUESTED_MTU)) throw IOException("MTU request refused")
            mtu = mtuChanged.await()
            if (!g.discoverServices()) throw IOException("Service discovery refused")
            if (servicesFound.await() != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("Service discovery failed")
            }
            val c =
                g.getService(PairingProtocol.SERVICE_UUID)
                    ?.getCharacteristic(PairingProtocol.MESSAGE_CHARACTERISTIC_UUID)
                    ?: throw IOException("No pairing service")
            characteristic = c
            if (!g.setCharacteristicNotification(c, true)) throw IOException("Notifications refused")
            val cccd = c.getDescriptor(CCCD_UUID) ?: throw IOException("No notification descriptor")
            val result = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (result != BluetoothStatusCodes.SUCCESS) throw IOException("Subscribe refused: $result")
            if (descriptorWritten.await() != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("Subscribe failed")
            }
        }

        fun fail() {
            incoming.close(IOException("Disconnected"))
            written.close(IOException("Disconnected"))
        }

        override suspend fun send(message: ByteArray) {
            val g = gatt ?: throw IOException("Not connected")
            val c = characteristic ?: throw IOException("Not connected")
            for (chunk in Framing.chunks(message, mtu - 3)) {
                val result =
                    g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (result != BluetoothStatusCodes.SUCCESS) throw IOException("Write refused: $result")
                val status = written.receive()
                if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Write failed: $status")
            }
        }

        override suspend fun receive(): ByteArray = incoming.receive()

        override fun close() {
            fail()
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
        }
    }

    private companion object {
        const val FIND_TIMEOUT_MILLIS = 30_000L
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 1_000L
        const val REQUESTED_MTU = 247
    }
}
