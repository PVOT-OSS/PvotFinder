// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.prauga.finder.bluetooth.AdapterStateSource
import org.prauga.finder.bluetooth.AndroidBleAdvertiser
import org.prauga.finder.bluetooth.AndroidBleScanner
import org.prauga.finder.bluetooth.BluetoothPermissions
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.DiscoveryController

class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val diagnostics = DiagnosticsLog()
    private val permissions = BluetoothPermissions(context)

    val discovery =
        DiscoveryController(
            scope = scope,
            adapterStates = AdapterStateSource(context, adapter).states,
            permissionSource = permissions::current,
            advertiser = AndroidBleAdvertiser(adapter, permissions, diagnostics),
            scanner = AndroidBleScanner(adapter, permissions, diagnostics, scope),
            log = diagnostics,
            elapsedClock = SystemClock::elapsedRealtime,
        )
}
