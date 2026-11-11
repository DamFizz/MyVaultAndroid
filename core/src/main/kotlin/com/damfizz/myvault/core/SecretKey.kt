package com.damfizz.myvault.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Secret Key (vault format v2) — port of the web's `src/vault/keys.ts` (Secret Key section) and
 * `src/vault/secret-key.ts`. See docs/SECRET-KEY.md.
 *
 * KEK = HKDF-SHA-256(ikm = Secret Key, salt = Argon2id(NFC(password)), info = "myvault/v2/kek/password+secret-key/" + vaultId)
 */
@Serializable
data class SecretKeyInfo(val id: String, val createdAt: Long)

class InvalidSecretKeyError(message: String = "That does not look like a valid Secret Key") : Exception(message)
class SecretKeyRequiredError(val vaultId: String) :
    Exception("This vault needs its Secret Key on this device. Scan the QR code from another device or type it from your Emergency Kit.")
class WrongSecretKeyError : Exception("This Secret Key does not belong to this vault.")
class RecoveryKeyRequiredError :
    Exception("Create an emergency recovery key before turning on the Secret Key, so the vault can never be lost.")

object SecretKeys {
    const val BYTES = 16
    private const val PREFIX = "MV2"
    private const val CHARS = 26

    private fun checksum(raw: ByteArray): String =
        Encoding.base32Encode(Crypto.sha256(Encoding.utf8("myvault/v2/secret-key-check/") + raw)).substring(0, 2)

    /** "MV2-" + 26 base32 chars + 2 checksum chars, in groups of 4. */
    fun format(raw: ByteArray): String {
        if (raw.size != BYTES) throw InvalidSecretKeyError()
        return "$PREFIX-" + (Encoding.base32Encode(raw) + checksum(raw)).chunked(4).joinToString("-")
    }

    class Generated(val display: String, val raw: ByteArray)

    fun generate(): Generated = Encoding.randomBytes(BYTES).let { Generated(format(it), it) }

    fun qrPayload(vaultId: String, raw: ByteArray) = "myvault-sk:2:$vaultId:${Encoding.base32Encode(raw)}"

    fun toStored(raw: ByteArray): String = Encoding.base32Encode(raw)

    fun fromStored(stored: String): ByteArray {
        if (!Regex("^[A-Z2-7]{26}$").matches(stored)) throw InvalidSecretKeyError("Stored Secret Key is corrupted")
        return Encoding.base32Decode(stored)
    }

    class Parsed(val raw: ByteArray, val vaultId: String? = null)

    /** Display form (any case, spaces/dashes ignored, with or without "MV2") or a QR payload. */
    fun parse(input: String): Parsed {
        val trimmed = input.trim()
        Regex("^myvault-sk:2:([A-Za-z0-9_-]+):([A-Z2-7]{26})$").matchEntire(trimmed)?.let {
            return Parsed(Encoding.base32Decode(it.groupValues[2]), it.groupValues[1])
        }
        var clean = trimmed.uppercase().replace(Regex("[\\s-]"), "")
        if (clean.length == CHARS + 2 + PREFIX.length && clean.startsWith(PREFIX)) clean = clean.substring(PREFIX.length)
        if (!Regex("^[A-Z2-7]{28}$").matches(clean)) throw InvalidSecretKeyError()
        val raw = Encoding.base32Decode(clean.substring(0, CHARS))
        if (raw.size != BYTES || checksum(raw) != clean.substring(CHARS)) throw InvalidSecretKeyError("Secret Key has a typo (checksum mismatch)")
        return Parsed(raw)
    }

    /** Public identifier of a Secret Key for one vault (72 bits, base64url). */
    fun id(raw: ByteArray, vaultId: String): String = Encoding.toBase64Url(Crypto.hkdf(raw, "myvault/v2/secret-key-id", vaultId, 9))

    fun passwordSecretKek(password: String, kdf: KdfParams, vaultId: String, sk: ByteArray): AesKey {
        val stretched = Crypto.deriveFromPassword(password, kdf)
        try {
            val raw = Crypto.hkdfRaw(sk, Encoding.utf8("myvault/v2/kek/password+secret-key/$vaultId"), stretched, Crypto.KEY_BYTES)
            return AesKey(raw).also { Encoding.wipe(raw) }
        } finally {
            Encoding.wipe(stretched)
        }
    }
}

/** Encrypts device-local secrets at rest. Android supplies an Android Keystore implementation (stage 3). */
interface LocalSecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray

    companion object {
        /** No extra protection — for tests and the JVM only. */
        val NONE = object : LocalSecretBox {
            override fun seal(plain: ByteArray) = plain.copyOf()
            override fun open(sealed: ByteArray) = sealed.copyOf()
        }
    }
}

/** Where this device keeps the Secret Key of each vault (never synced). */
interface DeviceSecretKeys {
    class Stored(val id: String, val raw: ByteArray)

    fun load(vaultId: String): Stored?
    fun save(vaultId: String, id: String, raw: ByteArray)
    fun forget(vaultId: String)
}

/** Keeps the Secret Key in the store's device-local table, sealed by [box]. */
class StoreDeviceSecretKeys(private val store: VaultStore, private val box: LocalSecretBox = LocalSecretBox.NONE) : DeviceSecretKeys {
    private fun key(vaultId: String) = "secretKey:$vaultId"

    override fun load(vaultId: String): DeviceSecretKeys.Stored? {
        val o = store.getLocal(key(vaultId)) as? JsonObject ?: return null
        return try {
            val id = (o["id"] as JsonPrimitive).contentOrNull!!
            DeviceSecretKeys.Stored(id, box.open(Encoding.fromBase64((o["sealed"] as JsonPrimitive).content)))
        } catch (_: Exception) {
            null
        }
    }

    override fun save(vaultId: String, id: String, raw: ByteArray) {
        store.setLocal(key(vaultId), buildJsonObject { put("id", id); put("sealed", Encoding.toBase64(box.seal(raw))) })
    }

    override fun forget(vaultId: String) = store.deleteLocal(key(vaultId))
}

/** How to obtain the Secret Key when the vault needs one. */
data class SecretKeySource(
    /** typed or scanned by the user (display form or QR payload) */
    val secretKey: String? = null,
    /** keep a typed key on this device after a successful unlock */
    val rememberSecretKey: Boolean = true,
    /** 26-char copy from an unlocked session's SecurityConfig (re-authentication) */
    val stored: String? = null,
)
