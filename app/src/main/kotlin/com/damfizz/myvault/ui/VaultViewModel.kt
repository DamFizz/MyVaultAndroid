package com.damfizz.myvault.ui

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.damfizz.myvault.R
import com.damfizz.myvault.core.Backup
import com.damfizz.myvault.core.BreachCheck
import com.damfizz.myvault.core.ItemType
import com.damfizz.myvault.core.SecurityAnalysis
import com.damfizz.myvault.core.DecryptionError
import com.damfizz.myvault.core.Encoding
import com.damfizz.myvault.core.InvalidCodeError
import com.damfizz.myvault.core.InvalidSecretKeyError
import com.damfizz.myvault.core.ItemFilter
import com.damfizz.myvault.core.Items
import com.damfizz.myvault.core.SortBy
import com.damfizz.myvault.core.SecretKeyRequiredError
import com.damfizz.myvault.core.SecretKeySource
import com.damfizz.myvault.core.StoreDeviceSecretKeys
import com.damfizz.myvault.core.Throttle
import com.damfizz.myvault.core.UnlockStep
import com.damfizz.myvault.core.VaultAccess
import com.damfizz.myvault.core.VaultCollection
import com.damfizz.myvault.core.VaultItem
import com.damfizz.myvault.core.VaultSession
import com.damfizz.myvault.core.WrongPasswordError
import com.damfizz.myvault.core.WrongSecretKeyError
import com.damfizz.myvault.data.FileVaultStore
import com.damfizz.myvault.security.BiometricKey
import com.damfizz.myvault.security.KeystoreSecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.crypto.Cipher

sealed interface Screen {
    data object Welcome : Screen
    data object Create : Screen
    data object Import : Screen
    data object Unlock : Screen
    class SecondFactor(val challenge: VaultAccess.SecondFactorChallenge) : Screen
    data object Vault : Screen
    data class Detail(val id: String) : Screen
    data object Editor : Screen
}

