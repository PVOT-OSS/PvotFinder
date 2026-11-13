// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.prauga.finder.bluetooth.protocol.AdvertisementCodec
import org.prauga.finder.bluetooth.protocol.AdvertisementPayload
import org.prauga.finder.bluetooth.protocol.FinderProtocol

sealed interface AdvertiserState {
    data object Stopped : AdvertiserState

    data class Starting(val payload: AdvertisementPayload) : AdvertiserState

    data class Advertising(val payload: AdvertisementPayload) : AdvertiserState

    data class Failed(val error: AdvertiseError) : AdvertiserState
}

enum class AdvertiseError {
    DATA_TOO_LARGE,
    TOO_MANY_ADVERTISERS,
    ALREADY_STARTED,
    INTERNAL_ERROR,
    FEATURE_UNSUPPORTED,
    PERMISSION_DENIED,
    UNKNOWN;

    companion object {
        fun fromCode(code: Int): AdvertiseError =
            when (code) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> DATA_TOO_LARGE
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> TOO_MANY_ADVERTISERS
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> ALREADY_STARTED
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> INTERNAL_ERROR
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> FEATURE_UNSUPPORTED
                else -> UNKNOWN
            }
    }
}

interface BleAdvertiser {
    val state: StateFlow<AdvertiserState>

    fun start(payload: AdvertisementPayload)

    fun stop()

    /** Forgets the running advertisement after Bluetooth went off, which ends it silently. */
    fun reset()
}

class AndroidBleAdvertiser(
    private val adapter: BluetoothAdapter?,
    private val permissions: BluetoothPermissions,
    private val log: DiagnosticsLog,
) : BleAdvertiser {
    private val _state = MutableStateFlow<AdvertiserState>(AdvertiserState.Stopped)
    override val state: StateFlow<AdvertiserState> = _state.asStateFlow()

    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(payload: AdvertisementPayload) {
        stop()
        if (!permissions.current().canAdvertise) return fail(AdvertiseError.PERMISSION_DENIED)
        val advertiser =
            adapter?.bluetoothLeAdvertiser ?: return fail(AdvertiseError.FEATURE_UNSUPPORTED)
        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(
                    ParcelUuid(FinderProtocol.SERVICE_UUID),
                    AdvertisementCodec.encode(payload),
                )
                .build()
        val cb =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    if (callback !== this) return
                    _state.value = AdvertiserState.Advertising(payload)
                    log.add(
                        DiagnosticEvent.Kind.ADVERTISER,
                        "Advertising started",
                        payload.temporaryId.toHex(),
                    )
                }

                override fun onStartFailure(errorCode: Int) {
                    if (callback !== this) return
                    callback = null
                    fail(AdvertiseError.fromCode(errorCode))
                }
            }
        callback = cb
        _state.value = AdvertiserState.Starting(payload)
        try {
            advertiser.startAdvertising(settings, data, cb)
        } catch (e: SecurityException) {
            callback = null
            fail(AdvertiseError.PERMISSION_DENIED)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        val cb = callback
        callback = null
        if (cb != null) {
            try {
                if (permissions.current().canAdvertise) {
                    adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
                }
            } catch (e: SecurityException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Stopping advertising was not permitted")
            } catch (e: IllegalStateException) {
                log.add(DiagnosticEvent.Kind.ERROR, "Bluetooth was off when advertising stopped")
            }
            log.add(DiagnosticEvent.Kind.ADVERTISER, "Advertising stopped")
        }
        _state.value = AdvertiserState.Stopped
    }

    override fun reset() {
        if (callback == null && _state.value == AdvertiserState.Stopped) return
        callback = null
        _state.value = AdvertiserState.Stopped
        log.add(DiagnosticEvent.Kind.ADVERTISER, "Advertising ended with Bluetooth")
    }

    private fun fail(error: AdvertiseError) {
        _state.value = AdvertiserState.Failed(error)
        log.add(DiagnosticEvent.Kind.ERROR, "Advertising failed: $error")
    }
}
