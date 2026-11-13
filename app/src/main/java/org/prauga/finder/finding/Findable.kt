// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.prauga.finder.bluetooth.DiscoveryController

/** The user's "Let my phones find this one" choice, kept across restarts. */
class Findable(private val context: Context, private val discovery: DiscoveryController) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Applies the saved choice; call while the app is in the foreground. */
    fun restore() {
        apply(_enabled.value)
    }

    fun set(on: Boolean) {
        preferences.edit { putBoolean(KEY, on) }
        _enabled.value = on
        apply(on)
    }

    private fun apply(on: Boolean) {
        discovery.setAdvertising(on)
        if (on) FindableService.start(context) else FindableService.stop(context)
    }

    private companion object {
        const val KEY = "findable"
    }
}
