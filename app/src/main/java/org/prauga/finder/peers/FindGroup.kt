// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.bluetooth.NearbyDevice
import org.prauga.finder.finding.FindState
import org.prauga.finder.finding.Finder

/** Where a paired phone is, and the buttons to verify it and make it ring. */
@Composable
fun FindGroup(deviceIdHex: String, nearby: NearbyDevice?, state: FindState?, finder: Finder) {
    PvotCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PvotSpacing.L),
            verticalArrangement = Arrangement.spacedBy(PvotSpacing.M),
        ) {
            val verified =
                state is FindState.Verified ||
                    state == FindState.Ringing ||
                    state == FindState.RingStopped
            Text(
                when {
                    nearby == null -> stringResource(R.string.find_not_nearby)
                    verified -> stringResource(R.string.find_verified, nearby.rssi)
                    else -> stringResource(R.string.find_maybe, nearby.rssi)
                },
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (verified && nearby != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(
                    when {
                        nearby == null -> R.string.find_not_nearby_hint
                        state == FindState.Working -> R.string.find_working
                        state == FindState.Ringing -> R.string.find_ringing
                        state == FindState.RingStopped -> R.string.find_ring_stopped
                        state is FindState.Failed -> failure(state.reason)
                        verified -> R.string.find_verified_hint
                        else -> R.string.find_maybe_hint
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nearby != null) {
                val idle = state != FindState.Working
                if (!verified) {
                    PvotButton(
                        stringResource(R.string.find_verify),
                        { finder.verify(deviceIdHex) },
                        Modifier.fillMaxWidth(),
                        style = PvotButtonStyle.Tonal,
                        enabled = idle,
                    )
                }
                if (state == FindState.Ringing) {
                    PvotButton(
                        stringResource(R.string.find_stop),
                        { finder.stopRing(deviceIdHex) },
                        Modifier.fillMaxWidth(),
                        enabled = idle,
                    )
                } else {
                    PvotButton(
                        stringResource(R.string.find_ring),
                        { finder.ring(deviceIdHex) },
                        Modifier.fillMaxWidth(),
                        enabled = idle,
                    )
                }
            }
        }
    }
}

private fun failure(reason: FindState.Reason): Int =
    when (reason) {
        FindState.Reason.NOT_NEARBY -> R.string.find_failed_not_nearby
        FindState.Reason.UNREACHABLE -> R.string.find_failed_unreachable
        FindState.Reason.NOT_VERIFIED -> R.string.find_failed_not_verified
        FindState.Reason.REFUSED -> R.string.find_failed_refused
    }
