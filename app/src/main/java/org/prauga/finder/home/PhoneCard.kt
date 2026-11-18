// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.peers.openMap

@Composable
fun PhoneCard(
    phone: PhoneItem,
    onOpen: () -> Unit,
    onPlaySound: () -> Unit,
    onStopSound: () -> Unit,
) {
    val context = LocalContext.current
    PvotCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(
            Modifier.padding(PvotSpacing.L),
            verticalArrangement = Arrangement.spacedBy(PvotSpacing.S),
        ) {
            Text(phone.name, style = MaterialTheme.typography.titleLarge)
            when (val presence = phone.presence) {
                is Presence.Verified ->
                    Text(
                        stringResource(R.string.card_verified, signalText(presence.signal)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                is Presence.Maybe ->
                    Text(
                        stringResource(R.string.card_maybe, signalText(presence.signal)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                Presence.Away ->
                    Text(
                        phone.lastSeen?.let {
                            stringResource(R.string.card_last_seen, relative(it.seenAtMillis))
                        } ?: stringResource(R.string.card_not_seen),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            phone.report?.let { report ->
                Text(
                    stringResource(
                        R.string.card_reported,
                        DateUtils.formatDateTime(
                            context,
                            report.takenAtSeconds * 1_000,
                            DateUtils.FORMAT_SHOW_TIME or
                                DateUtils.FORMAT_SHOW_DATE or
                                DateUtils.FORMAT_ABBREV_MONTH,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = PvotSpacing.XS),
                horizontalArrangement = Arrangement.spacedBy(PvotSpacing.S),
            ) {
                if (phone.presence != Presence.Away) {
                    PvotButton(
                        stringResource(if (phone.ringing) R.string.find_stop else R.string.find_ring),
                        if (phone.ringing) onStopSound else onPlaySound,
                        Modifier.weight(1f),
                        enabled = !phone.busy,
                    )
                }
                val report = phone.report
                if (phone.presence == Presence.Away && report != null) {
                    PvotButton(
                        stringResource(R.string.open_in_maps),
                        { openMap(context, report.latitude, report.longitude, phone.name) },
                        Modifier.weight(1f),
                        style = PvotButtonStyle.Tonal,
                    )
                }
                PvotButton(
                    stringResource(R.string.card_details),
                    onOpen,
                    Modifier.weight(1f),
                    style = PvotButtonStyle.Tonal,
                )
            }
        }
    }
}

@Composable
private fun signalText(signal: Signal): String =
    stringResource(
        when (signal) {
            Signal.STRONG -> R.string.signal_strong
            Signal.MEDIUM -> R.string.signal_medium
            Signal.WEAK -> R.string.signal_weak
        }
    )

@Composable
private fun relative(millis: Long): String {
    val now = System.currentTimeMillis()
    return if (now - millis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.just_now).lowercase()
    } else {
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}
