package com.damfizz.myvault.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Cloud storage used for sync, abstracted so the engine can be tested offline (web: sync/remote.ts). */
data class RemoteFileMeta(val id: String, /** monotonically increasing version reported by the provider */ val version: String)

interface RemoteStore {
    fun find(): RemoteFileMeta?
    fun getMeta(id: String): RemoteFileMeta?
    fun download(id: String): String
    fun create(content: String): RemoteFileMeta
    fun update(id: String, content: String): RemoteFileMeta
    fun remove(id: String)
}

/** code: auth | network | quota | rate-limit | not-found | server | config */
class RemoteError(val code: String, message: String, val status: Int? = null) : Exception(message)

class VaultMismatchError(val remoteVaultId: String, val remoteCreatedAt: Long) :
    Exception("The cloud vault belongs to a different vault than the one on this device.")
class RemoteKeyMismatchError :
    Exception("The cloud vault could not be authenticated with this vault's keys. It may have been modified or re-keyed.")
class SyncBusyError : Exception("The cloud vault kept changing during sync. Please try again.")
class NoCloudVaultError :
    Exception("No MyVault vault was found in this Google account yet. Create a vault on your other device and sync it first.")

@Serializable
data class SyncState(
    val vaultId: String,
    val remoteFileId: String? = null,
    val remoteVersion: String? = null,
    /** record id → vtag both sides last agreed on */
    val base: Map<String, String> = emptyMap(),
    val baseHeaderVtag: String? = null,
    val maxHeaderRev: Long = 0,
    val lastSyncAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
)

data class SyncReport(
    val created: Boolean,
    val pulled: Int,
    val pushed: Int,
    val conflicts: List<ConflictNotice>,
    val headerUpdated: Boolean,
    val skippedCorrupt: Int,
    val at: Long,
)

/**
 * Conflict-safe synchronization of the encrypted vault — port of the web's `sync/engine.ts`.
 *
 * - Runs only while the vault is unlocked; the remote only ever receives ciphertext.
 * - Never silently overwrites newer data: three-way merge per record, concurrent edits produce a
 *   "conflict copy" so both versions survive. Edits beat deletions.
 * - Optimistic concurrency: before uploading, the remote version is re-checked; if another device
 *   wrote in the meantime the whole merge is redone.
 * - A remote file of a different vault, or one that fails authentication, stops the sync.
 * - The Secret Key is never dropped silently when two devices change the header at once.
 */
class SyncEngine(private val session: VaultSession, private val remote: RemoteStore, private val clock: Clock = Clock.SYSTEM) {
    companion object {
        fun stateKey(vaultId: String) = "sync:$vaultId"

        fun loadState(session: VaultSession): SyncState {
            val raw = session.store.getLocal(stateKey(session.vaultId))
            return runCatching { VaultJson.json.decodeFromJsonElement(SyncState.serializer(), raw!!) }
                .getOrElse { SyncState(vaultId = session.vaultId, maxHeaderRev = session.header.rev) }
        }

        fun saveState(store: VaultStore, state: SyncState) =
            store.setLocal(stateKey(state.vaultId), VaultJson.json.encodeToJsonElement(SyncState.serializer(), state))

        /** New-device setup: download the encrypted vault and store it locally (web: bootstrap.ts). */
        fun downloadToDevice(store: VaultStore, remote: RemoteStore, snapshots: Backup.SnapshotStore? = null, now: Long = System.currentTimeMillis()): String {
            val meta = remote.find() ?: throw NoCloudVaultError()
            val file = SyncFormat.parse(remote.download(meta.id))
            if (store.getHeader() != null) snapshots?.createSnapshot("before-cloud-download")
            store.replaceVault(file.header, file.records)
            saveState(
                store,
                SyncState(
                    vaultId = file.header.vaultId, remoteFileId = meta.id, remoteVersion = meta.version,
                    base = Merge.baseFromRecords(file.records), baseHeaderVtag = file.header.vtag,
                    maxHeaderRev = file.header.rev, lastSyncAt = now,
                ),
            )
            return file.header.vaultId
        }
    }

    fun sync(): SyncReport {
        repeat(3) { attempt()?.let { return it } }
        throw SyncBusyError()
    }

    private fun locate(state: SyncState): RemoteFileMeta? = state.remoteFileId?.let(remote::getMeta) ?: remote.find()

    /** Compare payloads ignoring bookkeeping fields (updatedAt, device). */
    private fun contentKey(p: RecordPayload): JsonObject {
        val rest = p.json - "updatedAt" - "device"
        val item = rest["item"] as? JsonObject
        return if (p.kind == "item" && item != null) JsonObject(mapOf("kind" to rest.getValue("kind"), "item" to JsonObject(item - "updatedAt"))) else JsonObject(rest)
    }

