// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStartGuardTest {
    private val guard = ScanStartGuard(maxStarts = 5, windowMillis = 30_000)

    @Test
    fun `allows five starts in the window`() {
        repeat(5) {
            assertEquals(0, guard.delayBeforeStart(it * 1_000L))
            guard.recordStart(it * 1_000L)
        }
    }

    @Test
    fun `holds the sixth start until the first leaves the window`() {
        repeat(5) { guard.recordStart(it * 1_000L) }

        assertEquals(25_000, guard.delayBeforeStart(5_000))
        assertEquals(0, guard.delayBeforeStart(30_000))
    }

    @Test
    fun `old starts stop counting`() {
        repeat(5) { guard.recordStart(0) }

        assertEquals(1, guard.delayBeforeStart(29_999))
        assertEquals(0, guard.delayBeforeStart(30_000))
        guard.recordStart(30_000)
        assertEquals(0, guard.delayBeforeStart(30_001))
    }
}
