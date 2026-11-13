// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.peers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.theme.PvotSpacing
import java.util.Locale
import org.prauga.finder.R
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.LastSeen

/** Where this phone was when it last heard [peerName], and earlier hours. */
@Composable
fun LastSeenCard(identity: IdentityRepository, deviceIdHex: String, peerName: String) {
    val flow = remember(deviceIdHex) { identity.lastSeen(deviceIdHex) }
    val entries by flow.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val newest = entries.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(PvotSpacing.M)) {
        PvotCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(PvotSpacing.L),
                verticalArrangement = Arrangement.spacedBy(PvotSpacing.S),
            ) {
                if (newest == null) {
                    Text(
                        stringResource(R.string.last_seen_none_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.last_seen_none, peerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Text(
                    stringResource(R.string.last_seen_title, relative(newest.seenAtMillis)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    describe(context, newest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val lat = newest.latitude
                val lon = newest.longitude
                if (lat != null && lon != null) {
                    PvotButton(
                        stringResource(R.string.open_in_maps),
                        { openMap(context, lat, lon, peerName) },
                        Modifier.fillMaxWidth(),
                        style = PvotButtonStyle.Tonal,
                    )
                }
            }
        }
        val earlier = entries.drop(1)
        if (earlier.isNotEmpty()) {
            PvotListGroup(title = stringResource(R.string.last_seen_earlier)) {
                earlier.forEach { entry ->
                    item {
                        val lat = entry.latitude
                        val lon = entry.longitude
                        PvotListRow(
                            title = time(context, entry.seenAtMillis),
                            supportingText = describe(context, entry),
                            onClick =
                                if (lat != null && lon != null) {
                                    { openMap(context, lat, lon, peerName) }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun relative(millis: Long): String =
    if (System.currentTimeMillis() - millis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.just_now).lowercase()
    } else {
        DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            .toString()
    }

internal fun time(context: Context, millis: Long): String =
    DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )

private fun describe(context: Context, entry: LastSeen): String {
    val at = time(context, entry.seenAtMillis)
    val accuracy = entry.accuracyMetres
    return when {
        entry.latitude == null -> context.getString(R.string.last_seen_no_location, at)
        accuracy != null ->
            context.getString(R.string.last_seen_located, at, accuracy.toInt().coerceAtLeast(1))
        else -> context.getString(R.string.last_seen_located_unknown, at)
    }
}

internal fun openMap(context: Context, latitude: Double, longitude: Double, label: String) {
    val lat = String.format(Locale.ROOT, "%.6f", latitude)
    val lon = String.format(Locale.ROOT, "%.6f", longitude)
    val uri = "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})".toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        val web = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
        context.startActivity(Intent(Intent.ACTION_VIEW, web.toUri()))
    }
}
