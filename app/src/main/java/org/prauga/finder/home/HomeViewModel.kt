// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.bluetooth.AdvertiserState
import org.prauga.finder.bluetooth.DiscoveryController
import org.prauga.finder.bluetooth.DiscoveryStatus
import org.prauga.finder.bluetooth.NearbyDevice
import org.prauga.finder.bluetooth.NearbyKey
import org.prauga.finder.finding.FindState
import org.prauga.finder.finding.Findable
import org.prauga.finder.finding.Finder
import org.prauga.finder.identity.IdentityRepository
import org.prauga.finder.identity.LastSeen
import org.prauga.finder.identity.Peer
import org.prauga.finder.identity.Report
import org.prauga.finder.reports.ReportFetcher
import org.prauga.finder.reports.ReportSettings

class HomeViewModel(
    private val discovery: DiscoveryController,
    private val findable: Findable,
    reportSettings: ReportSettings,
    identity: IdentityRepository,
    private val finder: Finder,
    fetcher: ReportFetcher,
) : ViewModel() {
    private val known =
        combine(identity.peers, identity.newestSeen, identity.newestReports) { peers, seen, reports ->
            Known(peers, seen, reports)
        }

    private val settings =
        combine(findable.enabled, reportSettings.enabled, reportSettings.boards) { on, online, boards ->
            Settings(on, online, boards.size)
        }

    val state: StateFlow<HomeUiState> =
        combine(discovery.status, discovery.nearby, known, finder.states, settings) {
                status,
                nearby,
                known,
                finding,
                settings ->
                HomeUiState(
                    bluetooth = status.adapter,
                    nearbyAllowed = status.permissions.allGranted,
                    findable = findableStatus(settings.findable, status),
                    reportsOnline = settings.reportsOnline,
                    boards = settings.boards,
                    phones = known.peers.map { phone(it, nearby, known, finding[it.deviceIdHex]) },
                    loaded = true,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        fetcher.fetch()
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.SetFindable -> findable.set(event.on)
            is HomeEvent.PlaySound -> finder.ring(event.deviceIdHex)
            is HomeEvent.StopSound -> finder.stopRing(event.deviceIdHex)
            HomeEvent.PermissionsChanged -> discovery.refreshPermissions()
        }
    }

    private fun phone(
        peer: Peer,
        nearby: List<NearbyDevice>,
        known: Known,
        finding: FindState?,
    ): PhoneItem {
        val heard = nearby.firstOrNull { it.key == NearbyKey.Peer(peer.deviceIdHex) }
        val verified =
            finding is FindState.Verified ||
                finding == FindState.Ringing ||
                finding == FindState.RingStopped
        val presence =
            when {
                heard == null -> Presence.Away
                verified -> Presence.Verified(Signal.of(heard.rssi))
                else -> Presence.Maybe(Signal.of(heard.rssi))
            }
        return PhoneItem(
            deviceIdHex = peer.deviceIdHex,
            name = peer.name,
            presence = presence,
            lastSeen = known.seen[peer.deviceIdHex],
            report = known.reports[peer.deviceIdHex],
            ringing = finding == FindState.Ringing,
            busy = finding == FindState.Working,
        )
    }

    private fun findableStatus(on: Boolean, status: DiscoveryStatus): FindableStatus =
        when {
            !on -> FindableStatus.OFF
            status.adapter != AdapterState.ON -> FindableStatus.WAITING_FOR_BLUETOOTH
            !status.permissions.canAdvertise -> FindableStatus.NEEDS_PERMISSION
            status.advertiser is AdvertiserState.Advertising -> FindableStatus.ON
            status.advertiser is AdvertiserState.Failed -> FindableStatus.FAILED
            else -> FindableStatus.STARTING
        }

    private data class Known(
        val peers: List<Peer>,
        val seen: Map<String, LastSeen>,
        val reports: Map<String, Report>,
    )

    private data class Settings(val findable: Boolean, val reportsOnline: Boolean, val boards: Int)
}