/** One opened backup waiting for confirmation before it replaces the vault on this phone. */
class PendingImport(val opened: Backup.Opened, val text: String)

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FileVaultStore(File(app.filesDir, "vault"))
    private val access = VaultAccess(
        store,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(40),
        secretKeys = StoreDeviceSecretKeys(store, KeystoreSecretBox),
    )

    var screen by mutableStateOf<Screen>(if (store.getHeader() == null) Screen.Welcome else Screen.Unlock)
        private set
    /** selected tab on the main screen: 0 vault, 1 generator, 2 security, 3 settings */
    var tab by mutableStateOf(0)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var needsSecretKey by mutableStateOf(store.getHeader()?.let { !access.hasSecretKeyOnDevice(it) } ?: false)
        private set
    var session by mutableStateOf<VaultSession?>(null)
        private set
    /** bumped whenever the session's data changes, so lists recompose */
    var revision by mutableStateOf(0)
        private set
    var lastKdfMillis by mutableStateOf<Long?>(null)
        private set
    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set
    /** item ids whose secrets were unlocked with the master password in this session (reprompt) */
    val revealed = mutableSetOf<String>()

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    private fun describe(e: Throwable): String = when (e) {
        is WrongPasswordError -> str(R.string.err_wrong_password)
        is WrongSecretKeyError -> str(R.string.err_wrong_secret_key)
        is InvalidSecretKeyError -> str(R.string.err_invalid_secret_key)
        is SecretKeyRequiredError -> str(R.string.err_secret_key_required)
        is InvalidCodeError -> when (e.reason) {
            "expired" -> str(R.string.err_code_expired)
            "clock" -> str(R.string.err_clock, Math.round(Math.abs(e.driftSeconds ?: 0) / 60.0))
            else -> e.message ?: str(R.string.err_code)
        }
        is Throttle.ThrottledError -> str(R.string.err_throttled, ((e.retryAt - System.currentTimeMillis()) / 1000).coerceAtLeast(1))
        is Backup.BackupPasswordError -> str(R.string.err_backup_password)
        is Backup.CorruptBackupError -> str(R.string.err_backup_corrupt)
        is BiometricKey.Invalidated -> str(R.string.err_biometric_invalidated)
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Run slow work (Argon2id) off the main thread with a busy indicator and friendly errors. */
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        error = null
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: SecretKeyRequiredError) {
                needsSecretKey = true
                error = describe(e)
            } catch (e: Throwable) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun <T> timedKdf(block: () -> T): T = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        block().also { lastKdfMillis = SystemClock.elapsedRealtime() - t0 }
    }

    fun go(s: Screen) {
        error = null
        screen = s
    }

    // ------------------------------------------------------------------ create / import

    fun create(password: String, confirm: String) = work {
        if (password.length < 10) { error = str(R.string.err_password_short); return@work }
        if (password != confirm) { error = str(R.string.err_password_mismatch); return@work }
        val s = timedKdf { access.createVault(password) }
        opened(s)
    }

    fun openBackupFile(text: String, backupPassword: String) = work {
        val opened = timedKdf { Backup.open(text, backupPassword) }
        pendingImport = PendingImport(opened, text)
    }

    fun confirmImport() {
        val p = pendingImport ?: return
        store.wipe()
        store.replaceVault(p.opened.header, p.opened.records)
        pendingImport = null
        needsSecretKey = !access.hasSecretKeyOnDevice(p.opened.header)
        go(Screen.Unlock)
    }

    fun cancelImport() {
        pendingImport = null
    }

    // ------------------------------------------------------------------ unlock

    fun unlock(password: String, secretKey: String?, rememberSecretKey: Boolean) = work {
        val src = SecretKeySource(secretKey = secretKey?.takeIf { it.isNotBlank() }, rememberSecretKey = rememberSecretKey)
        handle(timedKdf { access.unlockWithPassword(password, src) })
    }

    fun unlockWithRecoveryKey(recoveryKey: String, newPassword: String) = work {
        if (newPassword.length < 10) { error = str(R.string.err_password_short); return@work }
        handle(timedKdf { access.unlockWithRecoveryKey(recoveryKey, newPassword) })
    }

    fun verifyTotp(challenge: VaultAccess.SecondFactorChallenge, code: String, trust: Boolean) = work {
        opened(withContext(Dispatchers.Default) { challenge.verifyTotp(code, if (trust) 30 else 0) })
    }

    fun useRecoveryCode(challenge: VaultAccess.SecondFactorChallenge, code: String) = work {
        opened(withContext(Dispatchers.Default) { challenge.useRecoveryCode(code) })
    }

    private fun handle(step: UnlockStep) {
        when (step) {
            is UnlockStep.Unlocked -> opened(step.session)
            is UnlockStep.SecondFactor -> go(Screen.SecondFactor(step.challenge))
        }
    }

    private fun opened(s: VaultSession) {
        runCatching { Items.purgeExpiredTrash(s, System.currentTimeMillis()) }
        filter = ItemFilter.All
        session = s
        needsSecretKey = false
        revealed.clear()
        revision++
        go(Screen.Vault)
    }

    fun lock() {
        (screen as? Screen.SecondFactor)?.challenge?.cancel()
        session?.lock()
        session = null
        breached = null
        draft = null
        revealed.clear()
        needsSecretKey = store.getHeader()?.let { !access.hasSecretKeyOnDevice(it) } ?: false
        screen = if (store.getHeader() == null) Screen.Welcome else Screen.Unlock
    }

    // ------------------------------------------------------------------ auto-lock

    private var backgroundAt: Long? = null

    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        val at = backgroundAt ?: return
        backgroundAt = null
        if (session != null && SystemClock.elapsedRealtime() - at >= AUTO_LOCK_MS) lock()
    }

    // ------------------------------------------------------------------ reads

    fun items(): List<VaultItem> = session?.items().orEmpty().filter { it.trashedAt == null }
    fun vaultSettings() = session?.settings()
    fun collections(): List<VaultCollection> = session?.collections().orEmpty()
    fun item(id: String): VaultItem? = session?.getItem(id)
    fun vaultCreatedAt(): Long? = store.getHeader()?.createdAt
    fun hasSecretKeyMode(): Boolean = store.getHeader()?.secretKey != null
    fun hasRecoveryKey(): Boolean = store.getHeader()?.recoveryWrap != null
    fun deviceTrustedUntil(): Long? = session?.let(access::deviceTrustedUntil)

    /** Master password check before revealing a protected item's secrets. */
    fun reprompt(id: String, password: String, onOk: () -> Unit) = work {
        val s = session ?: return@work
        timedKdf { access.verifyMasterPassword(s, password) }
        revealed += id
        onOk()
    }

    // ------------------------------------------------------------------ biometric quick unlock

    private fun quickKey(): String? = store.getHeader()?.let { "quickUnlock:${it.vaultId}" }

    fun biometricEnabled(): Boolean = quickKey()?.let { store.getLocal(it) } != null

    /** Cipher to hand to BiometricPrompt for unlocking, or null when quick unlock is not set up. */
    fun biometricUnlockCipher(): Cipher? {
        val o = quickKey()?.let { store.getLocal(it) } as? JsonObject ?: return null
        return try {
            BiometricKey.decryptCipher(Encoding.fromBase64(o["iv"]!!.jsonPrimitive.content))
        } catch (e: BiometricKey.Invalidated) {
            disableBiometric()
            error = describe(e)
            null
        }
    }

    fun unlockWithBiometric(cipher: Cipher) = work {
        val o = quickKey()?.let { store.getLocal(it) } as? JsonObject ?: return@work
        val vk = try {
            cipher.doFinal(Encoding.fromBase64(o["ct"]!!.jsonPrimitive.content))
        } catch (e: Exception) {
            throw DecryptionError()
        }
        handle(withContext(Dispatchers.Default) { access.unlockWithUnwrappedKey(vk) })
    }

    /** After BiometricPrompt approved [cipher]: verify the master password and wrap the vault key. */
    fun enableBiometric(cipher: Cipher, password: String) = work {
        val s = session ?: return@work
        val key = quickKey() ?: return@work
        val ct = timedKdf { access.withMasterPassword(s, password) { vk -> cipher.doFinal(vk) } }
        store.setLocal(key, buildJsonObject { put("iv", Encoding.toBase64(cipher.iv)); put("ct", Encoding.toBase64(ct)) })
        revision++
    }

    fun disableBiometric() {
        quickKey()?.let(store::deleteLocal)
        BiometricKey.delete()
        revision++
    }

    fun forgetTrust() {
        session?.let(access::forgetDeviceTrust)
        revision++
    }

    /** Remove the vault and all device secrets from this phone (the web/backup copies are untouched). */
    fun wipeDevice() {
        lock()
        disableBiometric()
        store.wipe()
        needsSecretKey = false
        screen = Screen.Welcome
    }

    fun clearError() {
        error = null
    }

    // ------------------------------------------------------------------ items: views, editing, actions

    /** Toast request for the UI layer; [undo] puts the previous versions back. */
    class Notice(val message: String, val undo: (() -> Unit)? = null)

    var filter by mutableStateOf<ItemFilter>(ItemFilter.All)
    var sortBy by mutableStateOf(SortBy.TITLE)
    var notice by mutableStateOf<Notice?>(null)
        private set
    var noticeId by mutableStateOf(0)
        private set
    /** the item being edited (survives rotation) and how it looked when the editor opened */
    var draft by mutableStateOf<VaultItem?>(null)
    var draftInitial: VaultItem? = null
        private set
    var draftIsNew = false
        private set

    private fun now() = System.currentTimeMillis()

    private fun notify(message: Int, before: List<VaultItem>? = null) {
        val s = session
        val undo: (() -> Unit)? = if (before != null && before.isNotEmpty() && s != null) {
            { Items.undo(s, before, now()); revision++; notify(R.string.toast_undone) }
        } else null
        notice = Notice(str(message), undo)
        noticeId++
    }

    fun usage(): Map<String, Long> = runCatching { session?.getDeviceSecrets()?.usage }.getOrNull().orEmpty()

    fun visibleItems(filter: ItemFilter, query: String): List<VaultItem> {
        val s = session ?: return emptyList()
        return Items.visible(s.items(), filter, query, s.collections(), usage(), sortBy, now())
    }

    fun allTags(): List<String> = Items.allTags(session?.items().orEmpty().filter { it.trashedAt == null })

    fun markUsed(id: String) {
        runCatching { session?.markUsed(id) }
    }

    fun startNew(type: String) {
        val s = session ?: return
        val item = Items.newTemplate(type, s.settings(), filter, now())
        draft = item; draftInitial = item; draftIsNew = true
        go(Screen.Editor)
    }

    fun startEdit(id: String) {
        val item = session?.getItem(id) ?: return
        draft = item; draftInitial = item; draftIsNew = false
        go(Screen.Editor)
    }

    /** Returns validation errors ("title", "url"); empty when saved. */
    fun saveDraft(): Set<String> {
        val s = session ?: return emptySet()
        val item = draft ?: return emptySet()
        val errors = Items.validate(item)
        if (errors.isNotEmpty()) return errors
        Items.save(s, item, now(), str(R.string.untitled))
        revision++
        draft = null
        notify(R.string.toast_saved)
        go(Screen.Detail(item.id))
        return emptySet()
    }

    fun closeEditor() {
        val back = draftInitial?.takeIf { !draftIsNew }?.id
        draft = null
        go(if (back != null) Screen.Detail(back) else Screen.Vault)
    }

    fun toggleFavorite(id: String) {
        val s = session ?: return
        val item = s.getItem(id) ?: return
        Items.favorite(s, listOf(id), !item.favorite, now())
        revision++
    }

    fun trash(ids: List<String>) {
        val s = session ?: return
        val before = Items.trash(s, ids, now())
        revision++
        notify(R.string.toast_trashed, before)
    }

    fun restore(ids: List<String>) {
        val s = session ?: return
        val before = Items.restore(s, ids, now())
        revision++
        notify(R.string.toast_restored, before)
    }

    fun archive(ids: List<String>, archived: Boolean) {
        val s = session ?: return
        val before = Items.archive(s, ids, archived, now())
        revision++
        notify(if (archived) R.string.toast_archived else R.string.toast_unarchived, before)
    }

    fun duplicate(id: String) {
        val s = session ?: return
        val copy = Items.duplicate(s, id, now()) ?: return
        revision++
        notify(R.string.toast_duplicated)
        go(Screen.Detail(copy.id))
    }

    fun purge(ids: List<String>) {
        val s = session ?: return
        if (ids.isEmpty()) return
        s.purgeItems(ids)
        revision++
        notify(R.string.toast_deleted)
    }

    fun trashedIds(): List<String> = session?.items().orEmpty().filter { it.trashedAt != null }.map { it.id }

    // ------------------------------------------------------------------ generator

    fun newLoginWithPassword(password: String) {
        val s = session ?: return
        val item = Items.newTemplate(ItemType.LOGIN, s.settings(), ItemFilter.All, now())
        draft = item.copy(password = password); draftInitial = item; draftIsNew = true
        go(Screen.Editor)
    }

    fun replacePassword(id: String, password: String) {
        val s = session ?: return
        val item = s.getItem(id) ?: return
        Items.save(s, item.copy(password = password), now(), str(R.string.untitled))
        revision++
        notice = Notice(str(R.string.gen_saved_to, item.title)); noticeId++
    }

    fun logins(): List<VaultItem> = items().filter { it.type == ItemType.LOGIN && it.archivedAt == null }.sortedBy { it.title.lowercase() }

    // ------------------------------------------------------------------ security center

    fun securityReport(): SecurityAnalysis.Report? {
        val s = session ?: return null
        return SecurityAnalysis.analyze(s.items(), s.settings().oldPasswordDays, now())
    }

    /** id → times seen in breach lists (only entries > 0); null until a check has finished */
    var breached by mutableStateOf<Map<String, Int>?>(null)
        private set
    var breachProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var breachError by mutableStateOf<String?>(null)
        private set

    fun runBreachCheck() {
        val s = session ?: return
        if (breachProgress != null) return
        val byPassword = s.items().filter { it.trashedAt == null && it.archivedAt == null && !it.password.isNullOrEmpty() }.groupBy { it.password!! }
        breachError = null
        breachProgress = 0 to byPassword.size
        viewModelScope.launch {
            try {
                val found = HashMap<String, Int>()
                var done = 0
                for ((password, group) in byPassword) {
                    val n = withContext(Dispatchers.IO) { BreachCheck.pwnedCount(password, ::fetchPwnedRange) }
                    if (n > 0) for (i in group) found[i.id] = n
                    breachProgress = ++done to byPassword.size
                }
                breached = found
            } catch (e: Exception) {
                breachError = e.message ?: e.javaClass.simpleName
            } finally {
                breachProgress = null
            }
        }
    }

    private fun fetchPwnedRange(prefix: String): String {
        val c = java.net.URL(BreachCheck.URL_PREFIX + prefix).openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.useCaches = false
            c.setRequestProperty("Add-Padding", "true")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val AUTO_LOCK_MS = 60_000L
    }
}
