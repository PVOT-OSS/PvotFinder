// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.prauga.finder.bluetooth.AdapterStateSource
import org.prauga.finder.bluetooth.AndroidBleAdvertiser
import org.prauga.finder.bluetooth.AndroidBleScanner
import org.prauga.finder.bluetooth.BluetoothPermissions
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.bluetooth.ScanStartGuard
import org.prauga.finder.bluetooth.pairing.PairingClient
import org.prauga.finder.bluetooth.pairing.PairingServer
import org.prauga.finder.bluetooth.protocol.RotatingId
import org.prauga.finder.bluetooth.gatt.GattMessageClient
import org.prauga.finder.bluetooth.gatt.GattMessageServer
import org.prauga.finder.finding.Finder
import org.prauga.finder.finding.FindingHost
import org.prauga.finder.finding.FindingProtocol
import org.prauga.finder.finding.PeerIds
import org.prauga.finder.finding.Ringer
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.pairing.PairingController

class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val scanGuard = ScanStartGuard()

    val diagnostics = DiagnosticsLog()
    private val permissions = BluetoothPermissions(context)

    val identity =
        IdentityRepository.create(
            context,
            defaultName = {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                    ?: Build.MODEL
            },
        )

    private val peerIds = PeerIds(identity).also { it.start(scope) }

    val discovery =
        DiscoveryController(
            scope = scope,
            adapterStates = AdapterStateSource(context, adapter).states,
            permissionSource = permissions::current,
            advertiser = AndroidBleAdvertiser(adapter, permissions, diagnostics),
            scanner = AndroidBleScanner(adapter, permissions, diagnostics, scope, guard = scanGuard),
            log = diagnostics,
            elapsedClock = SystemClock::elapsedRealtime,
            advertisedId = { seconds ->
                val secret = identity.beaconSecret()
                RotatingId.forPeriod(secret, RotatingId.period(seconds)).also { secret.fill(0) }
            },
        ).also { it.resolver = peerIds }

    val ringer = Ringer(context, scope)

    val finder =
        Finder(scope, discovery, identity, GattMessageClient(context, diagnostics), diagnostics)

    init {
        FindingHost(
                scope,
                discovery.status,
                identity,
                newServer = {
                    GattMessageServer(
                        context,
                        requireNotNull(manager),
                        diagnostics,
                        FindingProtocol.SERVICE_UUID,
                        FindingProtocol.MESSAGE_CHARACTERISTIC_UUID,
                        "Finding",
                    )
                },
                ringer,
                diagnostics,
            )
            .start()
    }

    val pairing =
        PairingController(
            scope = scope,
            identity = identity,
            newServer = { PairingServer(context, requireNotNull(manager), diagnostics) },
            client = PairingClient(context, requireNotNull(manager), scanGuard, diagnostics),
            log = diagnostics,
            appVersion = BuildConfig.VERSION_NAME,
        )
}
