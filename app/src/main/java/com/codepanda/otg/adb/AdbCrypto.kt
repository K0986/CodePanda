package com.codepanda.otg.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher

/**
 * Holds the RSA key pair used to authenticate this host with `adbd`, and knows
 * how to (a) encode the public key into the peculiar binary format adb expects
 * and (b) sign the authentication token adbd sends us.
 *
 * The first time a phone sees our public key it shows the familiar
 * "Allow USB debugging from this computer?" dialog. Once the user taps
 * "Always allow", adbd remembers the key and future connections skip the prompt
 * — which is exactly why we persist the key pair to disk.
 *
 * Note that this key is a **credential**: whoever holds it inherits debugging
 * authorisation on every device the user has ever approved. It lives in the
 * app's private storage, and the manifest sets `allowBackup="false"` so it is
 * not eligible for `adb backup` extraction or cloud backup. Losing it costs one
 * extra "Always allow" tap, which is a good trade.
 */
class AdbCrypto private constructor(val keyPair: KeyPair) {

    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    /**
     * Sign the 20-byte token from an `AUTH(TOKEN)` packet.
     *
     * The token is already a SHA-1 digest, so instead of hashing again we
     * manually apply PKCS#1 v1.5 padding (including the SHA-1 DigestInfo ASN.1
     * header) and perform a raw RSA operation — mirroring exactly what the C
     * implementation in adb does.
     */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    /** The public key encoded for an `AUTH(RSAPUBLICKEY)` packet. */
    fun adbPublicKey(): ByteArray {
        val encoded = encodePublicKey(publicKey)
        val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return "$base64 codepanda@otg".toByteArray(Charsets.UTF_8)
    }

    private fun persist(dir: File) {
        File(dir, PRIVATE_KEY_FILE).writeBytes(privateKey.encoded)
        File(dir, PUBLIC_KEY_FILE).writeBytes(publicKey.encoded)
    }

    companion object {
        private const val PRIVATE_KEY_FILE = "adbkey.pk8"
        private const val PUBLIC_KEY_FILE = "adbkey.x509"
        private const val KEY_SIZE_BITS = 2048
        private const val KEY_SIZE_WORDS = KEY_SIZE_BITS / 32 // 64
        private const val KEY_SIZE_BYTES = KEY_SIZE_BITS / 8 // 256

        /**
         * PKCS#1 v1.5 padding + SHA-1 DigestInfo, sized so that
         * `padding.size + 20 (token) == 256 (modulus bytes)`.
         */
        private val SIGNATURE_PADDING: ByteArray = buildSignaturePadding()

        /** Load the persisted key pair, generating and saving a new one if absent. */
        fun loadOrCreate(dir: File): AdbCrypto {
            val pkFile = File(dir, PRIVATE_KEY_FILE)
            val pubFile = File(dir, PUBLIC_KEY_FILE)
            if (pkFile.exists() && pubFile.exists()) {
                runCatching {
                    val factory = KeyFactory.getInstance("RSA")
                    val priv = factory.generatePrivate(PKCS8EncodedKeySpec(pkFile.readBytes()))
                    val pub = factory.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                    return AdbCrypto(KeyPair(pub, priv))
                }
                // Corrupt key material — fall through and regenerate.
            }
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(KEY_SIZE_BITS)
            val crypto = AdbCrypto(generator.generateKeyPair())
            runCatching { crypto.persist(dir) }
            return crypto
        }

        private fun buildSignaturePadding(): ByteArray {
            val asn1Sha1 = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
            )
            val total = KEY_SIZE_BYTES - 20 // 236
            val padding = ByteArray(total)
            padding[0] = 0x00
            padding[1] = 0x01
            val ffCount = total - 2 - 1 - asn1Sha1.size // 218
            for (i in 0 until ffCount) padding[2 + i] = 0xff.toByte()
            padding[2 + ffCount] = 0x00
            System.arraycopy(asn1Sha1, 0, padding, 3 + ffCount, asn1Sha1.size)
            return padding
        }

        /**
         * Encode an RSA public key into the 524-byte little-endian structure
         * adbd's Montgomery-form verifier expects:
         *
         * ```
         * struct RSAPublicKey {
         *     uint32_t modulus_size_words;   // 64
         *     uint32_t n0inv;                // -1 / n[0] mod 2^32
         *     uint8_t  modulus[256];         // little-endian
         *     uint8_t  rr[256];              // R^2 mod N, little-endian
         *     uint32_t exponent;             // 65537
         * };
         * ```
         */
        private fun encodePublicKey(key: RSAPublicKey): ByteArray {
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n = key.modulus
            val r = BigInteger.ONE.shiftLeft(KEY_SIZE_WORDS * 32) // 2^2048
            val rr = r.modPow(BigInteger.valueOf(2), n) // R^2 mod N

            var n0inv = n.mod(r32)
            n0inv = n0inv.modInverse(r32)
            n0inv = r32.subtract(n0inv)

            val buffer = ByteBuffer
                .allocate(4 + 4 + KEY_SIZE_BYTES + KEY_SIZE_BYTES + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(KEY_SIZE_WORDS)
            buffer.putInt(n0inv.toInt())
            putLittleEndian(buffer, n)
            putLittleEndian(buffer, rr)
            buffer.putInt(key.publicExponent.toInt())
            return buffer.array()
        }

        /** Write a positive BigInteger as [KEY_SIZE_BYTES] little-endian bytes. */
        private fun putLittleEndian(buffer: ByteBuffer, value: BigInteger) {
            // BigInteger.toByteArray() is big-endian and may carry a sign byte.
            var bytes = value.toByteArray()
            if (bytes.size > KEY_SIZE_BYTES) {
                bytes = bytes.copyOfRange(bytes.size - KEY_SIZE_BYTES, bytes.size)
            }
            val le = ByteArray(KEY_SIZE_BYTES)
            for (i in bytes.indices) {
                le[i] = bytes[bytes.size - 1 - i]
            }
            buffer.put(le)
        }
    }
}
