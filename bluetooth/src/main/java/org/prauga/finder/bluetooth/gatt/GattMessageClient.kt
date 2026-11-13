// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.pairing.Framing

/** Connects to a [GattMessageServer]'s characteristic on a known device, retrying a few times. */
@SuppressLint("MissingPermission")
class GattMessageClient(private val context: Context, private val log: DiagnosticsLog) {
    suspend fun connect(
        device: BluetoothDevice,
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ): MessageLink {
        var lastError: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val link = ClientLink(device, serviceUuid, characteristicUuid)
            try {
                withTimeout(CONNECT_TIMEOUT_MILLIS) { link.open() }
                return link
            } catch (e: TimeoutCancellationException) {
                lastError = IOException("Timed out connecting", e)
                link.close()
                log.add(DiagnosticEvent.Kind.ERROR, "Connection attempt ${attempt + 1} timed out")
            } catch (e: IOException) {
                lastError = e
                link.close()
                log.add(DiagnosticEvent.Kind.ERROR, "Connection attempt ${attempt + 1}: ${e.message}")
                delay(RETRY_DELAY_MILLIS)
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }
        throw lastError ?: IOException("Could not connect")
    }

    private inner class ClientLink(
        private val device: BluetoothDevice,
        private val serviceUuid: UUID,
        private val characteristicUuid: UUID,
    ) : MessageLink {
        private var gatt: BluetoothGatt? = null
        private var characteristic: BluetoothGattCharacteristic? = null
        private var mtu = DEFAULT_MTU
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
                    mtuChanged.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU)
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
                g.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
                    ?: throw IOException("No such service")
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
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 1_000L
        const val REQUESTED_MTU = 247
        const val DEFAULT_MTU = 23
    }
}
