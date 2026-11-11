package com.damfizz.myvault.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port of the web app's `src/lib/crypto.ts`: Argon2id, HKDF-SHA-256 and AES-256-GCM with AAD.
 * Output must stay byte-compatible with Web Crypto + hash-wasm (proven by the fixture tests).
 */
object Crypto {
    const val CRYPTO_FORMAT_VERSION = 1
    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Default Argon2id cost, identical to the web app (64 MiB, 3 passes, 1 lane). */
    val DEFAULT_KDF = KdfCost(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

    @Volatile
    private var kdfOverride: KdfCost? = null

    /** Test-only hook to keep unit tests fast. Never called by application code. */
    fun setKdfCostForTesting(cost: KdfCost?) {
        kdfOverride = cost
    }

    fun newKdfParams(): KdfParams {
        val c = kdfOverride ?: DEFAULT_KDF
        return KdfParams("argon2id", Encoding.toBase64(Encoding.randomBytes(16)), c.memoryKiB, c.iterations, c.parallelism)
    }

    fun validateKdfParams(p: KdfParams) {
        require(p.alg == "argon2id") { "Unsupported key derivation algorithm" }
        require(p.memoryKiB in 1024..1024 * 1024) { "Invalid Argon2id memory parameter" }
        require(p.iterations in 1..50) { "Invalid Argon2id iteration parameter" }
        require(p.parallelism in 1..16) { "Invalid Argon2id parallelism parameter" }
        require(Encoding.fromBase64(p.salt).size >= 16) { "Invalid salt" }
    }

    /** Argon2id(NFC(password)) → 32 bytes. The caller must wipe the result. */
    fun deriveFromPassword(password: String, params: KdfParams): ByteArray {
        validateKdfParams(params)
        val pw = Encoding.utf8(Normalizer.normalize(password, Normalizer.Form.NFC))
        try {
            val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(Encoding.fromBase64(params.salt))
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKiB)
                .withParallelism(params.parallelism)
                .build()
            val out = ByteArray(KEY_BYTES)
            Argon2BytesGenerator().apply { init(p) }.generateBytes(pw, out)
            return out
        } finally {
            Encoding.wipe(pw)
        }
    }

    // ------------------------------------------------------------------ HKDF-SHA-256 (RFC 5869)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        // An empty HMAC key is equivalent to an all-zero key of one hash length (RFC 2104 padding).
        val k = if (key.isEmpty()) ByteArray(32) else key
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(k, "HmacSHA256"))
            doFinal(data)
        }
    }

    /** HKDF-SHA-256 with UTF-8 `salt` and `info`, exactly as Web Crypto's HKDF deriveBits. */
    fun hkdf(ikm: ByteArray, info: String, salt: String = "", length: Int = 32): ByteArray =
        hkdfRaw(ikm, Encoding.utf8(info), Encoding.utf8(salt), length)

    /** HKDF-SHA-256 with raw `info` and `salt` bytes. */
    fun hkdfRaw(ikm: ByteArray, infoBytes: ByteArray, salt: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmacSha256(prk, t + infoBytes + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        Encoding.wipe(prk)
        return out
    }

    /** HKDF → AES-256-GCM key separated by `info` (web: deriveAesKey). */
    fun deriveAesKey(ikm: ByteArray, info: String, salt: String = ""): AesKey {
        val raw = hkdf(ikm, info, salt, KEY_BYTES)
        return AesKey(raw).also { Encoding.wipe(raw) }
    }

    fun hmacSha256Sign(key: ByteArray, data: ByteArray): ByteArray = hmacSha256(key, data)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // ------------------------------------------------------------------ AES-256-GCM

    fun seal(key: AesKey, plaintext: ByteArray, aad: String): Sealed {
        val iv = Encoding.randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key.spec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(Encoding.utf8(aad))
        val ct = cipher.doFinal(plaintext)
        return Sealed(Encoding.toBase64(iv), Encoding.toBase64(ct))
    }

    fun open(key: AesKey, sealed: Sealed, aad: String): ByteArray {
        val iv: ByteArray
        val ct: ByteArray
        try {
            iv = Encoding.fromBase64(sealed.iv)
            ct = Encoding.fromBase64(sealed.ct)
        } catch (e: IllegalArgumentException) {
            throw DecryptionError("Encrypted data is malformed")
        }
        if (iv.size != IV_BYTES || ct.size < 16) throw DecryptionError("Encrypted data is malformed")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key.spec, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(Encoding.utf8(aad))
            return cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw DecryptionError()
        } catch (e: java.security.GeneralSecurityException) {
            throw DecryptionError()
        }
    }

    fun sealJson(key: AesKey, value: JsonElement, aad: String): Sealed {
        val bytes = Encoding.utf8(VaultJson.json.encodeToString(JsonElement.serializer(), value))
        try {
            return seal(key, bytes, aad)
        } finally {
            Encoding.wipe(bytes)
        }
    }

    fun openJson(key: AesKey, sealed: Sealed, aad: String): JsonElement {
        val bytes = open(key, sealed, aad)
        try {
            return VaultJson.json.parseToJsonElement(Encoding.utf8Decode(bytes))
        } finally {
            Encoding.wipe(bytes)
        }
    }
}

data class KdfCost(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

@Serializable
data class KdfParams(
    val alg: String,
    /** base64 random salt (16 bytes) */
    val salt: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
)

/** AES-GCM ciphertext: base64 96-bit IV and base64 ciphertext including the 128-bit tag. */
@Serializable
data class Sealed(val iv: String, val ct: String)

/** An AES-256 key. Holds raw bytes in memory (JVM keys are not wipeable; documented limit). */
class AesKey(raw: ByteArray) {
    init {
        require(raw.size == Crypto.KEY_BYTES) { "AES-256 key must be 32 bytes" }
    }
    internal val spec = SecretKeySpec(raw.copyOf(), "AES")
}

/** Thrown when authenticated decryption fails: wrong key, tampered data or wrong context. */
class DecryptionError(message: String = "Decryption failed: wrong key or data was modified") : Exception(message)
