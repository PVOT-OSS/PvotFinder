// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.bluetooth.AdvertiseError
import org.prauga.finder.bluetooth.NearbyDevice
import org.prauga.finder.bluetooth.NearbyKey
import org.prauga.finder.bluetooth.ScanError
import org.prauga.finder.bluetooth.protocol.TemporaryId

/** The elapsed-realtime clock, ticking once a second while shown. */
@Composable
fun rememberElapsedNow(): Long {
    val now by
        produceState(SystemClock.elapsedRealtime()) {
            while (true) {
                delay(1_000)
                value = SystemClock.elapsedRealtime()
            }
        }
    return now
}

fun TemporaryId.shortLabel(): String = toHex().take(8).uppercase().chunked(4).joinToString(" ")

/** A stable string for a nearby device, for navigation. */
fun NearbyKey.asString(): String =
    when (this) {
        is NearbyKey.Peer -> "peer:$deviceIdHex"
        is NearbyKey.Unknown -> "id:${id.toHex()}"
    }

/** A paired phone by its name, anything else as an unknown Pvot Finder phone. */
@Composable
fun nearbyName(device: NearbyDevice, peerNames: Map<String, String>): String =
    when (val key = device.key) {
        is NearbyKey.Peer -> peerNames[key.deviceIdHex] ?: stringResource(R.string.device)
        is NearbyKey.Unknown -> stringResource(R.string.device_unknown, key.id.shortLabel())
    }

@Composable
fun timeAgo(now: Long, then: Long): String {
    val seconds = ((now - then).coerceAtLeast(0) / 1_000).toInt()
    return when {
        seconds < 2 -> stringResource(R.string.just_now)
        seconds < 60 -> stringResource(R.string.seconds_ago, seconds)
        else -> stringResource(R.string.minutes_ago, seconds / 60)
    }
}

@Composable
fun adapterLabel(state: AdapterState): String =
    stringResource(
        when (state) {
            AdapterState.UNAVAILABLE -> R.string.bluetooth_unavailable
            AdapterState.OFF -> R.string.bluetooth_off
            AdapterState.TURNING_ON -> R.string.bluetooth_turning_on
            AdapterState.ON -> R.string.bluetooth_on
            AdapterState.TURNING_OFF -> R.string.bluetooth_turning_off
        }
    )

@Composable
fun advertiseErrorLabel(error: AdvertiseError): String =
    stringResource(
        when (error) {
            AdvertiseError.DATA_TOO_LARGE -> R.string.advertise_error_too_large
            AdvertiseError.TOO_MANY_ADVERTISERS -> R.string.advertise_error_too_many
            AdvertiseError.ALREADY_STARTED -> R.string.error_already_started
            AdvertiseError.INTERNAL_ERROR -> R.string.error_internal
            AdvertiseError.FEATURE_UNSUPPORTED -> R.string.advertise_error_unsupported
            AdvertiseError.PERMISSION_DENIED -> R.string.error_permission
            AdvertiseError.UNKNOWN -> R.string.error_unknown
        }
    )

@Composable
fun scanErrorLabel(error: ScanError): String =
    stringResource(
        when (error) {
            ScanError.ALREADY_STARTED -> R.string.error_already_started
            ScanError.APPLICATION_REGISTRATION_FAILED -> R.string.scan_error_registration
            ScanError.INTERNAL_ERROR -> R.string.error_internal
            ScanError.FEATURE_UNSUPPORTED -> R.string.scan_error_unsupported
            ScanError.OUT_OF_HARDWARE_RESOURCES -> R.string.scan_error_resources
            ScanError.SCANNING_TOO_FREQUENTLY -> R.string.scan_error_too_frequent
            ScanError.PERMISSION_DENIED -> R.string.error_permission
            ScanError.UNKNOWN -> R.string.error_unknown
        }
    )
