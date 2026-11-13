// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.report

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt
import org.prauga.finder.bluetooth.protocol.RotatingId
import org.prauga.finder.nostr.Event
import org.prauga.finder.nostr.Filter

/** Where a phone says it was, as its paired phones read it. */
data class ReportedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Int,
    val takenAtSeconds: Long,
    val batteryPercent: Int?,
)

/**
 * A phone's report of its own location, readable and writable only with its beacon secret
 * (docs/relay.md §1b).
 */
object SelfReport {
    const val KIND = 3971
    const val TAG = "y"
    const val INTERVAL_SECONDS = 30 * 60L
    const val KEEP_SECONDS = 7 * 24 * 60 * 60L

    private const val VERSION: Byte = 1
    private const val PLAINTEXT_SIZE = 16
    private const val NONCE_SIZE = 12
    const val SIZE = NONCE_SIZE + PLAINTEXT_SIZE + 16
    private val LABEL = "PvotFinder self v1".toByteArray(Charsets.US_ASCII)

    fun tag(beaconSecret: ByteArray, period: Long): ByteArray =
        hmac(seed(beaconSecret, period), "tag".toByteArray()).copyOf(16)

    fun seal(
        beaconSecret: ByteArray,
        period: Long,
        location: ReportedLocation,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val seed = seed(beaconSecret, period)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = cipher()
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(hmac(seed, "key".toByteArray()), "ChaCha20"),
            IvParameterSpec(nonce),
        )
        cipher.updateAAD(hmac(seed, "tag".toByteArray()).copyOf(16))
        return nonce + cipher.doFinal(encode(location))
    }

    /** Null when [report] was not made with [beaconSecret] for [period], or is damaged. */
    fun open(beaconSecret: ByteArray, period: Long, report: ByteArray): ReportedLocation? {
        if (report.size != SIZE) return null
        val seed = seed(beaconSecret, period)
        return try {
            val cipher = cipher()
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hmac(seed, "key".toByteArray()), "ChaCha20"),
                IvParameterSpec(report.copyOf(NONCE_SIZE)),
            )
            cipher.updateAAD(hmac(seed, "tag".toByteArray()).copyOf(16))
            decode(cipher.doFinal(report, NONCE_SIZE, report.size - NONCE_SIZE))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** The Nostr event carrying this phone's location at [nowSeconds]. */
    fun event(beaconSecret: ByteArray, nowSeconds: Long, location: ReportedLocation): Event {
        val period = RotatingId.period(nowSeconds)
        return Event.create(
            kind = KIND,
            tags =
                listOf(
                    listOf(TAG, tag(beaconSecret, period).toHex()),
                    listOf("expiration", (nowSeconds + KEEP_SECONDS).toString()),
                ),
            content = Base64.getEncoder().encodeToString(seal(beaconSecret, period, location)),
            createdAt = nowSeconds,
        )
    }

    /** Every tag a phone with [beaconSecret] may have used between [fromSeconds] and [toSeconds]. */
    fun tags(beaconSecret: ByteArray, fromSeconds: Long, toSeconds: Long): Map<String, Long> =
        (RotatingId.period(fromSeconds)..RotatingId.period(toSeconds)).associateBy {
            tag(beaconSecret, it).toHex()
        }

    fun filter(tagValues: Collection<String>, sinceSeconds: Long): Filter =
        Filter(kinds = listOf(KIND), tags = mapOf(TAG[0] to tagValues.toList()), since = sinceSeconds)

    /** Opens [event] if one of [tagPeriods] (from [tags]) matches it. */
    fun open(beaconSecret: ByteArray, tagPeriods: Map<String, Long>, event: Event): ReportedLocation? {
        if (event.kind != KIND) return null
        val period = event.tagValues(TAG).firstNotNullOfOrNull { tagPeriods[it] } ?: return null
        val report =
            try {
                Base64.getDecoder().decode(event.content)
            } catch (e: IllegalArgumentException) {
                return null
            }
        return open(beaconSecret, period, report)
    }

    private fun seed(beaconSecret: ByteArray, period: Long): ByteArray {
        require(period in 0..0xFFFF_FFFFL)
        return hmac(beaconSecret, LABEL + ByteBuffer.allocate(4).putInt(period.toInt()).array())
    }

    private fun encode(location: ReportedLocation): ByteArray =
        ByteBuffer.allocate(PLAINTEXT_SIZE)
            .put(VERSION)
            .putInt((location.latitude * 1e7).roundToInt())
            .putInt((location.longitude * 1e7).roundToInt())
            .putShort(location.accuracyMetres.coerceIn(0, 0xFFFF).toShort())
            .putInt(location.takenAtSeconds.coerceIn(0, 0xFFFF_FFFFL).toInt())
            .put((location.batteryPercent?.coerceIn(0, 100) ?: 0xFF).toByte())
            .array()

    private fun decode(bytes: ByteArray): ReportedLocation? {
        if (bytes.size != PLAINTEXT_SIZE || bytes[0] != VERSION) return null
        val buffer = ByteBuffer.wrap(bytes, 1, PLAINTEXT_SIZE - 1)
        val latitude = buffer.getInt() / 1e7
        val longitude = buffer.getInt() / 1e7
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return ReportedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMetres = buffer.getShort().toInt() and 0xFFFF,
            takenAtSeconds = buffer.getInt().toLong() and 0xFFFF_FFFFL,
            batteryPercent = (buffer.get().toInt() and 0xFF).takeIf { it <= 100 },
        )
    }

    private fun cipher(): Cipher =
        try {
            Cipher.getInstance("ChaCha20-Poly1305")
        } catch (e: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
