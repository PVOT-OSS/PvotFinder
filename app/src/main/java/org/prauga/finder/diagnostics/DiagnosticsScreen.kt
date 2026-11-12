// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prauga.pvot.coreui.PvotSectionHeader
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.navigation.PvotTopBarAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.prauga.finder.BuildConfig
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.bluetooth.DiagnosticEvent
import org.prauga.finder.bluetooth.DiagnosticsLog
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.bluetooth.DiscoveryStatus

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun DiagnosticEvent.time(): String =
    timeFormat.format(Instant.ofEpochMilli(wallTimeMillis).atZone(ZoneId.systemDefault()))

private fun DiagnosticEvent.line(): String =
    listOfNotNull(time(), kind.name, message, detail).joinToString("  ")

@Composable
fun DiagnosticsScreen(discovery: DiscoveryController, log: DiagnosticsLog, onBack: () -> Unit) {
    val status by discovery.status.collectAsStateWithLifecycle()
    val events by log.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val newestFirst = events.asReversed()

    SubScreen(
        title = stringResource(R.string.diagnostics),
        onBack = onBack,
        actions = {
            PvotTopBarAction(
                painterResource(R.drawable.ic_copy),
                stringResource(R.string.copy_log),
                onClick = {
                    val text = (statusLines(status) + events.map { it.line() }).joinToString("\n")
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Pvot Finder diagnostics", text))
                },
            )
            PvotTopBarAction(
                painterResource(R.drawable.ic_delete),
                stringResource(R.string.clear_log),
                onClick = log::clear,
            )
        },
    ) {
        item {
            PvotListGroup(title = stringResource(R.string.section_state)) {
                statusLines(status).forEach { line ->
                    val (title, value) = line.split(": ", limit = 2)
                    item { PvotListRow(title = title, supportingText = value) }
                }
            }
        }
        item { PvotSectionHeader(stringResource(R.string.section_events)) }
        if (events.isEmpty()) {
            item { PvotListRow(title = stringResource(R.string.events_empty)) }
        }
        items(newestFirst) { event ->
            PvotListRow(
                title = event.message,
                supportingText =
                    listOfNotNull(event.time(), event.kind.name, event.detail).joinToString("  "),
            )
        }
    }
}

private fun statusLines(status: DiscoveryStatus): List<String> =
    listOf(
        "App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}",
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Bluetooth: ${status.adapter}",
        "Permissions: scan ${status.permissions.canScan}, " +
            "advertise ${status.permissions.canAdvertise}",
        "Foreground: ${status.foreground}",
        "Advertiser: ${status.advertiser.describe()}",
        "Scanner: ${status.scanner}",
    )

private fun Any.describe(): String = toString().replace(Regex("TemporaryId\\(([0-9a-f]+)\\)"), "$1")
