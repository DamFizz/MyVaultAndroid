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
    data object Organize : Screen
    data object BackupRestore : Screen
    data object Sync : Screen
}

/** One opened backup waiting for confirmation before it replaces the vault on this phone. */
class PendingImport(val opened: Backup.Opened, val text: String)

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FileVaultStore(File(app.filesDir, "vault"))
    val prefs = com.damfizz.myvault.data.Prefs(app)
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
    private var revisionState by mutableStateOf(0)
    var revision: Int
        get() = revisionState
        private set(value) {
            revisionState = value
            if (session != null && !syncing) scheduleAutoSync()
        }
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
        val step = timedKdf { access.unlockWithPassword(password, src) }
        // Kept only until the "use fingerprint or face next time?" offer is answered (or the vault locks),
        // so turning it on does not ask for the password a second time.
        if (!biometricEnabled() && !prefs.biometricOfferDismissed &&
            com.damfizz.myvault.security.BiometricSupport.status(getApplication()) == com.damfizz.myvault.security.BiometricSupport.Status.AVAILABLE
        ) offerPassword = password
        handle(step)
    }

    /** Password just used to unlock, held briefly for the biometric offer; never stored. */
    private var offerPassword: String? = null
    var biometricOffer by mutableStateOf(false)
        private set

    /** User accepted the offer and BiometricPrompt approved [cipher]. */
    fun acceptBiometricOffer(cipher: Cipher) {
        val pw = offerPassword ?: return
        offerPassword = null
        biometricOffer = false
        enableBiometric(cipher, pw)
    }

    fun declineBiometricOffer() {
        offerPassword = null
        biometricOffer = false
        prefs.updateBiometricOfferDismissed(true)
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
        biometricOffer = offerPassword != null
    }

    fun lock() {
        offerPassword = null
        biometricOffer = false
        (screen as? Screen.SecondFactor)?.challenge?.cancel()
        session?.lock()
        session = null
        breached = null
        draft = null
        selection = emptySet()
        pendingSecretKey = null
        shownSecretKey = null
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
        val secs = prefs.lockOnHiddenSeconds
        if (session != null && secs >= 0 && SystemClock.elapsedRealtime() - at >= secs * 1000L) lock()
        lastInteraction = SystemClock.elapsedRealtime()
    }

    private var lastInteraction = SystemClock.elapsedRealtime()

    fun onUserInteraction() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    init {
        // Inactivity lock (web: autoLockMinutes). Checked every 15 s while the app is alive.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val mins = prefs.autoLockMinutes
                if (session != null && mins > 0 && !busy && SystemClock.elapsedRealtime() - lastInteraction >= mins * 60_000L) lock()
            }
        }
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
        notice = Notice(str(R.string.bio_enabled)); noticeId++
    }

    /**
     * Protected item opened with fingerprint/face: the biometric key releases the vault key, which is
     * checked against this vault's header (same proof as the master password), then wiped.
     */
    fun repromptWithBiometric(id: String, cipher: Cipher, onOk: () -> Unit) = work {
        val s = session ?: return@work
        val o = quickKey()?.let { store.getLocal(it) } as? JsonObject ?: return@work
        val vk = try {
            cipher.doFinal(Encoding.fromBase64(o["ct"]!!.jsonPrimitive.content))
        } catch (e: Exception) {
            throw DecryptionError()
        }
        try {
            withContext(Dispatchers.Default) {
                val h = s.header
                val keys = com.damfizz.myvault.core.Keys.deriveVaultKeys(vk, h.vaultId)
                com.damfizz.myvault.core.Crypto.openJson(keys.security, h.security, com.damfizz.myvault.core.Aad.security(h.vaultId, h.rev, h.vtag))
            }
        } finally {
            Encoding.wipe(vk)
        }
        revealed += id
        onOk()
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
    var sortBy: SortBy
        get() = prefs.sortBy
        set(value) = prefs.updateSortBy(value)
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

    // ------------------------------------------------------------------ multi-select

    var selection by mutableStateOf<Set<String>>(emptySet())

    fun toggleSelected(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun moveSelected(category: String?, keepCategory: Boolean, folder: String?, keepFolder: Boolean) {
        val s = session ?: return
        val before = Items.move(s, selection, now(), category, folder, keepCategory, keepFolder)
        selection = emptySet()
        revision++
        notify(R.string.toast_moved, before)
    }

    fun favoriteSelected(value: Boolean) {
        val s = session ?: return
        Items.favorite(s, selection, value, now())
        selection = emptySet()
        revision++
    }

    // ------------------------------------------------------------------ overview

    fun corruptedCount(): Int = session?.corruptedIds?.size ?: 0

    fun backupDue(): Boolean {
        val days = prefs.backupReminderDays
        if (days <= 0 || session == null) return false
        val last = lastBackupAt() ?: return items().isNotEmpty()
        return now() - last > days * 86_400_000L
    }

    // ------------------------------------------------------------------ Secret Key (format v2)

    /** A freshly generated key the user is being shown in the Emergency Kit (not yet enabled). */
    var pendingSecretKey by mutableStateOf<String?>(null)
    /** The current key, revealed after the master password was verified. */
    var shownSecretKey by mutableStateOf<String?>(null)

    fun prepareSecretKey() {
        pendingSecretKey = com.damfizz.myvault.core.SecretKeys.generate().display
    }

    fun secretKeyQr(display: String): String {
        val raw = com.damfizz.myvault.core.SecretKeys.parse(display).raw
        return com.damfizz.myvault.core.SecretKeys.qrPayload(store.getHeader()?.vaultId.orEmpty(), raw)
    }

    fun enableSecretKey(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        val key = pendingSecretKey ?: return@work
        timedKdf { access.enableSecretKey(s, password, key) }
        pendingSecretKey = null
        revision++
        notice = Notice(str(R.string.sk_enabled)); noticeId++
        onDone()
    }

    fun disableSecretKey(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        timedKdf { access.disableSecretKey(s, password) }
        revision++
        notice = Notice(str(R.string.sk_disabled)); noticeId++
        onDone()
    }

    fun revealSecretKey(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        timedKdf { access.verifyMasterPassword(s, password) }
        shownSecretKey = access.currentSecretKey(s)?.let(com.damfizz.myvault.core.SecretKeys::format)
        onDone()
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

    // ------------------------------------------------------------------ backup, restore, snapshots, CSV

    fun lastBackupAt(): Long? = runCatching { session?.getDeviceSecrets()?.lastBackupAt }.getOrNull()

    /** Build the encrypted .myvault text; [separatePassword] null = protect with the master password. */
    fun exportBackup(masterPassword: String, separatePassword: String?, onReady: (String) -> Unit) = work {
        val s = session ?: return@work
        if (separatePassword != null && separatePassword.length < 10) { error = str(R.string.err_password_short); return@work }
        val text = timedKdf {
            access.verifyMasterPassword(s, masterPassword)
            Backup.create(s.header, s.encryptedRecords(), separatePassword ?: masterPassword, "android-" + (appVersion ?: "?"))
        }
        onReady(text)
    }

    fun backupSaved() {
        val s = session ?: return
        runCatching { s.setDeviceSecrets(s.getDeviceSecrets().copy(lastBackupAt = now())) }
        revision++
        notice = Notice(str(R.string.bk_exported)); noticeId++
    }

    private val appVersion: String? = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()

    var openedBackup by mutableStateOf<Backup.Opened?>(null)
        private set

    fun openBackupForRestore(text: String, backupPassword: String) = work {
        openedBackup = timedKdf { Backup.open(text, backupPassword) }
    }

    fun closeOpenedBackup() {
        openedBackup = null
    }

    fun mergeBackup(masterOfBackup: String, secretKeyOfBackup: String?) = work {
        val s = session ?: return@work
        val b = openedBackup ?: return@work
        val r = timedKdf { Backup.restoreMerge(s, b, masterOfBackup, secretKeyOfBackup?.takeIf { it.isNotBlank() }, now()) }
        openedBackup = null
        revision++
        notice = Notice(str(R.string.bk_merged, r.added, r.copies, r.skipped)); noticeId++
    }

    fun replaceWithBackup() {
        val b = openedBackup ?: return
        Backup.restoreReplace(store, store, b)
        openedBackup = null
        lock()
    }

    fun snapshots(): List<Backup.Snapshot> = store.listSnapshots()

    fun restoreSnapshot(id: String) {
        val snap = store.listSnapshots().find { it.id == id } ?: return
        store.createSnapshot("before-restore")
        store.replaceVault(snap.header, snap.records, listOf("sync:${snap.header.vaultId}"))
        lock()
    }

    fun deleteSnapshot(id: String) {
        store.deleteSnapshot(id)
        revision++
    }

    fun importCsv(preview: com.damfizz.myvault.core.Csv.Preview) {
        val s = session ?: return
        val n = com.damfizz.myvault.core.Csv.import(s, preview, now())
        revision++
        notice = Notice(str(R.string.csv_done, n)); noticeId++
    }

    // ------------------------------------------------------------------ Google Drive sync

    /** Google access token, in memory only (never persisted). */
    private var driveToken: String? = null
    var syncing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    /** set when the cloud holds a different vault: the user must choose which one to keep */
    var syncMismatch by mutableStateOf(false)
        private set
    var needsDriveAuth by mutableStateOf(false)
        private set

    private fun remote() = com.damfizz.myvault.data.DriveRemote { driveToken ?: throw com.damfizz.myvault.core.RemoteError("auth", "Not connected", 401) }

    fun hasDriveToken() = driveToken != null
    fun lastSyncAt(): Long? = session?.let { com.damfizz.myvault.core.SyncEngine.loadState(it).lastSyncAt }
    fun conflicts() = runCatching { session?.getDeviceSecrets()?.conflicts }.getOrNull().orEmpty()

    fun dismissConflicts() {
        val s = session ?: return
        s.setDeviceSecrets(s.getDeviceSecrets().copy(conflicts = emptyList()))
        revision++
    }

    private fun syncErrorText(e: Throwable): String = when (e) {
        is com.damfizz.myvault.core.RemoteError -> when (e.code) {
            "auth" -> str(R.string.sync_err_auth)
            "network" -> str(R.string.sync_err_network)
            "quota" -> str(R.string.sync_err_quota)
            "rate-limit" -> str(R.string.sync_err_rate)
            "config" -> str(R.string.sync_err_config)
            else -> e.message ?: "Drive error"
        }
        is com.damfizz.myvault.core.VaultMismatchError -> str(R.string.sync_err_mismatch)
        is com.damfizz.myvault.core.RemoteKeyMismatchError -> str(R.string.sync_err_key)
        is com.damfizz.myvault.core.InvalidVaultFileError -> str(R.string.sync_err_corrupt)
        is com.damfizz.myvault.core.SyncBusyError -> str(R.string.sync_err_busy)
        is com.damfizz.myvault.core.NoCloudVaultError -> str(R.string.sync_no_cloud)
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Called by the UI when Google returned an access token for the Drive appDataFolder scope. */
    fun onDriveToken(token: String, thenSync: Boolean = true) {
        driveToken = token
        driveSetupNeeded = false
        needsDriveAuth = false
        syncError = null
        viewModelScope.launch {
            val email = runCatching { withContext(Dispatchers.IO) { remote().about().first } }.getOrNull()
            prefs.updateDrive(true, email ?: prefs.driveEmail)
            if (thenSync && session != null) syncNow(quiet = false)
        }
    }

    /** Google says this Android app is not registered (missing Android OAuth client for package + SHA-1). */
    var driveSetupNeeded by mutableStateOf(false)
        private set

    fun onDriveAuthFailed(message: String) {
        val unregistered = "UNREGISTERED_ON_API_CONSOLE" in message || "DEVELOPER_ERROR" in message || message.trim().startsWith("10:")
        driveSetupNeeded = unregistered
        syncError = if (unregistered) str(R.string.gs_title) else str(R.string.sync_err_signin, message)
    }

    // ------------------------------------------------------------------ app updates (GitHub Releases)

    val installedVersion: String = com.damfizz.myvault.data.Updater.installedVersion(app)
    var update by mutableStateOf<com.damfizz.myvault.data.Updater.Release?>(null)
        private set
    /** null idle · "checking" · "latest" · "downloading" · or an error message */
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var updateProgress by mutableStateOf(0f)
        private set

    fun checkForUpdates(manual: Boolean) {
        if (updateStatus == "checking" || updateStatus == "downloading") return
        if (!manual && (!prefs.autoUpdateCheck || System.currentTimeMillis() - prefs.lastUpdateCheck < 86_400_000L)) return
        updateStatus = if (manual) "checking" else null
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { com.damfizz.myvault.data.Updater.latest() }
                prefs.lastUpdateCheck = System.currentTimeMillis()
                update = r?.takeIf { com.damfizz.myvault.core.Versions.isNewer(it.version, installedVersion) }
                updateStatus = if (manual && update == null) "latest" else null
            } catch (e: com.damfizz.myvault.data.Updater.UpdateError) {
                if (manual) updateStatus = when (e.message) {
                    "network" -> str(R.string.upd_err_network)
                    "not-found" -> str(R.string.upd_err_notfound)
                    else -> str(R.string.upd_err_other, e.message ?: "?")
                }
            } catch (e: Exception) {
                if (manual) updateStatus = str(R.string.upd_err_other, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val r = update ?: return
        val ctx = getApplication<Application>()
        updateStatus = "downloading"
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { com.damfizz.myvault.data.Updater.download(ctx, r) { p -> updateProgress = p } }
                updateStatus = null
                com.damfizz.myvault.data.Updater.install(ctx, file)
            } catch (e: com.damfizz.myvault.data.Updater.UpdateError) {
                updateStatus = when (e.message) {
                    "network" -> str(R.string.upd_err_network)
                    "checksum" -> str(R.string.upd_err_checksum)
                    else -> str(R.string.upd_err_other, e.message ?: "?")
                }
            } catch (e: Exception) {
                updateStatus = str(R.string.upd_err_other, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearUpdateStatus() {
        if (updateStatus != "downloading" && updateStatus != "checking") updateStatus = null
    }

    fun syncNow(quiet: Boolean = true) {
        val s = session ?: return
        if (syncing) return
        if (driveToken == null) { needsDriveAuth = true; return }
        syncing = true
        syncError = null
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { com.damfizz.myvault.core.SyncEngine(s, remote()).sync() }
                syncMismatch = false
                revision++
                if (r.conflicts.isNotEmpty()) { notice = Notice(str(R.string.sync_conflicts_toast, r.conflicts.size)); noticeId++ }
                else if (!quiet) { notice = Notice(if (r.pulled + r.pushed == 0) str(R.string.sync_up_to_date) else str(R.string.sync_done, r.pulled, r.pushed)); noticeId++ }
            } catch (e: Throwable) {
                if (e is com.damfizz.myvault.core.RemoteError && e.code == "auth") { driveToken = null; needsDriveAuth = true }
                if (e is com.damfizz.myvault.core.VaultMismatchError) syncMismatch = true
                syncError = syncErrorText(e)
            } finally {
                syncing = false
            }
        }
    }

    private var autoSyncJob: kotlinx.coroutines.Job? = null

    /** Debounced sync after a local change (web: auto-sync). */
    private fun scheduleAutoSync() {
        if (!prefs.driveConnected || !prefs.autoSync || driveToken == null) return
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3_000)
            syncNow(quiet = true)
        }
    }

    fun disconnectDrive() {
        driveToken = null
        syncError = null
        syncMismatch = false
        prefs.updateDrive(false, null)
    }

    fun deleteCloudCopy() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { remote().find()?.let { remote().remove(it.id) } }
                s.store.deleteLocal(com.damfizz.myvault.core.SyncEngine.stateKey(s.vaultId))
                disconnectDrive()
            } catch (e: Throwable) {
                syncError = syncErrorText(e)
            }
        }
    }

    /** Vault mismatch: keep this phone's vault and replace the cloud copy. */
    fun keepLocalVault() {
        val s = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { remote().find()?.let { remote().remove(it.id) } }
                s.store.deleteLocal(com.damfizz.myvault.core.SyncEngine.stateKey(s.vaultId))
                syncMismatch = false
                syncNow(quiet = false)
            } catch (e: Throwable) {
                syncError = syncErrorText(e)
            }
        }
    }

    /** New device, or vault mismatch → use the cloud vault. A safety snapshot is taken if a vault exists. */
    fun downloadFromDrive() = work {
        try {
            withContext(Dispatchers.IO) { com.damfizz.myvault.core.SyncEngine.downloadToDevice(store, remote(), store) }
        } catch (e: Throwable) {
            error = syncErrorText(e)
            return@work
        }
        syncMismatch = false
        lock()
        notice = Notice(str(R.string.lock_downloaded)); noticeId++
    }

    // ------------------------------------------------------------------ organize

    fun saveCollection(c: VaultCollection) {
        val s = session ?: return
        com.damfizz.myvault.core.Collections.save(s, c, now())
        revision++
    }

    fun moveCollection(c: VaultCollection, dir: Int) {
        val s = session ?: return
        com.damfizz.myvault.core.Collections.move(s, c, dir, now())
        revision++
    }

    fun deleteCollection(id: String, moveTo: String?) {
        val s = session ?: return
        s.deleteCollection(id, moveTo)
        if (filter == ItemFilter.Category(id) || filter == ItemFilter.Section(id)) filter = ItemFilter.All
        revision++
    }

    // ------------------------------------------------------------------ vault settings and security management

    fun updateVaultSettings(transform: (com.damfizz.myvault.core.VaultSettings) -> com.damfizz.myvault.core.VaultSettings) {
        session?.saveSettings(transform)
        revision++
    }

    fun security() = session?.security
    fun deviceName(): String = session?.device?.name.orEmpty()
    fun recordCount(): Int = store.allRecords().size

    fun renameDevice(name: String) {
        com.damfizz.myvault.core.Devices.rename(store, name)
        revision++
    }

    /** One-time secrets to show after a security change (recovery key, recovery codes). */
    var reveal by mutableStateOf<Pair<String, List<String>>?>(null)

    fun changeMasterPassword(current: String, next: String, confirm: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        if (next != confirm) { error = str(R.string.err_password_mismatch); return@work }
        if (!com.damfizz.myvault.core.Strength.isAcceptableMasterPassword(next)) { error = str(R.string.err_password_weak); return@work }
        timedKdf { access.changeMasterPassword(s, current, next) }
        // the biometric wrap holds the vault key, which did not change, so it stays valid
        revision++
        notice = Notice(str(R.string.settings_password_changed)); noticeId++
        onDone()
    }

    fun createRecoveryKey(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        val key = timedKdf { access.createRecoveryKey(s, password) }
        revision++
        reveal = "recoveryKey" to listOf(key)
        onDone()
    }

    fun removeRecoveryKey(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        timedKdf { access.verifyMasterPassword(s, password) }
        if (s.header.secretKey != null) { error = str(R.string.err_recovery_needed_for_sk); return@work }
        access.removeRecoveryKey(s)
        revision++
        onDone()
    }

    fun enableTotp(secret: String, code: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        val codes = withContext(Dispatchers.Default) { access.enableTotp(s, secret, code) }
        revision++
        reveal = "recoveryCodes" to codes
        onDone()
    }

    fun disableTotp(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        timedKdf { access.verifyMasterPassword(s, password) }
        access.disableTotp(s)
        access.forgetDeviceTrust(s)
        revision++
        onDone()
    }

    fun regenerateRecoveryCodes(password: String, onDone: () -> Unit) = work {
        val s = session ?: return@work
        timedKdf { access.verifyMasterPassword(s, password) }
        reveal = "recoveryCodes" to access.regenerateRecoveryCodes(s)
        revision++
        onDone()
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
