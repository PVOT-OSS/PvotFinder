// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.nostr

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures on secp256k1, as Nostr uses them.
 *
 * Not constant time: only for throwaway keys made for a single event, which have nothing to leak.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G =
        Point(
            BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
        )
    private val SEVEN = BigInteger.valueOf(7)

    private data class Point(val x: BigInteger, val y: BigInteger)

    /** A new private key, 32 bytes. */
    fun newPrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val key = ByteArray(32).also(random::nextBytes)
            val d = BigInteger(1, key)
            if (d.signum() > 0 && d < N) return key
        }
    }

    /** The 32-byte x-only public key for [privateKey]. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        val d = scalar(privateKey)
        return bytes32(multiply(G, d)!!.x)
    }

    fun sign(
        privateKey: ByteArray,
        message: ByteArray,
        auxRand: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
    ): ByteArray {
        require(auxRand.size == 32)
        val d0 = scalar(privateKey)
        val publicPoint = multiply(G, d0)!!
        val d = if (publicPoint.y.testBit(0)) N - d0 else d0
        val masked = xor(bytes32(d), taggedHash("BIP0340/aux", auxRand))
        val px = bytes32(publicPoint.x)
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", masked + px + message)).mod(N)
        require(k0.signum() != 0) { "Nonce is zero" }
        val r = multiply(G, k0)!!
        val k = if (r.y.testBit(0)) N - k0 else k0
        val rx = bytes32(r.x)
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + px + message)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val point = liftX(BigInteger(1, publicKey)) ?: return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e =
            BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + publicKey + message))
                .mod(N)
        val result = add(multiply(G, s), multiply(point, N - e)) ?: return false
        return !result.y.testBit(0) && result.x == r
    }

    private fun scalar(privateKey: ByteArray): BigInteger {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < N) { "Private key out of range" }
        return d
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(BigInteger.TWO, P) != c) return null
        return Point(x, if (y.testBit(0)) P - y else y)
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val lambda =
            if (a == b) {
                BigInteger.valueOf(3)
                    .multiply(a.x.pow(2))
                    .multiply(BigInteger.TWO.multiply(a.y).modInverse(P))
                    .mod(P)
            } else {
                b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
            }
        val x = lambda.pow(2).subtract(a.x).subtract(b.x).mod(P)
        return Point(x, lambda.multiply(a.x.subtract(x)).subtract(a.y).mod(P))
    }

    private fun multiply(point: Point, k: BigInteger): Point? {
        var result: Point? = null
        var addend: Point? = point
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    internal fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
        return MessageDigest.getInstance("SHA-256").run {
            update(tagHash)
            update(tagHash)
            digest(data)
        }
    }

    private fun bytes32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}
