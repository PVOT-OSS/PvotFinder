// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.finding.Findable
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.reports.ReportSettings

class SettingsViewModel(
    private val discovery: DiscoveryController,
    private val findable: Findable,
    private val reportSettings: ReportSettings,
    private val identity: IdentityRepository,
) : ViewModel() {
    private val name = MutableStateFlow("")

    private val reports =
        combine(reportSettings.enabled, reportSettings.boards) { on, boards -> on to boards.size }

    val state: StateFlow<SettingsUiState> =
        combine(discovery.status, discovery.timeoutMillis, findable.enabled, reports, name) {
                status,
                timeout,
                on,
                (online, boards),
                phoneName ->
                SettingsUiState(
                    bluetooth = status.adapter,
                    nearbyAllowed = status.permissions.allGranted,
                    findable = on,
                    timeoutMillis = timeout,
                    reportsOnline = online,
                    boards = boards,
                    phoneName = phoneName,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { name.value = identity.self().name }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetFindable -> findable.set(event.on)
            is SettingsEvent.SetTimeout -> discovery.setTimeout(event.millis)
            is SettingsEvent.SetReportsOnline -> {
                reportSettings.setEnabled(event.on)
                findable.restart()
            }
            is SettingsEvent.Rename ->
                viewModelScope.launch {
                    identity.renameSelf(event.name)
                    name.value = identity.self().name
                }
            SettingsEvent.Reset ->
                viewModelScope.launch {
                    identity.reset()
                    name.value = identity.self().name
                }
            SettingsEvent.PermissionsChanged -> discovery.refreshPermissions()
        }
    }
}
