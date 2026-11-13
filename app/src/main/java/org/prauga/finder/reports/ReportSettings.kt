// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.reports

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.prauga.finder.BuildConfig

/** Whether this phone reports itself online, and the notice boards used. Empty until chosen. */
class ReportSettings(context: Context) {
    private val preferences = context.getSharedPreferences("reports", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _boards =
        MutableStateFlow(preferences.getStringSet(BOARDS, emptySet()).orEmpty().sorted())
    val boards: StateFlow<List<String>> = _boards.asStateFlow()

    fun setEnabled(on: Boolean) {
        preferences.edit { putBoolean(ENABLED, on) }
        _enabled.value = on
    }

    /** Adds a `wss://` address (debug builds also take `ws://` for a local test board). */
    fun addBoard(url: String): Boolean {
        val clean = url.trim().trimEnd('/')
        val secure = clean.startsWith("wss://") && clean.length > 6
        val localTest = BuildConfig.DEBUG && clean.startsWith("ws://") && clean.length > 5
        if (!secure && !localTest) return false
        save(_boards.value + clean)
        return true
    }

    fun removeBoard(url: String) = save(_boards.value - url)

    private fun save(list: List<String>) {
        val sorted = list.distinct().sorted()
        preferences.edit { putStringSet(BOARDS, sorted.toSet()) }
        _boards.value = sorted
    }

    private companion object {
        const val ENABLED = "enabled"
        const val BOARDS = "boards"
    }
}
