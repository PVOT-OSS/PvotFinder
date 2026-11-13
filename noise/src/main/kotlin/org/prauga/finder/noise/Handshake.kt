// SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.prauga.finder.noise

import java.security.MessageDigest

internal enum class Token {
    E,
    S,
    EE,
    ES,
    SE,
    SS,
    PSK,
}

/** A handshake pattern: which static keys are known in advance and the tokens of each message. */
internal class Pattern(
    val initiatorPreKnown: Boolean,
    val responderPreKnown: Boolean,
    val messages: List<List<Token>>,
) {
    val hasPsk: Boolean
        get() = messages.any { Token.PSK in it }

    companion object {
        private val base =
            mapOf(
                "NN" to Pattern(false, false, listOf(listOf(Token.E), listOf(Token.E, Token.EE))),
                "KK" to
                    Pattern(
                        true,
                        true,
                        listOf(
                            listOf(Token.E, Token.ES, Token.SS),
                            listOf(Token.E, Token.EE, Token.SE),
                        ),
                    ),
                "XX" to
                    Pattern(
                        false,
                        false,
                        listOf(
                            listOf(Token.E),
                            listOf(Token.E, Token.EE, Token.S, Token.ES),
                            listOf(Token.S, Token.SE),
                        ),
                    ),
                "IK" to
                    Pattern(
                        false,
                        true,
                        listOf(
                            listOf(Token.E, Token.ES, Token.S, Token.SS),
                            listOf(Token.E, Token.EE, Token.SE),
                        ),
                    ),
                "NK" to
                    Pattern(
                        false,
                        true,
                        listOf(listOf(Token.E, Token.ES), listOf(Token.E, Token.EE)),
                    ),
                "XK" to
                    Pattern(
                        false,
                        true,
                        listOf(
                            listOf(Token.E, Token.ES),
                            listOf(Token.E, Token.EE),
                            listOf(Token.S, Token.SE),
                        ),
                    ),
                "KN" to
                    Pattern(true, false, listOf(listOf(Token.E), listOf(Token.E, Token.EE, Token.SE))),
            )

        /** Parses names like `XXpsk0` or `KK`; only the patterns above are supported. */
        fun parse(name: String): Pattern {
            val match =
                Regex("([A-Z]{2})((?:psk\\d)(?:\\+psk\\d)*)?").matchEntire(name)
                    ?: throw NoiseException("Unsupported pattern $name")
            val pattern =
                base[match.groupValues[1]] ?: throw NoiseException("Unsupported pattern $name")
            val pskPositions =
                match.groupValues[2].split("+").filter { it.isNotEmpty() }.map {
                    it.removePrefix("psk").toInt()
                }
            val messages = pattern.messages.map { it.toMutableList() }
            for (position in pskPositions) {
                if (position == 0) messages[0].add(0, Token.PSK)
                else messages.getOrNull(position - 1)?.add(Token.PSK)
                    ?: throw NoiseException("Unsupported pattern $name")
            }
            return Pattern(pattern.initiatorPreKnown, pattern.responderPreKnown, messages)
        }
    }
}

/**
 * One side of a Noise handshake with Curve25519, ChaCha20-Poly1305 and SHA-256.
 *
 * @param pattern a pattern name such as `XXpsk0` or `KK`.
 * @param staticKey this side's static key, when the pattern uses one.
 * @param remoteStaticKey the other side's static key, when the pattern knows it in advance.
 * @param ephemeralKeys source of ephemeral keys; tests pass fixed ones.
 */
