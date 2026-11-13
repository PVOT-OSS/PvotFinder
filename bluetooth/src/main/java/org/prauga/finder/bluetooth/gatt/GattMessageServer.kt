// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.pairing.Framing
import org.prauga.finder.pairing.MessageChannel

internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** One connection carrying framed messages. */
interface MessageLink : MessageChannel {
    fun close()
}

/**
 * A GATT server with one characteristic: clients write framed messages and receive them as
 * notifications. Each client that connects and subscribes becomes one [MessageLink] from [accept].
 */
@SuppressLint("MissingPermission")
class GattMessageServer(
    private val context: Context,
    private val manager: BluetoothManager,
    private val log: DiagnosticsLog,
    private val serviceUuid: UUID,
    private val characteristicUuid: UUID,
    private val name: String,
) {
    private var server: BluetoothGattServer? = null
    private val links = ConcurrentHashMap<String, ServerLink>()
    private val accepted = Channel<MessageLink>(Channel.UNLIMITED)
    private val characteristic =
        BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

    /** Opens the GATT server; throws when Bluetooth refuses. */
    suspend fun open() {
        val serviceAdded = CompletableDeferred<Int>()
        val gattServer =
            manager.openGattServer(context, callback(serviceAdded))
                ?: throw IOException("Bluetooth refused the GATT server")
        server = gattServer
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        val service =
            BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        gattServer.addService(service)
        if (serviceAdded.await() != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("Bluetooth refused the $name service")
        }
        log.add(DiagnosticEvent.Kind.ADVERTISER, "$name server open")
    }

    suspend fun accept(): MessageLink = accepted.receive()

    fun close() {
        links.values.forEach { it.fail() }
        links.clear()
        runCatching { server?.close() }
        server = null
        accepted.close()
        log.add(DiagnosticEvent.Kind.ADVERTISER, "$name server closed")
    }

    private fun callback(serviceAdded: CompletableDeferred<Int>) =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                serviceAdded.complete(status)
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    links.remove(device.address)?.fail()
                    log.add(DiagnosticEvent.Kind.ADVERTISER, "$name client disconnected")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log.add(DiagnosticEvent.Kind.ADVERTISER, "$name client connected")
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                link(device).mtu = mtu
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                val value =
                    if (links[device.address]?.subscribed == true) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val ok = descriptor.uuid == CCCD_UUID && !preparedWrite
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        null,
                    )
                }
                if (!ok) return
                val link = link(device)
                val subscribe = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (subscribe && !link.subscribed) {
                    link.subscribed = true
                    accepted.trySend(link)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val ok =
                    characteristic.uuid == characteristicUuid &&
                        !preparedWrite &&
                        offset == 0
                if (responseNeeded) {
                    server?.sendResponse(
                        device,
                        requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        null,
                    )
                }
                if (ok) link(device).received(value)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                links[device.address]?.notificationSent(status)
            }
        }

    private fun link(device: BluetoothDevice): ServerLink =
        links.getOrPut(device.address) { ServerLink(device) }

    private inner class ServerLink(private val device: BluetoothDevice) : MessageLink {
        @Volatile var mtu = DEFAULT_MTU
        @Volatile var subscribed = false
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private val sent = Channel<Int>(Channel.UNLIMITED)
        private val reassembler = Framing.Reassembler()

        fun received(chunk: ByteArray) {
            try {
                reassembler.add(chunk).forEach { incoming.trySend(it) }
            } catch (e: Exception) {
                fail()
            }
        }

        fun notificationSent(status: Int) {
            sent.trySend(status)
        }

        fun fail() {
            incoming.close(IOException("Disconnected"))
            sent.close(IOException("Disconnected"))
        }

        override suspend fun send(message: ByteArray) {
            val gattServer = server ?: throw IOException("Server closed")
            for (chunk in Framing.chunks(message, mtu - ATT_OVERHEAD)) {
                val result = gattServer.notifyCharacteristicChanged(device, characteristic, false, chunk)
                if (result != BluetoothGatt.GATT_SUCCESS) throw IOException("Notify failed: $result")
                val status =
                    try {
                        sent.receive()
                    } catch (e: ClosedReceiveChannelException) {
                        throw IOException("Disconnected", e)
                    }
                if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Notify failed: $status")
            }
        }

        override suspend fun receive(): ByteArray =
            try {
                incoming.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw IOException("Disconnected", e)
            }

        override fun close() {
            links.remove(device.address)
            fail()
            runCatching { server?.cancelConnection(device) }
        }
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val ATT_OVERHEAD = 3
    }
}
