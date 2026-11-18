// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.PvotSegmentedControl
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import com.prauga.pvot.designsystem.components.list.PvotSwitchRow
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.BuildConfig
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.adapterLabel
import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.bluetooth.BluetoothPermissions
import org.prauga.finder.common.hasPermissions
import org.prauga.finder.common.rememberPermissionRequest
import org.prauga.finder.peers.NameDialog

private val timeoutOptions = listOf(10_000L, 30_000L, 60_000L)
private val LOCATION =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
private val NOTIFICATIONS = arrayOf(Manifest.permission.POST_NOTIFICATIONS)
private const val PRIVACY_URL = "https://github.com/PVOT-OSS/PvotSeek/blob/main/PrivacyPolicy.md"
private const val SOURCE_URL = "https://github.com/PVOT-OSS/PvotSeek"
private const val LICENCE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onOpenBoards: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    var locationAllowed by remember { mutableStateOf(context.hasPermissions(LOCATION)) }
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    LifecycleResumeEffect(Unit) {
        locationAllowed = context.hasPermissions(LOCATION)
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onEvent(SettingsEvent.PermissionsChanged)
        onPauseOrDispose {}
    }
    var disclosureThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    var enableReportsAfterLocation by rememberSaveable { mutableStateOf(false) }
    val askNearby =
        rememberPermissionRequest(BluetoothPermissions.REQUIRED) {
            onEvent(SettingsEvent.PermissionsChanged)
        }
    val askNotifications =
        rememberPermissionRequest(NOTIFICATIONS) {
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    val askLocation =
        rememberPermissionRequest(LOCATION) { granted ->
            locationAllowed = context.hasPermissions(LOCATION)
            if (granted && enableReportsAfterLocation) onEvent(SettingsEvent.SetReportsOnline(true))
            enableReportsAfterLocation = false
        }

    SubScreen(stringResource(R.string.settings), onBack) {
        item {
            PvotListGroup(title = stringResource(R.string.settings_access)) {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.bluetooth),
                        value = adapterLabel(state.bluetooth),
                        showChevron = state.bluetooth == AdapterState.OFF,
                        enabled = state.bluetooth != AdapterState.UNAVAILABLE,
                        onClick = {
                            activity?.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                    )
                }
                item {
                    AccessRow(
                        title = stringResource(R.string.permission_nearby),
                        granted = state.nearbyAllowed,
                        summary = stringResource(R.string.permission_summary),
                        onAsk = askNearby,
                    )
                }
                item {
                    AccessRow(
                        title = stringResource(R.string.notifications),
                        granted = notificationsAllowed,
                        summary = stringResource(R.string.notifications_summary),
                        onAsk = askNotifications,
                    )
                }
                item {
                    AccessRow(
                        title = stringResource(R.string.location),
                        granted = locationAllowed,
                        summary = stringResource(R.string.location_summary),
                        optional = true,
                        onAsk = { disclosureThen = askLocation },
                    )
                }
            }
        }
        item {
            PvotListGroup(title = stringResource(R.string.settings_finding)) {
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.home_findable),
                        checked = state.findable,
                        onCheckedChange = { onEvent(SettingsEvent.SetFindable(it)) },
                        summary = stringResource(R.string.settings_findable_summary),
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
                    Text(stringResource(R.string.forget_after), style = MaterialTheme.typography.titleSmall)
                    PvotSegmentedControl(
                        options =
                            timeoutOptions.map {
                                stringResource(R.string.seconds_short, (it / 1_000).toInt())
                            },
                        selectedIndex = timeoutOptions.indexOf(state.timeoutMillis).coerceAtLeast(0),
                        onSelect = { onEvent(SettingsEvent.SetTimeout(timeoutOptions[it])) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            PvotListGroup(title = stringResource(R.string.section_online)) {
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.report_online),
                        checked = state.reportsOnline,
                        onCheckedChange = { on ->
                            if (on && !locationAllowed) {
                                enableReportsAfterLocation = true
                                disclosureThen = askLocation
                            } else {
                                onEvent(SettingsEvent.SetReportsOnline(on))
                            }
                        },
                        summary =
                            when {
                                !state.reportsOnline -> stringResource(R.string.report_online_off)
                                !state.findable -> stringResource(R.string.report_online_needs_findable)
                                state.boards == 0 -> stringResource(R.string.report_online_needs_boards)
                                else -> stringResource(R.string.report_online_on)
                            },
                    )
                }
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.boards),
                        value =
                            if (state.boards == 0) stringResource(R.string.boards_none)
                            else pluralStringResource(R.plurals.boards_count, state.boards, state.boards),
                        onClick = onOpenBoards,
                    )
                }
            }
        }
        item {
            PvotListGroup(title = stringResource(R.string.section_this_phone)) {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.name),
                        value = state.phoneName,
                        summary = stringResource(R.string.name_summary),
                        onClick = { renaming = true },
                    )
                }
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.reset),
                        summary = stringResource(R.string.reset_summary),
                        showChevron = false,
                        onClick = { resetting = true },
                    )
                }
            }
        }
        item {
            PvotListGroup(title = stringResource(R.string.settings_about)) {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.diagnostics),
                        onClick = onOpenDiagnostics,
                    )
                }
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.privacy_policy),
                        onClick = { uriHandler.openUri(PRIVACY_URL) },
                    )
                }
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.source_code),
                        summary = stringResource(R.string.licence_summary),
                        onClick = { uriHandler.openUri(SOURCE_URL) },
                    )
                }
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.licence),
                        value = stringResource(R.string.licence_name),
                        onClick = { uriHandler.openUri(LICENCE_URL) },
                    )
                }
                item {
                    PvotListRow(
                        title = stringResource(R.string.app_version),
                        supportingText = BuildConfig.VERSION_NAME,
                    )
                }
            }
        }
    }

    LocationDisclosure(
        visible = disclosureThen != null,
        onContinue = {
            val next = disclosureThen
            disclosureThen = null
            next?.invoke()
        },
        onDismiss = {
            disclosureThen = null
            enableReportsAfterLocation = false
        },
    )
    NameDialog(
        visible = renaming,
        title = stringResource(R.string.rename_this_phone),
        initial = state.phoneName,
        onDismiss = { renaming = false },
        onSave = {
            renaming = false
            onEvent(SettingsEvent.Rename(it))
        },
    )
    PvotDialog(
        visible = resetting,
        onDismissRequest = { resetting = false },
        title = stringResource(R.string.reset_title),
        message = stringResource(R.string.reset_message),
        destructive = true,
    ) {
        PvotDialogAction(
            stringResource(R.string.reset),
            {
                resetting = false
                onEvent(SettingsEvent.Reset)
            },
            Modifier.fillMaxWidth(),
            style = PvotDialogActionStyle.Destructive,
        )
        PvotDialogAction(stringResource(R.string.cancel), { resetting = false }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AccessRow(
    title: String,
    granted: Boolean,
    summary: String,
    onAsk: () -> Unit,
    optional: Boolean = false,
) {
    PvotPreferenceRow(
        title = title,
        value =
            stringResource(
                when {
                    granted -> R.string.permission_granted
                    optional -> R.string.location_optional
                    else -> R.string.permission_needed
                }
            ),
        summary = if (granted) null else summary,
        showChevron = !granted,
        onClick = { if (!granted) onAsk() },
    )
}

/** Shown before Android's location prompt: what is shared, with whom, and when. */
@Composable
fun LocationDisclosure(visible: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    PvotDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.location_disclosure_title),
        message = stringResource(R.string.location_disclosure_text),
    ) {
        PvotDialogAction(
            stringResource(R.string.location_disclosure_continue),
            onContinue,
            Modifier.fillMaxWidth(),
            style = PvotDialogActionStyle.Primary,
        )
        PvotDialogAction(stringResource(R.string.not_now), onDismiss, Modifier.fillMaxWidth())
    }
}
