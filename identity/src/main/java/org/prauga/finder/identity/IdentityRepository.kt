// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.identity

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.prauga.finder.noise.Dh25519
import org.prauga.finder.pairing.PairedPeer
import org.prauga.finder.pairing.PeerInfo

/** This phone as its peers know it. */
data class SelfIdentity(val deviceId: ByteArray, val name: String) {
    override fun equals(other: Any?): Boolean =
        other is SelfIdentity && deviceId.contentEquals(other.deviceId) && name == other.name

    override fun hashCode(): Int = 31 * deviceId.contentHashCode() + name.hashCode()
}

/** A paired phone, without its secret. */
data class Peer(
    val deviceIdHex: String,
    val name: String,
    val appVersion: String,
    val pairedAtMillis: Long,
)

/**
 * The phone's keys and its paired phones. Beacon secrets are stored sealed under a Keystore key and
 * only opened in memory when needed.
 */
class IdentityRepository(
    private val dao: IdentityDao,
    private val defaultName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val staticAlias: String = STATIC_ALIAS,
    private val boxAlias: String = BOX_ALIAS,
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutex = Mutex()

    val peers: Flow<List<Peer>> =
        dao.peers().map { list ->
            list.map { Peer(it.deviceIdHex, it.name, it.appVersion, it.pairedAtMillis) }
        }

    suspend fun self(): SelfIdentity = withContext(io) { selfEntity().let { SelfIdentity(it.deviceId, it.name) } }

    suspend fun staticKey(): Dh25519 = withContext(io) { KeystoreStaticKey.getOrCreate(staticAlias) }

    suspend fun beaconSecret(): ByteArray =
        withContext(io) {
            val self = selfEntity()
            box().open(self.sealedBeaconSecret, SELF_LABEL + self.deviceId)
        }

    suspend fun peerInfo(appVersion: String): PeerInfo {
        val self = self()
        return PeerInfo(self.deviceId, self.name, appVersion)
    }

    suspend fun renameSelf(name: String) = withContext(io) {
        selfEntity()
        dao.renameSelf(name.trim().take(PeerInfo.MAX_TEXT))
    }

    /** Stores a newly paired phone, replacing an earlier pairing with the same phone. */
    suspend fun addPeer(paired: PairedPeer) =
        withContext(io) {
            val id = paired.info.deviceId.toHex()
            dao.putPeer(
                PeerEntity(
                    deviceIdHex = id,
                    name = paired.info.name,
                    appVersion = paired.info.appVersion,
                    staticPublicKey = paired.staticPublicKey,
                    sealedBeaconSecret = box().seal(paired.beaconSecret, PEER_LABEL + paired.info.deviceId),
                    pairedAtMillis = clock(),
                )
            )
        }

    /** Each paired phone's static public key by device ID. */
    suspend fun peerStaticKeys(): Map<String, ByteArray> =
        withContext(io) { dao.peerList().associate { it.deviceIdHex to it.staticPublicKey } }

    /** Each paired phone's beacon secret by device ID, opened for deriving its identifiers. */
    suspend fun peerSecrets(): Map<String, ByteArray> =
        withContext(io) {
            dao.peerList().associate {
                it.deviceIdHex to box().open(it.sealedBeaconSecret, PEER_LABEL + it.deviceIdHex.hexToBytes())
            }
        }

    suspend fun renamePeer(deviceIdHex: String, name: String) =
        withContext(io) { dao.renamePeer(deviceIdHex, name.trim().take(PeerInfo.MAX_TEXT)) }

    suspend fun removePeer(deviceIdHex: String) = withContext(io) { dao.removePeer(deviceIdHex) }

    /** Forgets every paired phone and creates new keys and a new beacon secret. */
    suspend fun reset() =
        withContext(io) {
            mutex.withLock {
                dao.removeAllPeers()
                dao.removeSelf()
                KeystoreStaticKey.delete(staticAlias)
                SecretBox.delete(boxAlias)
            }
        }

    private suspend fun selfEntity(): SelfEntity =
        mutex.withLock {
            dao.self()
                ?: run {
                    val deviceId = ByteArray(PeerInfo.DEVICE_ID_SIZE).also(random::nextBytes)
                    val secret = ByteArray(BEACON_SECRET_SIZE).also(random::nextBytes)
                    KeystoreStaticKey.getOrCreate(staticAlias)
                    val entity =
                        SelfEntity(
                            deviceId = deviceId,
                            name = defaultName().take(PeerInfo.MAX_TEXT),
                            sealedBeaconSecret = box().seal(secret, SELF_LABEL + deviceId),
                            createdAtMillis = clock(),
                        )
                    secret.fill(0)
                    dao.putSelf(entity)
                    entity
                }
        }

    private fun box(): SecretBox = SecretBox.getOrCreate(boxAlias)

    companion object {
        const val BEACON_SECRET_SIZE = 32

        fun create(context: Context, defaultName: () -> String): IdentityRepository =
            IdentityRepository(IdentityDatabase.create(context).dao(), defaultName)

        private const val STATIC_ALIAS = "finder-static"
        private const val BOX_ALIAS = "finder-box"
        private val SELF_LABEL = "self".toByteArray()
        private val PEER_LABEL = "peer".toByteArray()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
