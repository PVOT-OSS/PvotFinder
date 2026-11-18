// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.home

sealed interface HomeEvent {
    data class SetFindable(val on: Boolean) : HomeEvent

    data class PlaySound(val deviceIdHex: String) : HomeEvent

    data class StopSound(val deviceIdHex: String) : HomeEvent

    data object PermissionsChanged : HomeEvent
}
