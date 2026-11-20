package com.prelina.findmynet

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SRP-6a client for Apple's iCloud sign-in (the "pyicloud flow"),
 * replacing the legacy widget password POST that Apple now refuses
 * with HTTP 503.
 *
 * The maths follows, step for step:
 * - gcobb321/icloud3 `apple_acct/srp_password.py` for the password key
 *   derivation: SHA-256 of the password first, then PBKDF2-HMAC-SHA256
 *   (dklen 32); protocol "s2k" feeds the raw digest into PBKDF2,
 *   protocol "s2k_fo" feeds its lowercase hex string.
 * - cocagne/pysrp (`srp/_pysrp.py`) as driven by iCloud3 with
 *   `rfc5054_enable()` and `no_username_in_x()`, hash SHA-256 and the
 *   2048-bit RFC 5054 group (g = 2):
 *     k = H(pad(N) | pad(g))            (operands padded to len(N))
 *     u = H(pad(A) | pad(B))
 *     x = H(salt | H(":" | derivedKey)) (username dropped, colon kept)
 *     S = (B - k*g^x)^(a + u*x) mod N
 *     K = H(S)                          (S in minimal big-endian bytes)
 *     M1 = H(H(N) xor H(pad(g)) | H(username) | salt | A | B | K)
 *     H_AMK = H(A | M1 | K)             (A in minimal big-endian bytes)
 *   H_AMK is the expected server proof: it is sent as "m2" in
 *   signin/complete and the server's returned "m2" is verified
 *   against it in [verifyServerProof].
 *
 * The implementation is pure JVM (no Android APIs) so it can be
 * unit-tested against fixed vectors; see SrpClientTest. Nothing here
 * logs: passwords, proofs and keys never leave this class except as
 * the protocol values handed back to the caller.
 */
