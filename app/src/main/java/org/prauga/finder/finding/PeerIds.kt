// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.finding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.prauga.finder.bluetooth.PeerResolver
import org.prauga.finder.bluetooth.protocol.RotatingId
import org.prauga.finder.bluetooth.protocol.TemporaryId
import org.prauga.finder.identity.IdentityRepository

/**
 * The identifiers each paired phone may be advertising around now, rebuilt when the paired phones
 * change and at every period.
 */
class PeerIds(
    private val identity: IdentityRepository,
    private val wallSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) : PeerResolver {
    @Volatile private var table: Map<TemporaryId, String> = emptyMap()

    override fun resolve(id: TemporaryId): String? = table[id]

    fun start(scope: CoroutineScope) {
        scope.launch {
            identity.peers.collectLatest {
                while (true) {
                    val now = wallSeconds()
                    rebuild(now)
                    delay(RotatingId.secondsLeft(now) * 1_000 + 500)
                }
            }
        }
    }

    private suspend fun rebuild(now: Long) {
        val secrets = identity.peerSecrets()
        table =
            buildMap {
                secrets.forEach { (deviceIdHex, secret) ->
                    RotatingId.window(secret, now).forEach { put(it, deviceIdHex) }
                    secret.fill(0)
                }
            }
    }
}
