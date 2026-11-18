// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.coreui.PvotSectionHeader
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import com.prauga.pvot.designsystem.components.list.PvotSwitchRow
import com.prauga.pvot.designsystem.navigation.PvotTopBarAction
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.finder.R
import org.prauga.finder.SubScreen
import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.bluetooth.BluetoothPermissions
import org.prauga.finder.common.rememberPermissionRequest

@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    onOpenPhone: (String) -> Unit,
    onAddPhone: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val activity = LocalActivity.current
    val allowNearby =
        rememberPermissionRequest(BluetoothPermissions.REQUIRED) {
            onEvent(HomeEvent.PermissionsChanged)
        }

    SubScreen(
        title = stringResource(R.string.app_name),
        onBack = null,
        actions = {
            PvotTopBarAction(
                painterResource(R.drawable.ic_settings),
                stringResource(R.string.settings),
                onOpenSettings,
            )
        },
    ) {
        if (state.bluetooth == AdapterState.OFF) {
            item {
                Notice(
                    title = stringResource(R.string.home_bluetooth_off),
                    text = stringResource(R.string.home_bluetooth_off_text),
                    action = stringResource(R.string.home_turn_on),
                    onAction = {
                        activity?.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                )
            }
        } else if (state.bluetooth == AdapterState.UNAVAILABLE) {
            item {
                Notice(
                    title = stringResource(R.string.home_bluetooth_missing),
                    text = stringResource(R.string.home_bluetooth_missing_text),
                )
            }
        }
        if (!state.nearbyAllowed) {
            item {
                Notice(
                    title = stringResource(R.string.home_nearby_needed),
                    text = stringResource(R.string.permission_summary),
                    action = stringResource(R.string.allow_nearby),
                    onAction = allowNearby,
                )
            }
        }
        item { ThisPhone(state, onEvent, onOpenSettings) }
        if (state.loaded && state.phones.isEmpty()) {
            item { NoPhones(onAddPhone) }
        } else if (state.phones.isNotEmpty()) {
            item {
                PvotSectionHeader(
                    stringResource(R.string.section_my_phones),
                    Modifier.padding(horizontal = PvotSpacing.XS, vertical = PvotSpacing.XS),
                )
            }
            state.phones.forEach { phone ->
                item(key = phone.deviceIdHex) {
                    PhoneCard(
                        phone = phone,
                        onOpen = { onOpenPhone(phone.deviceIdHex) },
                        onPlaySound = { onEvent(HomeEvent.PlaySound(phone.deviceIdHex)) },
                        onStopSound = { onEvent(HomeEvent.StopSound(phone.deviceIdHex)) },
                    )
                }
            }
            item {
                PvotButton(
                    stringResource(R.string.add_phone),
                    onAddPhone,
                    Modifier.fillMaxWidth(),
                    style = PvotButtonStyle.Tonal,
                    icon = painterResource(R.drawable.ic_plus),
                )
            }
        }
    }
}

@Composable
private fun ThisPhone(state: HomeUiState, onEvent: (HomeEvent) -> Unit, onOpenSettings: () -> Unit) {
    PvotListGroup(title = stringResource(R.string.section_this_phone)) {
        item {
            PvotSwitchRow(
                title = stringResource(R.string.home_findable),
                checked = state.findable != FindableStatus.OFF,
                onCheckedChange = { onEvent(HomeEvent.SetFindable(it)) },
                summary = stringResource(findableText(state.findable)),
            )
        }
        if (state.reportsOnline && state.findable != FindableStatus.OFF) {
            item {
                PvotPreferenceRow(
                    title = stringResource(R.string.home_reports_online),
                    summary =
                        if (state.boards == 0) stringResource(R.string.report_online_needs_boards)
                        else stringResource(R.string.report_online_on),
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

private fun findableText(status: FindableStatus): Int =
    when (status) {
        FindableStatus.OFF -> R.string.home_findable_off
        FindableStatus.STARTING -> R.string.starting
        FindableStatus.ON -> R.string.home_findable_on
        FindableStatus.WAITING_FOR_BLUETOOTH -> R.string.waiting_for_bluetooth
        FindableStatus.NEEDS_PERMISSION -> R.string.needs_permission
        FindableStatus.FAILED -> R.string.home_findable_failed
    }

@Composable
private fun NoPhones(onAddPhone: () -> Unit) {
    PvotCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PvotSpacing.L),
            verticalArrangement = Arrangement.spacedBy(PvotSpacing.M),
        ) {
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.home_empty_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PvotButton(
                stringResource(R.string.add_phone),
                onAddPhone,
                Modifier.fillMaxWidth(),
                icon = painterResource(R.drawable.ic_plus),
            )
        }
    }
}

@Composable
private fun Notice(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    PvotCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(PvotSpacing.L),
            verticalArrangement = Arrangement.spacedBy(PvotSpacing.S),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                PvotButton(action, onAction, Modifier.fillMaxWidth())
            }
        }
    }
}
