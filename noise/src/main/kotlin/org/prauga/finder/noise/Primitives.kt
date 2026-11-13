// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.noise

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An X25519 key that can agree with a remote public key; the private half may live elsewhere. */
interface Dh25519 {
    /** The raw 32-byte public key. */
    val publicKey: ByteArray

    fun agree(remotePublicKey: ByteArray): ByteArray
}

/** A software X25519 key, as used for Noise ephemeral keys. */
class SoftwareX25519 private constructor(private val privateKey: PrivateKey, publicKey: ByteArray) :
    Dh25519 {
    override val publicKey: ByteArray = publicKey.copyOf()

    override fun agree(remotePublicKey: ByteArray): ByteArray =
        X25519.agree(privateKey, remotePublicKey)

    companion object {
        fun generate(): SoftwareX25519 {
            val pair = KeyPairGenerator.getInstance("XDH").generateKeyPair()
            return SoftwareX25519(pair.private, X25519.rawPublic(pair.public.encoded))
        }

        /** For test vectors: the public key is computed as the private scalar times the base point. */
        fun fromPrivate(raw: ByteArray): SoftwareX25519 {
            require(raw.size == X25519.KEY_SIZE)
            val key =
                KeyFactory.getInstance("XDH")
                    .generatePrivate(PKCS8EncodedKeySpec(X25519.PKCS8_PREFIX + raw))
            return SoftwareX25519(key, X25519.agree(key, X25519.BASE_POINT))
        }
    }
}

object X25519 {
    const val KEY_SIZE = 32

    internal val BASE_POINT = ByteArray(KEY_SIZE).also { it[0] = 9 }
    private val X509_PREFIX = hex("302a300506032b656e032100")
    internal val PKCS8_PREFIX = hex("302e020100300506032b656e04220420")

    fun rawPublic(x509: ByteArray): ByteArray {
        if (x509.size != X509_PREFIX.size + KEY_SIZE) throw NoiseException("Not an X25519 key")
        return x509.copyOfRange(X509_PREFIX.size, x509.size)
    }

    fun x509Public(raw: ByteArray): ByteArray {
        if (raw.size != KEY_SIZE) throw NoiseException("Not an X25519 key")
        return X509_PREFIX + raw
    }

    /** Agrees with a remote key given as raw bytes, through [provider] when the key lives there. */
    fun agree(privateKey: PrivateKey, remotePublicKey: ByteArray, provider: String? = null): ByteArray {
        try {
            val remote =
                KeyFactory.getInstance("XDH")
                    .generatePublic(X509EncodedKeySpec(x509Public(remotePublicKey)))
            val agreement =
                if (provider == null) KeyAgreement.getInstance("XDH")
                else KeyAgreement.getInstance("XDH", provider)
            agreement.init(privateKey)
            agreement.doPhase(remote, true)
            return agreement.generateSecret()
        } catch (e: GeneralSecurityException) {
            throw NoiseException("X25519 agreement failed", e)
        }
    }
}

internal object Sha256 {
    const val HASH_LEN = 32
    const val BLOCK_LEN = 64

    fun hash(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach(::update)
            digest()
        }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach(::update)
            doFinal()
        }

    /** Noise's HKDF: two or three 32-byte outputs. */
    fun hkdf(chainingKey: ByteArray, input: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, input)
        val out1 = hmac(temp, byteArrayOf(1))
        val out2 = hmac(temp, out1, byteArrayOf(2))
        if (outputs == 2) return listOf(out1, out2)
        return listOf(out1, out2, hmac(temp, out2, byteArrayOf(3)))
    }
}

internal object ChaChaPoly {
    const val TAG_LEN = 16

    private fun cipher(): Cipher =
        try {
            Cipher.getInstance("ChaCha20-Poly1305")
        } catch (e: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        }

    private fun nonce(n: Long): ByteArray =
        ByteArray(12).also { bytes -> for (i in 0 until 8) bytes[4 + i] = (n ushr (8 * i)).toByte() }

    fun encrypt(key: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher().run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(n)))
            updateAAD(ad)
            doFinal(plaintext)
        }

    fun decrypt(key: ByteArray, n: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray =
        try {
            cipher().run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(n)))
                updateAAD(ad)
                doFinal(ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            throw NoiseException("Decryption failed", e)
        }
}

internal fun hex(s: String): ByteArray =
    ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
