// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.bluetooth.NearbyDevice
import org.prauga.finder.asString
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.nearbyName
import org.prauga.finder.rememberElapsedNow
import org.prauga.finder.timeAgo

private const val CHART_WINDOW_MILLIS = 60_000L

@Composable
fun DeviceScreen(
    discovery: DiscoveryController,
    identity: IdentityRepository,
    key: String,
    onBack: () -> Unit,
) {
    val nearby by discovery.nearby.collectAsStateWithLifecycle()
    val peers by identity.peers.collectAsStateWithLifecycle(emptyList())
    val peerNames = peers.associate { it.deviceIdHex to it.name }
    val now = rememberElapsedNow()
    val current = nearby.firstOrNull { it.key.asString() == key }
    var lastKnown by remember { mutableStateOf<NearbyDevice?>(null) }
    if (current != null) lastKnown = current
    val device = current ?: lastKnown

    SubScreen(
        title = device?.let { nearbyName(it, peerNames) } ?: stringResource(R.string.device),
        onBack = onBack,
    ) {
        if (device == null) {
            item { PvotListRow(title = stringResource(R.string.device_out_of_range)) }
            return@SubScreen
        }
        item { SignalCard(device, inRange = current != null, now = now) }
        item {
            PvotListGroup(title = stringResource(R.string.section_details)) {
                item {
                    PvotListRow(
                        title = stringResource(R.string.temporary_id),
                        supportingText = device.advertisedId.toHex(),
                    )
                }
                item {
                    PvotListRow(
                        title = stringResource(R.string.first_seen),
                        supportingText = timeAgo(now, device.firstSeenMillis),
                    )
                }
                item {
                    PvotListRow(
                        title = stringResource(R.string.sightings),
                        supportingText = device.sightings.toString(),
                    )
                }
                device.txPower?.let { tx ->
                    item {
                        PvotListRow(
                            title = stringResource(R.string.tx_power),
                            supportingText = stringResource(R.string.rssi_dbm, tx),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalCard(device: NearbyDevice, inRange: Boolean, now: Long) {
    PvotCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PvotSpacing.L),
            verticalArrangement = Arrangement.spacedBy(PvotSpacing.S),
        ) {
            Text(
                stringResource(if (inRange) R.string.status_nearby else R.string.status_gone),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.rssi_dbm, device.rssi),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                stringResource(R.string.last_seen, timeAgo(now, device.lastSeenMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RssiChart(
                samples = device.history,
                now = now,
                windowMillis = CHART_WINDOW_MILLIS,
                description = stringResource(R.string.signal_chart),
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = PvotSpacing.S),
            )
            Text(
                stringResource(R.string.signal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
