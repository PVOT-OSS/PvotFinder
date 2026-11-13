// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.bluetooth.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The identifier a phone advertises in each period, derived from its beacon secret. */
object RotatingId {
    const val PERIOD_SECONDS = 1024L

    /** Periods either side of now that a looking phone also accepts, for clock differences. */
    const val SKEW_PERIODS = 2

    private val LABEL = "PvotFinder eid v1".toByteArray(Charsets.US_ASCII)

    fun period(unixSeconds: Long): Long = Math.floorDiv(unixSeconds, PERIOD_SECONDS)

    /** Seconds until [unixSeconds]'s period ends. */
    fun secondsLeft(unixSeconds: Long): Long =
        (period(unixSeconds) + 1) * PERIOD_SECONDS - unixSeconds

    fun forPeriod(beaconSecret: ByteArray, period: Long): TemporaryId {
        require(period in 0..0xFFFF_FFFFL) { "Period out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(beaconSecret, "HmacSHA256"))
        mac.update(LABEL)
        mac.update(
            byteArrayOf(
                (period ushr 24).toByte(),
                (period ushr 16).toByte(),
                (period ushr 8).toByte(),
                period.toByte(),
            )
        )
        return TemporaryId(mac.doFinal().copyOf(TemporaryId.SIZE))
    }

    /** The identifiers a peer may be advertising around [unixSeconds]. */
    fun window(beaconSecret: ByteArray, unixSeconds: Long): List<TemporaryId> {
        val now = period(unixSeconds)
        return (now - SKEW_PERIODS..now + SKEW_PERIODS).filter { it >= 0 }.map {
            forPeriod(beaconSecret, it)
        }
    }
}
