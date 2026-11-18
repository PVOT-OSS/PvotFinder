// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

import org.prauga.finder.bluetooth.AdapterState
import org.prauga.finder.identity.LastSeen
import org.prauga.finder.identity.Report

data class HomeUiState(
    val bluetooth: AdapterState = AdapterState.ON,
    val nearbyAllowed: Boolean = true,
    val findable: FindableStatus = FindableStatus.OFF,
    val reportsOnline: Boolean = false,
    val boards: Int = 0,
    val phones: List<PhoneItem> = emptyList(),
    val loaded: Boolean = false,
)

enum class FindableStatus {
    OFF,
    STARTING,
    ON,
    WAITING_FOR_BLUETOOTH,
    NEEDS_PERMISSION,
    FAILED,
}

enum class Signal {
    STRONG,
    MEDIUM,
    WEAK;

    companion object {
        fun of(rssi: Int): Signal =
            when {
                rssi >= -60 -> STRONG
                rssi >= -75 -> MEDIUM
                else -> WEAK
            }
    }
}

sealed interface Presence {
    data class Verified(val signal: Signal) : Presence

    data class Maybe(val signal: Signal) : Presence

    data object Away : Presence
}

data class PhoneItem(
    val deviceIdHex: String,
    val name: String,
    val presence: Presence,
    val lastSeen: LastSeen?,
    val report: Report?,
    val ringing: Boolean,
    val busy: Boolean,
)