    private class HeaderDecision(val adopt: Pair<VaultHeader, SecurityConfig>? = null, val pushLocal: Boolean, val bumpLocal: Long? = null)

    private fun mergeHeader(remoteHeader: VaultHeader, state: SyncState, notices: MutableList<ConflictNotice>): HeaderDecision {
        val local = session.header
        if (remoteHeader.vtag == local.vtag) return HeaderDecision(pushLocal = false)
        fun authenticate(): SecurityConfig = try {
            session.openSecurity(remoteHeader)
        } catch (e: Exception) {
            throw RemoteKeyMismatchError()
        }
        // Rollback protection: never accept a header older than one we have already seen.
        if (remoteHeader.rev < state.maxHeaderRev || (remoteHeader.rev < local.rev && local.vtag == state.baseHeaderVtag)) return HeaderDecision(pushLocal = true)
        if (local.vtag == state.baseHeaderVtag) return HeaderDecision(adopt = remoteHeader to authenticate(), pushLocal = false)
        if (remoteHeader.vtag == state.baseHeaderVtag) return HeaderDecision(pushLocal = true)
        // Both changed: keep the most recent change, and make sure the result outranks both.
        val security = authenticate()
        notices += ConflictNotice(
            id = Encoding.randomId(), at = clock.now(), kind = "header", title = "Security settings",
            message = "Security settings (master password, Authenticator or recovery options) were changed on two devices. The most recent change was kept — please review Settings → Security.",
        )
        val minRev = maxOf(local.rev, remoteHeader.rev) + 1
        // Never drop the Secret Key silently: if only one side has it, that side wins regardless of time.
        val remoteHasSk = remoteHeader.secretKey != null
        val localHasSk = local.secretKey != null
        val preferRemote = if (remoteHasSk != localHasSk) remoteHasSk else remoteHeader.updatedAt > local.updatedAt
        return if (preferRemote) HeaderDecision(adopt = remoteHeader to security, pushLocal = true, bumpLocal = minRev) else HeaderDecision(pushLocal = true, bumpLocal = minRev)
    }

