package com.damfizz.myvault.core

/**
 * Key hierarchy (port of `src/vault/keys.ts`):
 *
 *   master password ──Argon2id(salt)──► 32 bytes ──HKDF("myvault/v1/kek/password", salt=vaultId)──► KEK
 *   KEK ──AES-256-GCM──► wraps the random 256-bit vault key (VK)
 *   VK ──HKDF(salt=vaultId)──► records / security / local subkeys, device-trust HMAC key
 */
class VaultKeys(val vaultId: String, val records: AesKey, val security: AesKey, val local: AesKey)

object Aad {
    fun wrap(vaultId: String, purpose: String) = "myvault/v1/wrap/$purpose/$vaultId"
    fun security(vaultId: String, rev: Long, vtag: String) = "myvault/v1/security/$vaultId/$rev/$vtag"
    fun record(vaultId: String, id: String, rev: Long, vtag: String) = "myvault/v1/record/$vaultId/$id/$rev/$vtag"
    fun local(vaultId: String, key: String) = "myvault/v1/local/$vaultId/$key"
    fun backup(createdAt: Long) = "myvault/v1/backup/$createdAt"
}

object WrapPurpose {
    const val PASSWORD = "password"
    const val RECOVERY = "recovery"
    fun device(id: String) = "device:$id"
}

object Keys {
    fun generateVaultKey(): ByteArray = Encoding.randomBytes(Crypto.KEY_BYTES)

    fun deriveVaultKeys(vk: ByteArray, vaultId: String) = VaultKeys(
        vaultId = vaultId,
        records = Crypto.deriveAesKey(vk, "myvault/v1/subkey/records", vaultId),
        security = Crypto.deriveAesKey(vk, "myvault/v1/subkey/security", vaultId),
        local = Crypto.deriveAesKey(vk, "myvault/v1/subkey/local", vaultId),
    )

    /** HMAC-SHA-256 key for this device's "trusted for two-step" token. Caller wipes it. */
    fun deviceTrustKey(vk: ByteArray, vaultId: String): ByteArray = Crypto.hkdf(vk, "myvault/v1/device-trust", vaultId, 32)

    /** KEK from the master password (slow by design). */
    fun passwordKek(password: String, kdf: KdfParams, vaultId: String): AesKey {
        val stretched = Crypto.deriveFromPassword(password, kdf)
        try {
            return Crypto.deriveAesKey(stretched, "myvault/v1/kek/password", vaultId)
        } finally {
            Encoding.wipe(stretched)
        }
    }

    /** KEK from a high-entropy secret (recovery key, device secret). No Argon2 needed. */
    fun secretKek(secret: ByteArray, info: String, vaultId: String): AesKey = Crypto.deriveAesKey(secret, "myvault/v1/kek/$info", vaultId)

    fun wrapVaultKey(kek: AesKey, vk: ByteArray, vaultId: String, purpose: String): Sealed = Crypto.seal(kek, vk, Aad.wrap(vaultId, purpose))

    fun unwrapVaultKey(kek: AesKey, wrapped: Sealed, vaultId: String, purpose: String): ByteArray {
        val vk = Crypto.open(kek, wrapped, Aad.wrap(vaultId, purpose))
        if (vk.size != Crypto.KEY_BYTES) throw IllegalStateException("Invalid vault key length")
        return vk
    }

    // ---------------------------------------------------------------- emergency recovery key (160 bits)

    class RecoveryKey(val display: String, val raw: ByteArray)

    fun generateRecoveryKey(): RecoveryKey {
        val raw = Encoding.randomBytes(20)
        val display = Encoding.base32Encode(raw).chunked(4).joinToString("-")
        return RecoveryKey(display, raw)
    }

    fun parseRecoveryKey(input: String): ByteArray {
        val clean = input.uppercase().replace(Regex("[^A-Z2-7]"), "")
        require(clean.length == 32) { "A recovery key has 32 characters" }
        return Encoding.base32Decode(clean)
    }

    // ---------------------------------------------------------------- single-use 2FA recovery codes

    fun generateRecoveryCodes(count: Int = 10): List<String> = List(count) {
        val b32 = Encoding.base32Encode(Encoding.randomBytes(7)).substring(0, 10)
        "${b32.substring(0, 5)}-${b32.substring(5)}"
    }

    fun normalizeRecoveryCode(code: String): String = code.uppercase().replace(Regex("[^A-Z2-7]"), "")

    fun hashRecoveryCode(vaultId: String, code: String): String =
        Encoding.toBase64(Crypto.sha256(Encoding.utf8("myvault/v1/recovery-code/$vaultId/${normalizeRecoveryCode(code)}")))
}
