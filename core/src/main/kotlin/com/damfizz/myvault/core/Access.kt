package com.damfizz.myvault.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WrongPasswordError(message: String = "Incorrect master password") : Exception(message)

class InvalidCodeError(
    message: String = "Invalid or expired code",
    /** "invalid" | "expired" | "clock" */
    val reason: String = "invalid",
    /** for "clock": how far the authenticator's clock is ahead of this device (seconds) */
    val driftSeconds: Long? = null,
) : Exception(message)

class NoVaultError : Exception("No vault exists on this device")

sealed interface UnlockStep {
    class Unlocked(val session: VaultSession, val usedRecoveryCode: Boolean = false) : UnlockStep
    class SecondFactor(val challenge: VaultAccess.SecondFactorChallenge) : UnlockStep
}

/**
 * Vault creation, unlocking and security-sensitive operations — port of `src/vault/access.ts`.
 *
 * Unlock: throttle check → Argon2id KEK unwraps the vault key (GCM auth fails on a wrong password)
 * → decrypt the security config → if an Authenticator is enrolled, hold the vault key in a
 * short-lived challenge until a TOTP or recovery code is given, unless this device holds a valid
 * trust token (HMAC with a vault-key-derived key, bound to device id + enrollment + expiry).
 */
class VaultAccess(
    private val store: VaultStore,
    private val clock: Clock = Clock.SYSTEM,
    private val deviceName: String = Devices.DEFAULT_NAME,
) {
    private fun device() = Devices.get(store, deviceName)

    private fun totpCodeError(secret: String, code: String): InvalidCodeError {
        val drift = Totp.detectClockDrift(secret, code, nowMs = clock.now())
        if (drift != null) {
            return InvalidCodeError(
                "This device's clock differs from your authenticator by about ${Math.round(Math.abs(drift) / 60.0)} minute(s).",
                "clock",
                drift,
            )
        }
        return InvalidCodeError()
    }

    // ------------------------------------------------------------------ creation

    fun createVault(password: String): VaultSession {
        if (store.getHeader() != null) throw IllegalStateException("A vault already exists on this device")
        val vaultId = Encoding.randomId()
        val now = clock.now()
        val vk = Keys.generateVaultKey()
        try {
            val kdf = Crypto.newKdfParams()
            val kek = Keys.passwordKek(password, kdf, vaultId)
            val keys = Keys.deriveVaultKeys(vk, vaultId)
            val security = SecurityConfig(passwordChangedAt = now)
            val vtag = Encoding.randomId()
            val header = VaultHeader(
                vaultId = vaultId,
                createdAt = now,
                rev = 1,
                vtag = vtag,
                updatedAt = now,
                kdf = kdf,
                passwordWrap = Keys.wrapVaultKey(kek, vk, vaultId, WrapPurpose.PASSWORD),
                recoveryWrap = null,
                security = Crypto.sealJson(keys.security, security.toJson(), Aad.security(vaultId, 1, vtag)),
            )
            store.putHeader(header)
            val session = VaultSession.open(store, keys, header, security, device(), clock)
            session.saveCollections(Defaults.starterCollections(now))
            session.saveSettings { VaultSettings.DEFAULT }
            return session
        } finally {
            Encoding.wipe(vk)
        }
    }

    // ------------------------------------------------------------------ unlocking

    internal fun interface Finalizer {
        fun finalize(vk: ByteArray, header: VaultHeader): VaultHeader
    }

    private fun openSession(vk: ByteArray, header: VaultHeader, security: SecurityConfig, finalize: Finalizer?): VaultSession {
        try {
            val h = finalize?.finalize(vk, header) ?: header
            val keys = Keys.deriveVaultKeys(vk, h.vaultId)
            val sec = if (h === header) security else decryptSecurity(keys, h)
            return VaultSession.open(store, keys, h, sec, device(), clock)
        } finally {
            Encoding.wipe(vk)
        }
    }

    private fun decryptSecurity(keys: VaultKeys, h: VaultHeader): SecurityConfig =
        SecurityConfig.fromJson(Crypto.openJson(keys.security, h.security, Aad.security(h.vaultId, h.rev, h.vtag)) as JsonObject)

    // ------------------------------------------------------------------ trusted device

    private fun trustKeyName(vaultId: String) = "totpTrust:$vaultId"
    private fun replayKeyName(vaultId: String) = "totpLastStep:$vaultId"

    private fun trustMessage(vaultId: String, deviceId: String, enrolledAt: Long, until: Long) =
        Encoding.utf8("myvault/v1/device-trust/$vaultId/$deviceId/$enrolledAt/$until")

    private data class TrustToken(val until: Long, val enrolledAt: Long, val mac: String)

    private fun readTrust(vaultId: String): TrustToken? {
        val o = store.getLocal(trustKeyName(vaultId)) as? JsonObject ?: return null
        return try {
            TrustToken(
                o["until"]!!.jsonPrimitive.content.toDouble().toLong(),
                o["enrolledAt"]!!.jsonPrimitive.content.toDouble().toLong(),
                o["mac"]!!.jsonPrimitive.content,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isDeviceTrusted(vk: ByteArray, header: VaultHeader, totp: TotpSettings): Boolean {
        val token = readTrust(header.vaultId) ?: return false
        if (token.until <= clock.now() || token.enrolledAt != totp.enrolledAt) {
            store.deleteLocal(trustKeyName(header.vaultId))
            return false
        }
        return try {
            val key = Keys.deviceTrustKey(vk, header.vaultId)
            val expected = Crypto.hmacSha256Sign(key, trustMessage(header.vaultId, device().id, token.enrolledAt, token.until))
            Encoding.wipe(key)
            Encoding.constantTimeEqual(expected, Encoding.fromBase64(token.mac))
        } catch (_: Exception) {
            false
        }
    }

    private fun trustDevice(vk: ByteArray, header: VaultHeader, totp: TotpSettings, days: Int) {
        val until = clock.now() + days * 86_400_000L
        val key = Keys.deviceTrustKey(vk, header.vaultId)
        val mac = Crypto.hmacSha256Sign(key, trustMessage(header.vaultId, device().id, totp.enrolledAt, until))
        Encoding.wipe(key)
        store.setLocal(
            trustKeyName(header.vaultId),
            buildJsonObject { put("until", until); put("enrolledAt", totp.enrolledAt); put("mac", Encoding.toBase64(mac)) },
        )
    }

    /** When this device stops skipping the two-step code, or null when it is not trusted. */
    fun deviceTrustedUntil(session: VaultSession): Long? {
        val totp = session.security.totp ?: return null
        val t = readTrust(session.vaultId) ?: return null
        if (t.until <= clock.now() || t.enrolledAt != totp.enrolledAt) return null
        return t.until
    }

    fun forgetDeviceTrust(session: VaultSession) = store.deleteLocal(trustKeyName(session.vaultId))

    private fun lastUsedStep(vaultId: String): Long? =
        (store.getLocal(replayKeyName(vaultId)) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    // ------------------------------------------------------------------ second factor

    /** Holds the unwrapped vault key for at most 3 minutes until a code is supplied. */
    inner class SecondFactorChallenge internal constructor(
        vk: ByteArray,
        private val header: VaultHeader,
        private val security: SecurityConfig,
        private val finalize: Finalizer?,
    ) {
        private var held: ByteArray? = vk
        val expiresAt: Long = clock.now() + SECOND_FACTOR_TTL_MS
        val recoveryCodesLeft: Int = security.recoveryCodeHashes.size
        private val totpCfg = security.totp!!

        private fun take(): ByteArray {
            val k = held
            if (k == null || clock.now() > expiresAt) {
                cancel()
                throw InvalidCodeError("This unlock attempt expired. Please start again.", "expired")
            }
            return k
        }

        private fun release() {
            held = null
        }

        /** `trustDays` > 0 skips the code on this device for that many days. */
        fun verifyTotp(code: String, trustDays: Int = 0): VaultSession {
            Throttle.assertNotThrottled(store, clock.now())
            val key = take()
            val res = Totp.verify(totpCfg.secret, code, nowMs = clock.now(), lastUsedStep = lastUsedStep(header.vaultId))
            if (!res.ok) {
                Throttle.recordFailure(store, clock.now())
                throw totpCodeError(totpCfg.secret, code)
            }
            store.setLocal(replayKeyName(header.vaultId), JsonPrimitive(res.step!!))
            Throttle.recordSuccess(store)
            if (trustDays > 0) trustDevice(key, header, totpCfg, trustDays)
            release()
            return openSession(key, header, security, finalize)
        }

        fun useRecoveryCode(code: String): VaultSession {
            Throttle.assertNotThrottled(store, clock.now())
            val key = take()
            val hash = Keys.hashRecoveryCode(header.vaultId, code)
            if (hash !in security.recoveryCodeHashes) {
                Throttle.recordFailure(store, clock.now())
                throw InvalidCodeError("Recovery code not recognised or already used")
            }
            Throttle.recordSuccess(store)
            release()
            val session = openSession(key, header, security, finalize)
            // Consume the code (single use) — a synced header change.
            session.commitHeader { d -> d.security = d.security.copy(recoveryCodeHashes = d.security.recoveryCodeHashes - hash) }
            return session
        }

        fun cancel() {
            Encoding.wipe(held)
            held = null
        }
    }

    private fun continueUnlock(vk: ByteArray, header: VaultHeader, finalize: Finalizer? = null, allowTrustedDevice: Boolean = true): UnlockStep {
        val security = try {
            decryptSecurity(Keys.deriveVaultKeys(vk, header.vaultId), header)
        } catch (e: Exception) {
            Encoding.wipe(vk)
            throw e
        }
        val totp = security.totp
        if (totp == null || (allowTrustedDevice && isDeviceTrusted(vk, header, totp))) {
            Throttle.recordSuccess(store)
            return UnlockStep.Unlocked(openSession(vk, header, security, finalize))
        }
        return UnlockStep.SecondFactor(SecondFactorChallenge(vk, header, security, finalize))
    }

    private fun requireHeader(): VaultHeader = store.getHeader() ?: throw NoVaultError()

    /** Unwrap the raw vault key with the master password. Caller owns (and must wipe) the result. */
    private fun unwrapWithPassword(header: VaultHeader, password: String): ByteArray {
        Throttle.assertNotThrottled(store, clock.now())
        val kek = Keys.passwordKek(password, header.kdf, header.vaultId)
        try {
            return Keys.unwrapVaultKey(kek, header.passwordWrap, header.vaultId, WrapPurpose.PASSWORD)
        } catch (e: DecryptionError) {
            Throttle.recordFailure(store, clock.now())
            throw WrongPasswordError()
        }
    }

    fun unlockWithPassword(password: String): UnlockStep {
        val header = requireHeader()
        return continueUnlock(unwrapWithPassword(header, password), header)
    }

    /** Unlock with a vault key unwrapped by another primary method (e.g. biometric Keystore wrap). */
    fun unlockWithUnwrappedKey(vk: ByteArray): UnlockStep = continueUnlock(vk, requireHeader())

    /**
     * Forgotten master password: the recovery key unwraps the vault key and a new master password is
     * set. The Authenticator is still enforced, even on a trusted device.
     */
    fun unlockWithRecoveryKey(recoveryKey: String, newPassword: String): UnlockStep {
        Throttle.assertNotThrottled(store, clock.now())
        val header = requireHeader()
        val recoveryWrap = header.recoveryWrap ?: throw IllegalStateException("No recovery key was set up for this vault")
        val raw = try {
            Keys.parseRecoveryKey(recoveryKey)
        } catch (e: Exception) {
            Throttle.recordFailure(store, clock.now())
            throw InvalidCodeError("That does not look like a valid recovery key")
        }
        val vk = try {
            Keys.unwrapVaultKey(Keys.secretKek(raw, "recovery", header.vaultId), recoveryWrap, header.vaultId, WrapPurpose.RECOVERY)
        } catch (e: Exception) {
            Throttle.recordFailure(store, clock.now())
            throw InvalidCodeError("Recovery key is incorrect")
        } finally {
            Encoding.wipe(raw)
        }
        return continueUnlock(vk, header, { key, h ->
            val kdf = Crypto.newKdfParams()
            val kek = Keys.passwordKek(newPassword, kdf, h.vaultId)
            val keys = Keys.deriveVaultKeys(key, h.vaultId)
            val sec = decryptSecurity(keys, h).copy(passwordChangedAt = clock.now())
            val rev = h.rev + 1
            val vtag = Encoding.randomId()
            val next = h.copy(
                kdf = kdf,
                passwordWrap = Keys.wrapVaultKey(kek, key, h.vaultId, WrapPurpose.PASSWORD),
                rev = rev,
                vtag = vtag,
                updatedAt = clock.now(),
                security = Crypto.sealJson(keys.security, sec.toJson(), Aad.security(h.vaultId, rev, vtag)),
            )
            store.putHeader(next)
            next
        }, allowTrustedDevice = false)
    }

    // ------------------------------------------------------------------ re-authentication

    /** Verify the master password; [fn] receives the raw vault key, which is wiped afterwards. */
    fun <T> withMasterPassword(session: VaultSession, password: String, fn: (ByteArray) -> T): T {
        val vk = unwrapWithPassword(session.header, password)
        try {
            Throttle.recordSuccess(store)
            return fn(vk)
        } finally {
            Encoding.wipe(vk)
        }
    }

    fun verifyMasterPassword(session: VaultSession, password: String) = withMasterPassword(session, password) { }

    /** Verify the current second factor (TOTP or a recovery code) before a security change. */
    fun verifySecondFactor(session: VaultSession, code: String) {
        val cfg = session.security.totp ?: return
        Throttle.assertNotThrottled(store, clock.now())
        val res = Totp.verify(cfg.secret, code, nowMs = clock.now(), lastUsedStep = lastUsedStep(session.vaultId))
        if (res.ok) {
            store.setLocal(replayKeyName(session.vaultId), JsonPrimitive(res.step!!))
            Throttle.recordSuccess(store)
            return
        }
        val hash = Keys.hashRecoveryCode(session.vaultId, code)
        if (hash in session.security.recoveryCodeHashes) {
            session.commitHeader { d -> d.security = d.security.copy(recoveryCodeHashes = d.security.recoveryCodeHashes - hash) }
            Throttle.recordSuccess(store)
            return
        }
        Throttle.recordFailure(store, clock.now())
        throw totpCodeError(cfg.secret, code)
    }

    // ------------------------------------------------------------------ security changes

    fun changeMasterPassword(session: VaultSession, currentPassword: String, newPassword: String) {
        withMasterPassword(session, currentPassword) { vk ->
            val kdf = Crypto.newKdfParams()
            val kek = Keys.passwordKek(newPassword, kdf, session.vaultId)
            val wrap = Keys.wrapVaultKey(kek, vk, session.vaultId, WrapPurpose.PASSWORD)
            session.commitHeader { d ->
                d.header = d.header.copy(kdf = kdf, passwordWrap = wrap)
                d.security = d.security.copy(passwordChangedAt = clock.now())
            }
        }
    }

    /** Step 2 of Authenticator enrollment: verify a code for [secret], store it, return recovery codes. */
    fun enableTotp(session: VaultSession, secret: String, code: String): List<String> {
        val res = Totp.verify(secret, code, nowMs = clock.now())
        if (!res.ok) throw totpCodeError(secret, code)
        store.setLocal(replayKeyName(session.vaultId), JsonPrimitive(res.step!!))
        val codes = Keys.generateRecoveryCodes()
        val hashes = codes.map { Keys.hashRecoveryCode(session.vaultId, it) }
        val totp = TotpSettings(secret = secret, enrolledAt = clock.now())
        session.commitHeader { d ->
            d.security = d.security.copy(totp = totp, recoveryCodeHashes = hashes, recoveryCodesCreatedAt = clock.now())
        }
        return codes
    }

    fun disableTotp(session: VaultSession) = session.commitHeader { d ->
        d.security = d.security.copy(totp = null, recoveryCodeHashes = emptyList(), recoveryCodesCreatedAt = null)
    }

    fun regenerateRecoveryCodes(session: VaultSession): List<String> {
        val codes = Keys.generateRecoveryCodes()
        val hashes = codes.map { Keys.hashRecoveryCode(session.vaultId, it) }
        session.commitHeader { d -> d.security = d.security.copy(recoveryCodeHashes = hashes, recoveryCodesCreatedAt = clock.now()) }
        return codes
    }

    /** Create (or replace) the emergency recovery key. Requires the master password. */
    fun createRecoveryKey(session: VaultSession, password: String): String = withMasterPassword(session, password) { vk ->
        val rk = Keys.generateRecoveryKey()
        try {
            val wrap = Keys.wrapVaultKey(Keys.secretKek(rk.raw, "recovery", session.vaultId), vk, session.vaultId, WrapPurpose.RECOVERY)
            session.commitHeader { d ->
                d.header = d.header.copy(recoveryWrap = wrap)
                d.security = d.security.copy(recoveryKeyCreatedAt = clock.now())
            }
            rk.display
        } finally {
            Encoding.wipe(rk.raw)
        }
    }

    fun removeRecoveryKey(session: VaultSession) = session.commitHeader { d ->
        d.header = d.header.copy(recoveryWrap = null)
        d.security = d.security.copy(recoveryKeyCreatedAt = null)
    }

    companion object {
        const val SECOND_FACTOR_TTL_MS = 3 * 60_000L
    }
}

object Defaults {
    private data class Starter(val name: String, val icon: String, val color: String)

    private val STARTER = listOf(
        Starter("Social Media", "message-circle", "#ec4899"),
        Starter("Gaming", "gamepad", "#8b5cf6"),
        Starter("Finance", "landmark", "#10b981"),
        Starter("Work", "briefcase", "#3b82f6"),
        Starter("Education", "graduation-cap", "#f59e0b"),
        Starter("Email", "mail", "#06b6d4"),
        Starter("Shopping", "shopping-bag", "#f97316"),
        Starter("Entertainment", "clapperboard", "#ef4444"),
    )

    fun starterCollections(now: Long): List<VaultCollection> = STARTER.mapIndexed { i, s ->
        VaultCollection(
            id = Encoding.randomId(), kind = "category", name = s.name, icon = s.icon, color = s.color,
            parentId = null, pinned = false, order = i, createdAt = now, updatedAt = now,
            raw = buildJsonObject { put("parentId", kotlinx.serialization.json.JsonNull) },
        )
    }
}
