// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

import android.app.Activity
import android.Manifest
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.PvotSegmentedControl
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import com.prauga.pvot.designsystem.components.list.PvotSwitchRow
import com.prauga.pvot.designsystem.navigation.PvotTopBarAction
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.adapterLabel
import org.prauga.finder.advertiseErrorLabel
import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.bluetooth.AdvertiserState
import org.prauga.finder.bluetooth.BluetoothPermissions
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.bluetooth.DiscoveryStatus
import org.prauga.finder.bluetooth.NearbyDevice
import org.prauga.finder.bluetooth.NearbyKey
import org.prauga.finder.bluetooth.ScannerState
import org.prauga.finder.bluetooth.protocol.TemporaryId
import org.prauga.finder.nearbyName
import org.prauga.finder.finding.Findable
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.rememberElapsedNow
import org.prauga.finder.scanErrorLabel
import org.prauga.finder.shortLabel
import org.prauga.finder.timeAgo

private val timeoutOptions = listOf(10_000L, 30_000L, 60_000L)

@Composable
fun HomeScreen(
    discovery: DiscoveryController,
    findable: Findable,
    identity: IdentityRepository,
    onAddPhone: () -> Unit,
    onOpenPeer: (String) -> Unit,
    onOpenDevice: ((NearbyKey) -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    val peers by identity.peers.collectAsStateWithLifecycle(emptyList())
    val findableOn by findable.enabled.collectAsStateWithLifecycle()
    val status by discovery.status.collectAsStateWithLifecycle()
    val nearby by discovery.nearby.collectAsStateWithLifecycle()
    val timeout by discovery.timeoutMillis.collectAsStateWithLifecycle()
    val now = rememberElapsedNow()
    val requestPermissions = rememberPermissionRequest(discovery::refreshPermissions)
    val activity = LocalActivity.current

    SubScreen(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            if (onOpenDiagnostics != null) {
                PvotTopBarAction(
                    painterResource(R.drawable.ic_wave_sine),
                    stringResource(R.string.diagnostics),
                    onOpenDiagnostics,
                )
            }
        },
    ) {
        item {
            PvotListGroup(title = stringResource(R.string.section_bluetooth)) {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.bluetooth),
                        value = adapterLabel(status.adapter),
                        summary =
                            if (status.adapter == AdapterState.OFF) {
                                stringResource(R.string.bluetooth_off_summary)
                            } else {
                                null
                            },
                        showChevron = status.adapter == AdapterState.OFF,
                        enabled = status.adapter != AdapterState.UNAVAILABLE,
                        onClick = {
                            activity?.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                    )
                }
                item {
                    val granted = status.permissions.allGranted
                    PvotPreferenceRow(
                        title = stringResource(R.string.permission_nearby),
                        value =
                            stringResource(
                                if (granted) R.string.permission_granted
                                else R.string.permission_needed
                            ),
                        summary =
                            if (granted) null else stringResource(R.string.permission_summary),
                        showChevron = !granted,
                        onClick = { if (!granted) requestPermissions() },
                    )
                }
                item { NotificationsRow() }
            }
        }
        item { MyPhonesGroup(peers, nearby, onAddPhone, onOpenPeer) }
        item {
            PvotListGroup(title = stringResource(R.string.section_discovery)) {
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.advertise),
                        checked = findableOn,
                        onCheckedChange = findable::set,
                        summary = advertiserSummary(status),
                    )
                }
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.scan),
                        checked = status.wantScanning,
                        onCheckedChange = discovery::setScanning,
                        summary = scannerSummary(status, now),
                    )
                }
            }
        }
        item {
            PvotCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(PvotSpacing.L),
                    verticalArrangement = Arrangement.spacedBy(PvotSpacing.M),
                ) {
                    Text(
                        stringResource(R.string.forget_after),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    PvotSegmentedControl(
                        options =
                            timeoutOptions.map {
                                stringResource(R.string.seconds_short, (it / 1_000).toInt())
                            },
                        selectedIndex = timeoutOptions.indexOf(timeout).coerceAtLeast(0),
                        onSelect = { discovery.setTimeout(timeoutOptions[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            NearbyList(
                nearby,
                peers.associate { it.deviceIdHex to it.name },
                status.wantScanning,
                now,
                onOpenDevice,
            )
        }
        item { ThisPhoneGroup(identity) }
    }
}

@Composable
private fun NearbyList(
    nearby: List<NearbyDevice>,
    peerNames: Map<String, String>,
    scanning: Boolean,
    now: Long,
    onOpenDevice: ((NearbyKey) -> Unit)?,
) {
    PvotListGroup(title = stringResource(R.string.section_nearby)) {
        if (nearby.isEmpty()) {
            item {
                PvotListRow(
                    title = stringResource(R.string.nearby_empty),
                    supportingText =
                        stringResource(
                            if (scanning) R.string.nearby_empty_scanning
                            else R.string.nearby_empty_off
                        ),
                )
            }
        }
        nearby.forEach { device ->
            item {
                PvotListRow(
                    title = nearbyName(device, peerNames),
                    supportingText = timeAgo(now, device.lastSeenMillis),
                    trailing = {
                        Text(
                            stringResource(R.string.rssi_dbm, device.rssi),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = onOpenDevice?.let { open -> { open(device.key) } },
                )
            }
        }
    }
}

@Composable
private fun advertiserSummary(status: DiscoveryStatus): String =
    when {
        !status.wantAdvertising -> stringResource(R.string.advertise_off)
        status.adapter != AdapterState.ON -> stringResource(R.string.waiting_for_bluetooth)
        !status.permissions.canAdvertise -> stringResource(R.string.needs_permission)
        else ->
            when (val state = status.advertiser) {
                AdvertiserState.Stopped -> stringResource(R.string.paused)
                is AdvertiserState.Starting -> stringResource(R.string.starting)
                is AdvertiserState.Advertising ->
                    stringResource(R.string.advertising_as, state.payload.temporaryId.shortLabel())
                is AdvertiserState.Failed ->
                    stringResource(R.string.failed, advertiseErrorLabel(state.error))
            }
    }

@Composable
private fun scannerSummary(status: DiscoveryStatus, now: Long): String =
    when {
        !status.wantScanning -> stringResource(R.string.scan_off)
        status.adapter != AdapterState.ON -> stringResource(R.string.waiting_for_bluetooth)
        !status.permissions.canScan -> stringResource(R.string.needs_permission)
        else ->
            when (val state = status.scanner) {
                ScannerState.Stopped -> stringResource(R.string.paused)
                ScannerState.Scanning -> stringResource(R.string.scanning)
                is ScannerState.Waiting ->
                    stringResource(
                        R.string.scan_waiting,
                        ((state.untilElapsedMillis - now) / 1_000 + 1).coerceAtLeast(1).toInt(),
                    )
                is ScannerState.Failed -> stringResource(R.string.failed, scanErrorLabel(state.error))
            }
    }

/**
 * Asks for the Bluetooth permissions, or opens the app's settings once Android stops showing the
 * prompt after repeated denials.
 */
@Composable
private fun rememberPermissionRequest(onResult: () -> Unit): () -> Unit {
    val activity = LocalActivity.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            asked = true
            onResult()
        }
    return {
        if (activity != null && asked && !activity.canAskAgain()) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                )
            )
        } else {
            launcher.launch(BluetoothPermissions.REQUIRED)
        }
    }
}

private fun Activity.canAskAgain(): Boolean =
    BluetoothPermissions.REQUIRED.any { shouldShowRequestPermissionRationale(it) }

/** Ringing needs a notification to stop it, so this phone only rings when it can show one. */
@Composable
private fun NotificationsRow() {
    val context = LocalContext.current
    fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var granted by remember { mutableStateOf(allowed()) }
    LifecycleResumeEffect(Unit) {
        granted = allowed()
        onPauseOrDispose {}
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            asked = true
            granted = allowed()
        }
    PvotPreferenceRow(
        title = stringResource(R.string.notifications),
        value =
            stringResource(if (granted) R.string.permission_granted else R.string.permission_needed),
        summary = if (granted) null else stringResource(R.string.notifications_summary),
        showChevron = !granted,
        onClick = {
            if (granted) return@PvotPreferenceRow
            if (asked) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            } else {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}
