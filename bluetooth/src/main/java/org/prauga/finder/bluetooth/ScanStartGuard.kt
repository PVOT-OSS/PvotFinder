// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

/**
 * Android ignores an app's scans after more than five starts in thirty seconds, without an error.
 * The window here is a little wider to stay clear of that.
 */
class ScanStartGuard(
    private val maxStarts: Int = 5,
    private val windowMillis: Long = 31_000,
) {
    private val starts = ArrayDeque<Long>()

    /** Milliseconds to wait before a start at [now] is safe; 0 when it is safe now. */
    fun delayBeforeStart(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first() >= windowMillis) starts.removeFirst()
        return if (starts.size < maxStarts) 0 else starts.first() + windowMillis - now
    }

    fun recordStart(now: Long) {
        starts.addLast(now)
    }
}
