// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.prauga.finder.noise.NoiseHandshake
import org.prauga.finder.noise.SoftwareX25519
import org.prauga.finder.pairing.PairedPeer
import org.prauga.finder.pairing.PeerInfo

@RunWith(AndroidJUnit4::class)
class IdentityRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = IdentityDatabase.inMemory(context)
    private val repository =
        IdentityRepository(
            database.dao(),
            defaultName = { "Test phone" },
            staticAlias = "test-static",
            boxAlias = "test-box",
        )

    @After
    fun tearDown() {
        database.close()
        KeystoreStaticKey.delete("test-static")
        SecretBox.delete("test-box")
    }

    @Test
    fun keystoreStaticKeyAgreesWithASoftwareKey() {
        val keystore = KeystoreStaticKey.getOrCreate("test-static")
        val software = SoftwareX25519.generate()

        assertArrayEquals(
            software.agree(keystore.publicKey),
            keystore.agree(software.publicKey),
        )
        assertArrayEquals(keystore.publicKey, KeystoreStaticKey.getOrCreate("test-static").publicKey)
    }

    @Test
    fun keystoreStaticKeyCompletesANoiseHandshake() {
        val keystore = KeystoreStaticKey.getOrCreate("test-static")
        val other = SoftwareX25519.generate()
        val psk = ByteArray(32) { 1 }
        val i = NoiseHandshake("XXpsk0", true, byteArrayOf(), other, psk = psk)
        val r = NoiseHandshake("XXpsk0", false, byteArrayOf(), keystore, psk = psk)

        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())

        assertArrayEquals(keystore.publicKey, i.remoteStaticKey)
        assertArrayEquals(i.handshakeHash, r.handshakeHash)
    }

    @Test
    fun secretBoxOpensWhatItSealsOnlyWithTheSameAssociatedData() {
        val box = SecretBox.getOrCreate("test-box")
        val sealed = box.seal(byteArrayOf(1, 2, 3), "a".toByteArray())

        assertArrayEquals(byteArrayOf(1, 2, 3), box.open(sealed, "a".toByteArray()))
        assertThrows(AEADBadTagException::class.java) { box.open(sealed, "b".toByteArray()) }
    }

    @Test
    fun selfIsCreatedOnceWithAStableSecret() = runTest {
        val first = repository.self()
        val secret = repository.beaconSecret()

        assertEquals(first, repository.self())
        assertEquals("Test phone", first.name)
        assertEquals(32, secret.size)
        assertArrayEquals(secret, repository.beaconSecret())
    }

    @Test
    fun peersAreStoredRenamedAndRemoved() = runTest {
        val info = PeerInfo(ByteArray(16) { 7 }, "Pixel 10", "0.1.0")
        repository.addPeer(PairedPeer(info, ByteArray(32) { 1 }, ByteArray(32) { 2 }))

        val peer = repository.peers.first().single()
        assertEquals("07".repeat(16), peer.deviceIdHex)
        assertEquals("Pixel 10", peer.name)
        val stored = database.dao().peer(peer.deviceIdHex)!!
        assertFalse(stored.sealedBeaconSecret.contentEquals(ByteArray(32) { 2 }))

        assertArrayEquals(ByteArray(32) { 2 }, repository.peerSecrets().getValue(peer.deviceIdHex))

        repository.renamePeer(peer.deviceIdHex, "  Work phone  ")
        assertEquals("Work phone", repository.peers.first().single().name)

        repository.removePeer(peer.deviceIdHex)
        assertTrue(repository.peers.first().isEmpty())
    }

    @Test
    fun resetForgetsPeersAndMakesNewKeys() = runTest {
        val before = repository.self()
        val keyBefore = repository.staticKey().publicKey
        repository.addPeer(
            PairedPeer(PeerInfo(ByteArray(16), "x", "1"), ByteArray(32), ByteArray(32))
        )

        repository.reset()

        assertTrue(repository.peers.first().isEmpty())
        assertFalse(before.deviceId.contentEquals(repository.self().deviceId))
        assertFalse(keyBefore.contentEquals(repository.staticKey().publicKey))
    }
}
