package com.codepanda.otg.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import kotlin.io.path.createTempDirectory

/**
 * The `AUTH` signature is the one place where a byte out of place means the
 * device silently refuses to authorise us, with no error to go on. The padding
 * is assembled by hand (the token is already a SHA-1 digest, so there is nothing
 * left to hash), so it is worth checking against the PKCS#1 v1.5 layout that
 * adbd's verifier expects.
 */
class AdbCryptoTest {

    private val crypto = AdbCrypto.loadOrCreate(createTempDirectory("adbkeys").toFile())

    @Test
    fun `signing a token produces a full modulus-sized block`() {
        val signature = crypto.signToken(ByteArray(20) { it.toByte() })
        assertEquals(256, signature.size)
    }

    @Test
    fun `the signed block is PKCS1 v1_5 with a SHA-1 DigestInfo`() {
        val token = ByteArray(20) { (it * 7).toByte() }
        val signature = crypto.signToken(token)

        val publicKey = crypto.keyPair.public as RSAPublicKey
        val recovered = BigInteger(1, signature)
            .modPow(publicKey.publicExponent, publicKey.modulus)
            .toByteArray()

        // EM = 0x00 || 0x01 || 0xFF * 218 || 0x00 || DigestInfo || token.
        // toByteArray() drops the leading zero byte, hence 255 bytes here.
        val expected = byteArrayOf(0x01) +
            ByteArray(218) { 0xFF.toByte() } +
            byteArrayOf(0x00) +
            SHA1_DIGEST_INFO +
            token

        assertEquals(255, recovered.size)
        assertArrayEquals(expected, recovered)
    }

    @Test
    fun `a persisted key pair is reused instead of regenerated`() {
        val dir = createTempDirectory("adbkeys-reuse").toFile()
        val first = AdbCrypto.loadOrCreate(dir)
        val second = AdbCrypto.loadOrCreate(dir)

        // adbd remembers the key the user approved, so losing it would make the
        // "Allow USB debugging?" prompt reappear on every connection.
        assertEquals(first.keyPair.public, second.keyPair.public)
    }

    private companion object {
        /** ASN.1 DigestInfo prefix for a SHA-1 hash. */
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
