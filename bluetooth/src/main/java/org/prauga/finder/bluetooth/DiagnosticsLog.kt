// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticEvent(
    val wallTimeMillis: Long,
    val kind: Kind,
    val message: String,
    val detail: String? = null,
) {
    enum class Kind {
        ADAPTER,
        PERMISSION,
        ADVERTISER,
        SCANNER,
        SIGHTING,
        REJECTED,
        ERROR,
    }
}

/**
 * In-app event log for the diagnostics screen. [DiagnosticEvent.detail] can hold temporary IDs and
 * stays in memory; only [DiagnosticEvent.message] reaches logcat.
 */
class DiagnosticsLog(
    private val capacity: Int = 500,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val logcat: Boolean = true,
) {
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun add(kind: DiagnosticEvent.Kind, message: String, detail: String? = null) {
        val event = DiagnosticEvent(wallClock(), kind, message, detail)
        _events.update { (it + event).takeLast(capacity) }
        if (logcat) {
            if (kind == DiagnosticEvent.Kind.ERROR) Log.w(TAG, message) else Log.d(TAG, message)
        }
    }

    fun clear() {
        _events.value = emptyList()
    }

    private companion object {
        const val TAG = "PvotFinder"
    }
}
