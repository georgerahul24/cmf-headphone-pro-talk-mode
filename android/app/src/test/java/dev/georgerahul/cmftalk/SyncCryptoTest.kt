package dev.georgerahul.cmftalk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's sync crypto must match the Mac's (CryptoKit) exactly. Values made on the Mac. */
class SyncCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val phonePriv = ByteArray(32) { 0x11 }
    private val macPub = hex("0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20")
    private val secret = hex("6775da1c329836ab695bcaf2f7f085567a5b9d6242fd7b8cffd6e69ea6d4c2c8")
    private val session = hex("22e9992675e3117d2ffaa805953a8a75e44ab91138cc9eaaa8aa4664e49e5823")
    private val sealed = hex("000102030405060708090a0b6258c091883e0db1629450715e6cc80dcf71449597d768403f3422c1")

    @Test fun publicKey() = assertArrayEquals(
        hex("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"), SyncCrypto.publicKey(phonePriv))

    @Test fun macPublicKey() = assertArrayEquals(macPub, SyncCrypto.publicKey(ByteArray(32) { 0x22 }))

    @Test fun pairSecret() = assertArrayEquals(secret, SyncCrypto.pairSecret(phonePriv, macPub, "123456"))

    @Test fun proofs() {
        assertArrayEquals(hex("170c709948955853b3e9a571efd0813cd7960cf4d966ab3213799c21c5e2613e"), SyncCrypto.hmac(secret, "phone".toByteArray()))
        assertArrayEquals(hex("6eeed479b1eff3edb440bb7666f7d963c250b0fec51e2f221e9faf33cf7be200"), SyncCrypto.hmac(secret, "mac".toByteArray()))
    }

    @Test fun sessionKey() = assertArrayEquals(session, SyncCrypto.sessionKey(secret))

    @Test fun sealMatchesMac() =
        assertArrayEquals(sealed, SyncCrypto.seal(session, "{\"t\":\"ping\"}".toByteArray(), hex("000102030405060708090a0b")))

    @Test fun openFromMac() = assertEquals("{\"t\":\"ping\"}", String(SyncCrypto.open(session, sealed)))

    @Test fun bothSidesAgree() {
        val a = SyncCrypto.randomBytes(32); val b = SyncCrypto.randomBytes(32)
        assertArrayEquals(SyncCrypto.x25519(a, SyncCrypto.publicKey(b)), SyncCrypto.x25519(b, SyncCrypto.publicKey(a)))
    }
}
