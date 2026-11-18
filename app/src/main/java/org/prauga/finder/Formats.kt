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
