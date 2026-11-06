package dev.georgerahul.cmftalk

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto for syncing with the Mac, matching Apple's CryptoKit byte for byte (checked by
 * SyncCryptoTest against values made on the Mac):
 * X25519 key agreement (RFC 7748), HKDF-SHA256, HMAC-SHA256 and AES-256-GCM
 * (12-byte nonce + ciphertext + 16-byte tag, like CryptoKit's "combined").
 *
 * X25519 is written out here so it works the same on every phone, without depending on which
 * algorithms a phone's crypto provider happens to have. It only runs once, when pairing.
 */
object SyncCrypto {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun toLe(x: BigInteger): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    /** X25519(scalar, u), both 32 bytes, little-endian, as in RFC 7748. */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        val kk = le(k)
        val uu = u.copyOf()
        uu[31] = (uu[31].toInt() and 127).toByte()
        val x1 = le(uu).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (kk.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).mod(P).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
        return toLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun publicKey(priv: ByteArray) = x25519(priv, BASE)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** HKDF-SHA256 (RFC 5869), up to 32 bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(len)
    }

    /** The pairing secret, from our private key, the Mac's public key and the code. */
    fun pairSecret(priv: ByteArray, peerPub: ByteArray, code: String): ByteArray =
        hkdf(x25519(priv, peerPub), code.toByteArray(), "cmftalk-pair".toByteArray())

    fun sessionKey(secret: ByteArray): ByteArray =
        hkdf(secret, "cmftalk".toByteArray(), "cmftalk-session".toByteArray())

    fun seal(key: ByteArray, plain: ByteArray, nonce: ByteArray = randomBytes(12)): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, combined: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, combined, 0, 12))
        return c.doFinal(combined, 12, combined.size - 12)
    }
}