class NoiseHandshake(
    pattern: String,
    private val initiator: Boolean,
    prologue: ByteArray,
    private val staticKey: Dh25519? = null,
    remoteStaticKey: ByteArray? = null,
    psk: ByteArray? = null,
    private val ephemeralKeys: () -> Dh25519 = SoftwareX25519::generate,
) {
    private val pattern = Pattern.parse(pattern)
    private val symmetric = SymmetricState("Noise_${pattern}_25519_ChaChaPoly_SHA256")
    private val psk = psk?.copyOf()
    private var ephemeral: Dh25519? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStatic: ByteArray? = remoteStaticKey?.copyOf()
    private var messageIndex = 0
    private var failed = false

    init {
        if (this.pattern.hasPsk && (psk == null || psk.size != 32)) {
            throw NoiseException("Pattern needs a 32-byte PSK")
        }
        symmetric.mixHash(prologue)
        val localPre = if (initiator) this.pattern.initiatorPreKnown else this.pattern.responderPreKnown
        val remotePre = if (initiator) this.pattern.responderPreKnown else this.pattern.initiatorPreKnown
        val initiatorPre = if (initiator) localPre else remotePre
        val responderPre = if (initiator) remotePre else localPre
        if (localPre && staticKey == null) throw NoiseException("Pattern needs our static key")
        if (remotePre && remoteStatic == null) throw NoiseException("Pattern needs their static key")
        if (initiatorPre) symmetric.mixHash(if (initiator) staticKey!!.publicKey else remoteStatic!!)
        if (responderPre) symmetric.mixHash(if (initiator) remoteStatic!! else staticKey!!.publicKey)
    }

    val isFinished: Boolean
        get() = messageIndex == pattern.messages.size

    val isMyTurn: Boolean
        get() = !isFinished && (messageIndex % 2 == 0) == initiator

    /** The handshake hash, for channel binding once finished. */
    val handshakeHash: ByteArray
        get() = symmetric.handshakeHash.copyOf()

    /** The other side's static key once known. */
    val remoteStaticKey: ByteArray?
        get() = remoteStatic?.copyOf()

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!failed) { "Handshake failed" }
        check(isMyTurn) { "Not our turn to write" }
        val out = mutableListOf<ByteArray>()
        for (token in pattern.messages[messageIndex]) {
            when (token) {
                Token.E -> {
                    val e = ephemeralKeys().also { ephemeral = it }
                    out += e.publicKey
                    symmetric.mixHash(e.publicKey)
                    if (pattern.hasPsk) symmetric.mixKey(e.publicKey)
                }
                Token.S -> out += symmetric.encryptAndHash(requireNotNull(staticKey).publicKey)
                Token.PSK -> symmetric.mixKeyAndHash(psk!!)
                else -> symmetric.mixKey(dh(token))
            }
        }
        out += symmetric.encryptAndHash(payload)
        messageIndex++
        return out.fold(ByteArray(0)) { acc, part -> acc + part }
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!failed) { "Handshake failed" }
        check(!isFinished && !isMyTurn) { "Not our turn to read" }
        try {
            var offset = 0
            fun take(count: Int): ByteArray {
                if (message.size - offset < count) throw NoiseException("Message too short")
                return message.copyOfRange(offset, offset + count).also { offset += count }
            }
            for (token in pattern.messages[messageIndex]) {
                when (token) {
                    Token.E -> {
                        val re = take(X25519.KEY_SIZE).also { remoteEphemeral = it }
                        symmetric.mixHash(re)
                        if (pattern.hasPsk) symmetric.mixKey(re)
                    }
                    Token.S -> {
                        val length = X25519.KEY_SIZE + if (symmetric.hasKey) ChaChaPoly.TAG_LEN else 0
                        remoteStatic = symmetric.decryptAndHash(take(length))
                    }
                    Token.PSK -> symmetric.mixKeyAndHash(psk!!)
                    else -> symmetric.mixKey(dh(token))
                }
            }
            val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
            messageIndex++
            return payload
        } catch (e: NoiseException) {
            failed = true
            throw e
        }
    }

    /** The transport keys; call once the handshake is finished. */
    fun split(): NoiseTransport {
        check(isFinished) { "Handshake not finished" }
        val (c1, c2) = symmetric.split()
        return if (initiator) NoiseTransport(c1, c2) else NoiseTransport(c2, c1)
    }

    private fun dh(token: Token): ByteArray {
        val local: Dh25519
        val remote: ByteArray
        when (token) {
            Token.EE -> {
                local = requireNotNull(ephemeral)
                remote = requireNotNull(remoteEphemeral)
            }
            Token.ES -> {
                local = if (initiator) requireNotNull(ephemeral) else requireNotNull(staticKey)
                remote = if (initiator) requireNotNull(remoteStatic) else requireNotNull(remoteEphemeral)
            }
            Token.SE -> {
                local = if (initiator) requireNotNull(staticKey) else requireNotNull(ephemeral)
                remote = if (initiator) requireNotNull(remoteEphemeral) else requireNotNull(remoteStatic)
            }
            Token.SS -> {
                local = requireNotNull(staticKey)
                remote = requireNotNull(remoteStatic)
            }
            else -> error("Not a DH token: $token")
        }
        val shared = local.agree(remote)
        if (MessageDigest.isEqual(shared, ByteArray(shared.size))) {
            throw NoiseException("Low-order public key")
        }
        return shared
    }
}

/** The two directions of a finished handshake. */
class NoiseTransport internal constructor(
    private val send: CipherState,
    private val receive: CipherState,
) {
    fun encrypt(plaintext: ByteArray): ByteArray = send.encryptWithAd(ByteArray(0), plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray = receive.decryptWithAd(ByteArray(0), ciphertext)
}