    /** One merge attempt. Returns null if the remote changed underneath us (the caller retries). */
    private fun attempt(): SyncReport? {
        val s = session
        val state = loadState(s)
        val now = clock.now()
        val meta = locate(state)

        if (meta == null) {
            val created = remote.create(SyncFormat.serialize(s.header, s.encryptedRecords(), s.device.id, now))
            saveState(
                s.store,
                state.copy(
                    remoteFileId = created.id, remoteVersion = created.version, base = Merge.baseFromRecords(s.encryptedRecords()),
                    baseHeaderVtag = s.header.vtag, maxHeaderRev = maxOf(state.maxHeaderRev, s.header.rev),
                    lastSyncAt = now, lastAttemptAt = now, lastError = null,
                ),
            )
            return SyncReport(true, 0, s.encryptedRecords().size, emptyList(), false, 0, now)
        }

        val file = SyncFormat.parse(remote.download(meta.id))
        if (file.header.vaultId != s.vaultId) throw VaultMismatchError(file.header.vaultId, file.header.createdAt)

        val notices = mutableListOf<ConflictNotice>()
        val header = mergeHeader(file.header, state, notices)

        // ------------------------------------------------------------ records
        val snapshot = s.encryptedRecords()
        val snapshotTags = snapshot.associate { it.id to it.vtag }
        val result = Merge.mergeRecords(snapshot, file.records, state.base)
        val merged = result.merged
        var remoteChanged = result.remoteChanged || header.pushLocal
        var skippedCorrupt = 0

        // Verify remote records we intend to pull (authenticated decryption).
        val pullOk = mutableListOf<EncryptedRecord>()
        for (r in result.pull) {
            try {
                s.decryptRecord(r)
                pullOk += r
            } catch (e: Exception) {
                skippedCorrupt++
                val local = snapshot.find { it.id == r.id }
                // A corrupt remote-only record is left in the file untouched (never destroyed), just not imported.
                if (local != null) {
                    merged[r.id] = local
                    remoteChanged = true
                }
            }
        }

        // Re-seal local winners that must outrank the remote revision.
        val rewrites = mutableListOf<Triple<String, Long, RecordPayload>>()
        for (b in result.bump) rewrites += Triple(b.id, b.minRev, s.decryptRecord(merged.getValue(b.id)))

        // Resolve conflicts without losing data.
        for (c in result.conflicts) {
            val minRev = maxOf(c.local.rev, c.remote.rev) + 1
            val pl = runCatching { s.decryptRecord(c.local) }.getOrNull()
            val pr = runCatching { s.decryptRecord(c.remote) }.getOrNull()
            if (pr == null) skippedCorrupt++
            if (pl == null && pr == null) continue
            if (pr == null) { rewrites += Triple(c.id, minRev, pl!!); continue }
            if (pl == null) { rewrites += Triple(c.id, minRev, pr); continue }
            if (contentKey(pl) == contentKey(pr)) {
                rewrites += Triple(c.id, minRev, if (pl.updatedAt >= pr.updatedAt) pl else pr)
                continue
            }
            // Edit beats delete
            if (pl.kind == "tombstone" || pr.kind == "tombstone") {
                val kept = if (pl.kind == "tombstone") pr else pl
                rewrites += Triple(c.id, minRev, kept)
                kept.item()?.let {
                    notices += ConflictNotice(
                        id = Encoding.randomId(), at = now, kind = "item", title = it.title, keptId = c.id,
                        message = "This item was deleted on one device but edited on another. The edited version was kept.",
                    )
                }
                continue
            }
            val (winner, loser) = if (pl.updatedAt >= pr.updatedAt) pl to pr else pr to pl
            rewrites += Triple(c.id, minRev, winner)
            val wi = winner.item()
            val li = loser.item()
            val wc = winner.collection()
            val lc = loser.collection()
            if (wi != null && li != null) {
                val copyId = Encoding.randomId()
                val copy = li.copy(id = copyId, title = "${li.title.ifEmpty { "Untitled" }} (conflict copy)", updatedAt = now)
                rewrites += Triple(copyId, 1L, RecordPayload.item(copy, now, loser.device))
                notices += ConflictNotice(
                    id = Encoding.randomId(), at = now, kind = "item", title = wi.title, keptId = c.id, copyId = copyId,
                    message = "Edited on two devices at the same time. The newest version was kept and the version from “${loser.device}” was saved as a conflict copy.",
                )
            } else if (wc != null && lc != null && wc.name != lc.name) {
                notices += ConflictNotice(
                    id = Encoding.randomId(), at = now, kind = "collection", title = wc.name, keptId = c.id,
                    message = "“${lc.name}” was also renamed on another device. The most recent change was kept.",
                )
            } else if (winner.kind == "settings") {
                notices += ConflictNotice(
                    id = Encoding.randomId(), at = now, kind = "settings", title = "Vault settings",
                    message = "Vault settings were changed on two devices. The most recent change was kept.",
                )
            }
        }

        // Abort cleanly if the remote changed since we downloaded it.
        if (remoteChanged || rewrites.isNotEmpty()) {
            val current = remote.getMeta(meta.id)
            if (current == null || current.version != meta.version) return null
        }

        // ------------------------------------------------------------ apply locally
        // Only overwrite local records the user has not edited while we were merging.
        fun stillSame(id: String): Boolean {
            val cur = s.encryptedRecords().find { it.id == id }
            return cur == null || cur.vtag == snapshotTags[id]
        }
        val toApply = pullOk.filter { stillSame(it.id) }
        if (toApply.isNotEmpty()) s.applyEncryptedRecords(toApply)
        val safeRewrites = rewrites.filter { it.first !in snapshotTags || stillSame(it.first) }
        if (safeRewrites.isNotEmpty()) {
            for (w in s.writeRaw(safeRewrites)) merged[w.id] = w
            remoteChanged = true
        }
        header.adopt?.let { (h, sec) -> s.adoptHeader(h, sec) }
        if (header.bumpLocal != null) {
            s.commitHeader(header.bumpLocal) { }
            remoteChanged = true
        }

        // ------------------------------------------------------------ upload
        var finalMeta = meta
        if (remoteChanged) finalMeta = remote.update(meta.id, SyncFormat.serialize(s.header, merged.values.toList(), s.device.id, now))

        if (notices.isNotEmpty()) {
            val secrets = s.getDeviceSecrets()
            s.setDeviceSecrets(secrets.copy(conflicts = (notices + secrets.conflicts).take(50)))
        }

        saveState(
            s.store,
            state.copy(
                remoteFileId = finalMeta.id, remoteVersion = finalMeta.version, base = Merge.baseFromRecords(merged.values),
                baseHeaderVtag = s.header.vtag, maxHeaderRev = maxOf(state.maxHeaderRev, s.header.rev),
                lastSyncAt = now, lastAttemptAt = now, lastError = null,
            ),
        )
        return SyncReport(false, toApply.size, if (remoteChanged) result.pushed + safeRewrites.size else 0, notices, header.adopt != null, skippedCorrupt, now)
    }
}