class SrpClient(
    private val username: String,
    private val password: String,
    aBytes: ByteArray = ByteArray(256).also { SecureRandom().nextBytes(it) }
) {
    private val a: BigInteger = BigInteger(1, aBytes)

    /** Client public ephemeral A, in minimal big-endian bytes. */
    val publicA: ByteArray = bigIntToBytes(G.modPow(a, N))

    private var m1: ByteArray? = null
    private var hAmk: ByteArray? = null
    private var sessionKey: ByteArray? = null

    /** Client proof (base64-encode before sending as "m1"). */
    val clientProofM1: ByteArray
        get() = m1 ?: throw IllegalStateException("processChallenge not called")

    /** Expected server proof (base64-encode before sending as "m2"). */
    val expectedServerProofM2: ByteArray
        get() = hAmk ?: throw IllegalStateException("processChallenge not called")

    /** SRP session key K (not needed by the web flow; kept for tests). */
    val srpSessionKey: ByteArray
        get() = sessionKey ?: throw IllegalStateException("processChallenge not called")

    /**
     * Process the server's signin/init challenge. Returns false when a
     * SRP-6a safety check fails (B mod N == 0, or u == 0) or the
     * protocol is unsupported; the caller must then abort the login.
     */
    fun processChallenge(
        salt: ByteArray,
        serverB: ByteArray,
        iterations: Int,
        protocol: String
    ): Boolean {
        val derivedKey = derivePasswordKey(password, salt, iterations, protocol)
            ?: return false

        val b = BigInteger(1, serverB)
        if (b.mod(N) == BigInteger.ZERO) {
            return false
        }

        val aInt = G.modPow(a, N)
        val u = BigInteger(1, sha256(padToN(aInt) + padToN(b)))
        if (u == BigInteger.ZERO) {
            return false
        }

        val x = genX(salt, derivedKey)
        val v = G.modPow(x, N)
        val base = b.subtract(K_MULT.multiply(v)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)

        val k = sha256(bigIntToBytes(s))
        val m1Value = calculateM(username, salt, aInt, b, k)
        val hAmkValue = sha256(bigIntToBytes(aInt) + m1Value + k)

        sessionKey = k
        m1 = m1Value
        hAmk = hAmkValue
        return true
    }

    /**
     * Verify the server proof returned by signin/complete against the
     * locally computed H_AMK. The login must fail when this returns
     * false.
     */
    fun verifyServerProof(serverM2: ByteArray): Boolean {
        val expected = hAmk ?: return false
        return MessageDigest.isEqual(expected, serverM2)
    }

    companion object {
        // 2048-bit RFC 5054 group prime (pysrp NG_2048), g = 2.
        private val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4" +
                "A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60" +
                "95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF" +
                "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907" +
                "8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861" +
                "60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB" +
                "FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16
        )
        private val G = BigInteger.valueOf(2)

        private const val N_LEN = 256

        // k = H(pad(N) | pad(g)), operands padded to len(N) (RFC 5054 mode).
        private val K_MULT: BigInteger = BigInteger(1, sha256(padToN(N) + padToN(G)))

        // H(N) xor H(pad(g)), the first component of the M1 hash.
        private val H_N_XOR_G: ByteArray = run {
            val hN = sha256(bigIntToBytes(N))
            val hG = sha256(ByteArray(N_LEN - 1) + bigIntToBytes(G))
            ByteArray(hN.size) { i -> (hN[i].toInt() xor hG[i].toInt()).toByte() }
        }

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        /** Minimal big-endian bytes (pysrp long_to_bytes; 0 -> empty). */
        private fun bigIntToBytes(value: BigInteger): ByteArray {
            if (value == BigInteger.ZERO) {
                return ByteArray(0)
            }
            val raw = value.toByteArray()
            var start = 0
            while (start < raw.size - 1 && raw[start] == 0.toByte()) {
                start++
            }
            return raw.copyOfRange(start, raw.size)
        }

        private fun padToN(value: BigInteger): ByteArray {
            val raw = bigIntToBytes(value)
            if (raw.size >= N_LEN) {
                return raw
            }
            return ByteArray(N_LEN - raw.size) + raw
        }

        /**
         * Apple's password key derivation (iCloud3 SrpPassword):
         * SHA-256 of the password, then PBKDF2-HMAC-SHA256 with dklen
         * 32. "s2k" feeds the raw digest to PBKDF2; "s2k_fo" feeds its
         * lowercase hex string. Returns null for an unsupported
         * protocol.
         */
        fun derivePasswordKey(
            password: String,
            salt: ByteArray,
            iterations: Int,
            protocol: String
        ): ByteArray? {
            val digest = sha256(password.toByteArray(Charsets.UTF_8))
            val pbkdfInput = when (protocol) {
                "s2k" -> digest
                "s2k_fo" -> toHex(digest).toByteArray(Charsets.UTF_8)
                else -> return null
            }
            return pbkdf2HmacSha256(pbkdfInput, salt, iterations, 32)
        }

        /**
         * PBKDF2-HMAC-SHA256 over raw bytes, implemented directly on
         * javax.crypto.Mac so the password bytes are used exactly as
         * given (SecretKeyFactory's char-based PBEKeySpec conversion
         * is provider-dependent for non-ASCII bytes).
         */
        private fun pbkdf2HmacSha256(
            password: ByteArray,
            salt: ByteArray,
            iterations: Int,
            dkLen: Int
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val result = ByteArray(dkLen)
            var offset = 0
            var blockIndex = 1
            while (offset < dkLen) {
                // U1 = HMAC(password, salt | INT_32_BE(blockIndex))
                val block = salt + byteArrayOf(
                    (blockIndex ushr 24).toByte(),
                    (blockIndex ushr 16).toByte(),
                    (blockIndex ushr 8).toByte(),
                    blockIndex.toByte()
                )
                var u = mac.doFinal(block)
                val t = u.copyOf()
                for (i in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                val take = minOf(t.size, dkLen - offset)
                System.arraycopy(t, 0, result, offset, take)
                offset += take
                blockIndex++
            }
            return result
        }

        /** pysrp gen_x with no_username_in_x: x = H(salt | H(":" | p)). */
        private fun genX(salt: ByteArray, derivedKey: ByteArray): BigInteger {
            val inner = sha256(byteArrayOf(':'.code.toByte()) + derivedKey)
            return BigInteger(1, sha256(salt + inner))
        }

        /** pysrp calculate_M with SHA-256. */
        private fun calculateM(
            username: String,
            salt: ByteArray,
            aPub: BigInteger,
            bPub: BigInteger,
            sessionKey: ByteArray
        ): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(H_N_XOR_G)
            md.update(sha256(username.toByteArray(Charsets.UTF_8)))
            md.update(salt)
            md.update(bigIntToBytes(aPub))
            md.update(bigIntToBytes(bPub))
            md.update(sessionKey)
            return md.digest()
        }

        private fun toHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
