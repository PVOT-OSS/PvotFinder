// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.gatt.GattMessageServer
import org.prauga.finder.bluetooth.gatt.MessageLink
import org.prauga.finder.pairing.PairingProtocol

/** The showing phone's side: advertises the session and serves the pairing characteristic. */
@SuppressLint("MissingPermission")
class PairingServer(context: Context, private val manager: BluetoothManager, log: DiagnosticsLog) {
    private val server =
        GattMessageServer(
            context,
            manager,
            log,
            PairingProtocol.SERVICE_UUID,
            PairingProtocol.MESSAGE_CHARACTERISTIC_UUID,
            "Pairing",
        )
    private var advertiseCallback: AdvertiseCallback? = null

    /** Opens the GATT server and starts advertising [sessionId]; throws when Bluetooth refuses. */
    suspend fun open(sessionId: ByteArray) {
        server.open()
        startAdvertising(sessionId)
    }

    suspend fun accept(): MessageLink = server.accept()

    fun close() {
        advertiseCallback?.let { cb ->
            runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        }
        advertiseCallback = null
        server.close()
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
}
