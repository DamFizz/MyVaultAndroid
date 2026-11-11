package com.damfizz.myvault.core

import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 4226 / RFC 6238 (SHA-1, 6 digits, 30 s), port of `src/lib/totp.ts`. */
object Totp {
    fun generateSecret(): String = Encoding.base32Encode(Encoding.randomBytes(20))

    fun hotp(secret: ByteArray, counter: Long, digits: Int = 6): String {
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(if (secret.isEmpty()) ByteArray(1) else secret, "HmacSHA1"))
            doFinal(msg)
        }
        val offset = mac[mac.size - 1].toInt() and 0x0f
        val bin = ((mac[offset].toInt() and 0x7f) shl 24) or
            ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or
            (mac[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** JS: Math.floor(nowMs / 1000 / period). */
    fun timeStep(nowMs: Long, period: Int = 30): Long = Math.floorDiv(nowMs, 1000L * period)

    fun totp(secretB32: String, nowMs: Long = System.currentTimeMillis(), digits: Int = 6, period: Int = 30): String =
        hotp(Encoding.base32Decode(secretB32), timeStep(nowMs, period), digits)

    data class VerifyResult(val ok: Boolean, val step: Long? = null)

    /** Accepts ±`window` steps; a code at or before `lastUsedStep` is rejected (replay protection). */
    fun verify(
        secretB32: String,
        code: String,
        nowMs: Long = System.currentTimeMillis(),
        window: Int = 1,
        lastUsedStep: Long? = null,
        digits: Int = 6,
        period: Int = 30,
    ): VerifyResult {
        val normalized = code.replace(Regex("\\s"), "")
        if (!Regex("^\\d{$digits}$").matches(normalized)) return VerifyResult(false)
        val secret = Encoding.base32Decode(secretB32)
        val current = timeStep(nowMs, period)
        var matched: Long? = null
        // Check every candidate (no early exit) so timing does not reveal which step matched.
        for (d in -window..window) {
            val step = current + d
            val candidate = hotp(secret, step, digits)
            if (Encoding.constantTimeEqual(Encoding.utf8(candidate), Encoding.utf8(normalized)) && matched == null) matched = step
        }
        if (matched == null) return VerifyResult(false)
        if (lastUsedStep != null && matched <= lastUsedStep) return VerifyResult(false)
        return VerifyResult(true, matched)
    }

    /** Seconds the authenticator's clock is ahead (+) or behind (−) when the code is outside the window. */
    fun detectClockDrift(
        secretB32: String,
        code: String,
        nowMs: Long = System.currentTimeMillis(),
        window: Int = 1,
        maxSteps: Int = 20,
        digits: Int = 6,
        period: Int = 30,
    ): Long? {
        val normalized = code.replace(Regex("\\s"), "")
        if (!Regex("^\\d{$digits}$").matches(normalized)) return null
        val secret = Encoding.base32Decode(secretB32)
        val current = timeStep(nowMs, period)
        for (d in (window + 1)..maxSteps) {
            for (step in longArrayOf(current + d, current - d)) {
                if (hotp(secret, step, digits) == normalized) return (step - current) * period
            }
        }
        return null
    }

    fun otpauthUri(secretB32: String, account: String, issuer: String = "MyVault"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        // Matches the web: label via encodeURIComponent, params via URLSearchParams.
        val label = enc("$issuer:$account")
        fun form(s: String) = URLEncoder.encode(s, "UTF-8")
        return "otpauth://totp/$label?secret=${form(secretB32)}&issuer=${form(issuer)}&algorithm=SHA1&digits=6&period=30"
    }

    fun formatSecretForDisplay(secretB32: String): String = secretB32.chunked(4).joinToString(" ")
}
