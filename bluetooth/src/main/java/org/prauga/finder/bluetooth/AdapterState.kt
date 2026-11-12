// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class AdapterState {
    UNAVAILABLE,
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF,
}

class AdapterStateSource(private val context: Context, private val adapter: BluetoothAdapter?) {
    val states: Flow<AdapterState> =
        callbackFlow {
                if (adapter == null) {
                    trySend(AdapterState.UNAVAILABLE)
                    awaitClose {}
                    return@callbackFlow
                }
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val state =
                                intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE,
                                    BluetoothAdapter.ERROR,
                                )
                            trySend(map(state))
                        }
                    }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                trySend(map(adapter.state))
                awaitClose { context.unregisterReceiver(receiver) }
            }
            .distinctUntilChanged()

    private fun map(state: Int): AdapterState =
        when (state) {
            BluetoothAdapter.STATE_ON -> AdapterState.ON
            BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
            BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
            else -> AdapterState.OFF
        }
}
