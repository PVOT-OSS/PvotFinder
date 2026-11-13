// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.noise

/** Noise CipherState: a key and a nonce counter. */
class CipherState internal constructor(private var key: ByteArray? = null) {
    private var nonce = 0L

    internal val hasKey: Boolean
        get() = key != null

    internal fun initializeKey(newKey: ByteArray) {
        key = newKey.copyOf(Sha256.HASH_LEN)
        nonce = 0
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        val out = ChaChaPoly.encrypt(k, nextNonce(), ad, plaintext)
        nonce++
        return out
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        val out = ChaChaPoly.decrypt(k, nextNonce(), ad, ciphertext)
        nonce++
        return out
    }

    private fun nextNonce(): Long {
        if (nonce == -1L) throw NoiseException("Nonce exhausted")
        return nonce
    }
}

internal class SymmetricState(protocolName: String) {
    private val cipher = CipherState()
    private var chainingKey: ByteArray
    var handshakeHash: ByteArray
        private set

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        handshakeHash =
            if (name.size <= Sha256.HASH_LEN) name.copyOf(Sha256.HASH_LEN) else Sha256.hash(name)
        chainingKey = handshakeHash.copyOf()
    }

    val hasKey: Boolean
        get() = cipher.hasKey

    fun mixKey(input: ByteArray) {
        val (ck, tempK) = Sha256.hkdf(chainingKey, input, 2)
        chainingKey = ck
        cipher.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        handshakeHash = Sha256.hash(handshakeHash, data)
    }

    fun mixKeyAndHash(input: ByteArray) {
        val (ck, tempH, tempK) = Sha256.hkdf(chainingKey, input, 3)
        chainingKey = ck
        mixHash(tempH)
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        cipher.encryptWithAd(handshakeHash, plaintext).also(::mixHash)

    fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        cipher.decryptWithAd(handshakeHash, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Sha256.hkdf(chainingKey, ByteArray(0), 2)
        return CipherState(k1.copyOf(Sha256.HASH_LEN)) to CipherState(k2.copyOf(Sha256.HASH_LEN))
    }
}
