// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prauga.finder.bluetooth.protocol.RotatingId

class SelfReportTest {
    private val secret = ByteArray(32) { 9 }
    private val location = ReportedLocation(28.6139391, 77.2090212, 12, 1_700_000_000, 64)

    @Test
    fun `seals to 44 bytes and opens with the same secret and period`() {
        val sealed = SelfReport.seal(secret, 5, location)

        assertEquals(SelfReport.SIZE, sealed.size)
        assertEquals(44, sealed.size)
        assertEquals(location, SelfReport.open(secret, 5, sealed))
    }

    @Test
    fun `other secrets, periods and damaged reports do not open`() {
        val sealed = SelfReport.seal(secret, 5, location)

        assertNull(SelfReport.open(ByteArray(32), 5, sealed))
        assertNull(SelfReport.open(secret, 6, sealed))
        assertNull(SelfReport.open(secret, 5, sealed.copyOf().also { it[20] = (it[20] + 1).toByte() }))
        assertNull(SelfReport.open(secret, 5, sealed.copyOf(43)))
    }

    @Test
    fun `tags change every period and differ between phones`() {
        assertNotEquals(SelfReport.tag(secret, 5).toList(), SelfReport.tag(secret, 6).toList())
        assertNotEquals(SelfReport.tag(secret, 5).toList(), SelfReport.tag(ByteArray(32), 5).toList())
        assertEquals(16, SelfReport.tag(secret, 5).size)
    }

    @Test
    fun `an event round trip finds the period from the tag`() {
        val now = 1_700_000_000L
        val event = SelfReport.event(secret, now, location)
        val tags = SelfReport.tags(secret, now - 3_600, now)

        assertTrue(event.isValid())
        assertEquals(location, SelfReport.open(secret, tags, event))
        assertNull(SelfReport.open(ByteArray(32) { 1 }, SelfReport.tags(ByteArray(32) { 1 }, now - 3_600, now), event))
        assertEquals(listOf((now + SelfReport.KEEP_SECONDS).toString()), event.tagValues("expiration"))
        assertTrue(RotatingId.period(now) in tags.values)
    }

    @Test
    fun `a week of tags is about six hundred`() {
        val now = 1_700_000_000L
        val count = SelfReport.tags(secret, now - SelfReport.KEEP_SECONDS, now).size

        assertTrue(count in 590..592)
    }

    @Test
    fun `unknown battery and extreme coordinates survive`() {
        val edge = ReportedLocation(-90.0, 180.0, 70_000, 0, null)

        val opened = SelfReport.open(secret, 1, SelfReport.seal(secret, 1, edge))!!

        assertEquals(-90.0, opened.latitude, 0.0)
        assertEquals(180.0, opened.longitude, 0.0)
        assertEquals(65_535, opened.accuracyMetres)
        assertNull(opened.batteryPercent)
    }
}
