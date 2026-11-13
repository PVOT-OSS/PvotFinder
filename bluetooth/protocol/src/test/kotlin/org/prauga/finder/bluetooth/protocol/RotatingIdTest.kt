// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatingIdTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `periods are 1024 seconds`() {
        assertEquals(0, RotatingId.period(1023))
        assertEquals(1, RotatingId.period(1024))
        assertEquals(1, RotatingId.secondsLeft(2047))
        assertEquals(1024, RotatingId.secondsLeft(2048))
    }

    @Test
    fun `matches the documented construction`() {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val expected =
            mac.doFinal("PvotFinder eid v1".toByteArray() + byteArrayOf(0x01, 0x02, 0x03, 0x04))
                .copyOf(8)

        assertEquals(TemporaryId(expected), RotatingId.forPeriod(secret, 0x01020304))
    }

    @Test
    fun `changes every period and with the secret`() {
        val a = RotatingId.forPeriod(secret, 100)

        assertNotEquals(a, RotatingId.forPeriod(secret, 101))
        assertNotEquals(a, RotatingId.forPeriod(ByteArray(32), 100))
        assertEquals(a, RotatingId.forPeriod(secret, 100))
    }

    @Test
    fun `window covers two periods either side`() {
        val now = 1_000L * RotatingId.PERIOD_SECONDS + 5
        val window = RotatingId.window(secret, now)

        assertEquals(5, window.size)
        assertTrue(RotatingId.forPeriod(secret, 998) in window)
        assertTrue(RotatingId.forPeriod(secret, 1_002) in window)
        assertEquals(3, RotatingId.window(secret, 5).size)
    }
}
