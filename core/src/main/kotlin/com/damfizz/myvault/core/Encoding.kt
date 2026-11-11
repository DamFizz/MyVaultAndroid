package com.damfizz.myvault.core

import java.security.SecureRandom
import java.util.Base64

/** Byte/string encoding helpers, byte-for-byte compatible with the web app's `src/lib/encoding.ts`. */
object Encoding {
    private val rng = SecureRandom()
    private val B64_RE = Regex("^[A-Za-z0-9+/]*={0,2}$")

    fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    /** Strict UTF-8 decoding (the web uses TextDecoder with fatal: true). */
    fun utf8Decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Standard base64 with padding (what `btoa` produces). */
    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(value: String): ByteArray {
        require(B64_RE.matches(value)) { "Invalid base64 data" }
        return Base64.getDecoder().decode(value)
    }

    fun toBase64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64Url(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return fromBase64(padded)
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { rng.nextBytes(it) }

    /** 128-bit random identifier, URL-safe (same shape as the web's randomId()). */
    fun randomId(): String = toBase64Url(randomBytes(16))

    /** Uniform integer in [0, max) without modulo bias. */
    fun secureRandomInt(max: Int): Int {
        require(max > 0) { "Invalid range" }
        return rng.nextInt(max)
    }

    fun constantTimeEqual(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)

    /** Best-effort wipe of key material. The JVM may still hold copies; see docs/PLAN.md. */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    // ------------------------------------------------------------------ base32 (RFC 4648, no padding)

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var bits = 0
        var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(B32[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
            value = value and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(B32[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().replace(Regex("[\\s-]"), "").replace(Regex("=+$"), "")
        require(Regex("^[A-Z2-7]*$").matches(clean)) { "Invalid base32 secret" }
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var value = 0
        for (ch in clean) {
            value = (value shl 5) or B32.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xff)
                bits -= 8
            }
            value = value and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }
}
