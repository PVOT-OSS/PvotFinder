// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.prauga.finder.noise.Dh25519
import org.prauga.finder.noise.X25519

private const val KEYSTORE = "AndroidKeyStore"

private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

/** The phone's static X25519 key; the private half never leaves the Keystore. */
class KeystoreStaticKey private constructor(private val privateKey: PrivateKey, publicKey: ByteArray) :
    Dh25519 {
    override val publicKey: ByteArray = publicKey.copyOf()

    override fun agree(remotePublicKey: ByteArray): ByteArray =
        X25519.agree(privateKey, remotePublicKey, KEYSTORE)

    companion object {
        fun getOrCreate(alias: String): KeystoreStaticKey {
            val store = keyStore()
            val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) {
                return KeystoreStaticKey(
                    existing.privateKey,
                    X25519.rawPublic(existing.certificate.publicKey.encoded),
                )
            }
            val generator = KeyPairGenerator.getInstance("XDH", KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("x25519"))
                    .build()
            )
            val pair = generator.generateKeyPair()
            return KeystoreStaticKey(pair.private, X25519.rawPublic(pair.public.encoded))
        }

        fun delete(alias: String) {
            keyStore().deleteEntry(alias)
        }
    }
}

/** AES-256-GCM under a Keystore key, for secrets that have to be stored as bytes. */
class SecretBox private constructor(private val key: SecretKey) {
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val sealed = cipher.doFinal(plaintext)
        return cipher.iv + sealed
    }

    fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        fun getOrCreate(alias: String): SecretBox {
            val existing = keyStore().getKey(alias, null) as? SecretKey
            if (existing != null) return SecretBox(existing)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return SecretBox(generator.generateKey())
        }

        fun delete(alias: String) {
            keyStore().deleteEntry(alias)
        }
    }
}
