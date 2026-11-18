// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.settings

import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.bluetooth.DiscoveryController

data class SettingsUiState(
    val bluetooth: AdapterState = AdapterState.ON,
    val nearbyAllowed: Boolean = true,
    val findable: Boolean = false,
    val timeoutMillis: Long = DiscoveryController.DEFAULT_TIMEOUT_MILLIS,
    val reportsOnline: Boolean = false,
    val boards: Int = 0,
    val phoneName: String = "",
)

sealed interface SettingsEvent {
    data class SetFindable(val on: Boolean) : SettingsEvent

    data class SetTimeout(val millis: Long) : SettingsEvent

    data class SetReportsOnline(val on: Boolean) : SettingsEvent

    data class Rename(val name: String) : SettingsEvent

    data object Reset : SettingsEvent

    data object PermissionsChanged : SettingsEvent
}
