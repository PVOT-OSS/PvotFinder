// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.pairing

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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
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
import org.prauga.finder.pairing.PairingProtocol

internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** One phone connected to the pairing server. */
interface PairingLink : MessageChannel {
    fun close()
}

/**
 * The showing phone's side: advertises the session and serves the pairing characteristic. Each
 * phone that connects and subscribes becomes one [PairingLink] from [accept].
 */
@SuppressLint("MissingPermission")
class PairingServer(
    private val context: Context,
    private val manager: BluetoothManager,
    private val log: DiagnosticsLog,
) {
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val links = ConcurrentHashMap<String, ServerLink>()
    private val accepted = Channel<PairingLink>(Channel.UNLIMITED)
    private val characteristic =
        BluetoothGattCharacteristic(
            PairingProtocol.MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

    /** Opens the GATT server and starts advertising [sessionId]; throws when Bluetooth refuses. */
    suspend fun open(sessionId: ByteArray) {
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
            BluetoothGattService(
                PairingProtocol.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
        service.addCharacteristic(characteristic)
        gattServer.addService(service)
        if (serviceAdded.await() != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("Bluetooth refused the pairing service")
        }
        startAdvertising(sessionId)
        log.add(DiagnosticEvent.Kind.ADVERTISER, "Pairing server open")
    }

    suspend fun accept(): PairingLink = accepted.receive()

    fun close() {
        advertiseCallback?.let { cb ->
            runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        }
        advertiseCallback = null
        links.values.forEach { it.fail() }
        links.clear()
        runCatching { server?.close() }
        server = null
        accepted.close()
        log.add(DiagnosticEvent.Kind.ADVERTISER, "Pairing server closed")
    }

    private suspend fun startAdvertising(sessionId: ByteArray) {
        val advertiser =
            manager.adapter?.bluetoothLeAdvertiser ?: throw IOException("Cannot advertise")
        val started = CompletableDeferred<Int>()
        val cb =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    started.complete(0)
                }

                override fun onStartFailure(errorCode: Int) {
                    started.complete(errorCode)
                }
            }
        advertiseCallback = cb
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(
                    ParcelUuid(PairingProtocol.SERVICE_UUID),
                    PairingProtocol.advertisement(sessionId),
                )
                .build(),
            cb,
        )
        val error = started.await()
        if (error != 0) throw IOException("Pairing advertisement failed: $error")
    }

    private fun callback(serviceAdded: CompletableDeferred<Int>) =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                serviceAdded.complete(status)
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    links.remove(device.address)?.fail()
                    log.add(DiagnosticEvent.Kind.ADVERTISER, "Pairing phone disconnected")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log.add(DiagnosticEvent.Kind.ADVERTISER, "Pairing phone connected")
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
                    characteristic.uuid == PairingProtocol.MESSAGE_CHARACTERISTIC_UUID &&
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

    private inner class ServerLink(private val device: BluetoothDevice) : PairingLink {
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
