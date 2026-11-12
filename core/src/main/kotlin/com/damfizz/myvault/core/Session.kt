package com.damfizz.myvault.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Device-only secrets, encrypted with the local subkey and never synced (web: DeviceSecrets). */
@Serializable
data class ConflictNotice(
    val id: String,
    val at: Long,
    val kind: String,
    val title: String,
    val keptId: String? = null,
    val copyId: String? = null,
    val message: String,
)

@Serializable
data class GoogleAccount(
    val email: String? = null,
    val name: String? = null,
    val accessToken: String? = null,
    val tokenExpiresAt: Long? = null,
    val clientId: String? = null,
)

@Serializable
data class DeviceSecrets(
    val usage: Map<String, Long> = emptyMap(),
    val google: GoogleAccount? = null,
    val conflicts: List<ConflictNotice> = emptyList(),
    val lastBackupAt: Long? = null,
)

class DecryptedRecord(val record: EncryptedRecord, val payload: RecordPayload)

/**
 * An unlocked vault (port of `src/vault/session.ts`). Holds derived subkeys and decrypted records
 * in memory only; [lock] drops every reference.
 */
class VaultSession private constructor(
    val store: VaultStore,
    keys: VaultKeys,
    header: VaultHeader,
    security: SecurityConfig,
    val device: DeviceInfo,
    private val clock: Clock,
) {
    private var keys: VaultKeys? = keys
    private var decrypted = LinkedHashMap<String, DecryptedRecord>()
    private var deviceSecretsCache: DeviceSecrets? = null
    val corruptedIds = mutableListOf<String>()

    var header: VaultHeader = header
        private set
    var security: SecurityConfig = security
        private set

    val vaultId: String get() = header.vaultId
    val isLocked: Boolean get() = keys == null

    private fun requireKeys(): VaultKeys = keys ?: throw IllegalStateException("Vault is locked")

    fun getKeys(): VaultKeys = requireKeys()

    /** Re-read and decrypt every record from the store. */
    @Synchronized
    fun reload() {
        val next = LinkedHashMap<String, DecryptedRecord>()
        corruptedIds.clear()
        for (record in store.allRecords()) {
            try {
                next[record.id] = DecryptedRecord(record, decryptRecord(record))
            } catch (e: Exception) {
                corruptedIds += record.id
            }
        }
        decrypted = next
        val stored = store.getHeader()
        if (stored != null && stored.vtag != header.vtag && stored.vaultId == header.vaultId) {
            try {
                security = openSecurity(stored)
                header = stored
            } catch (_: Exception) {
                // keep the in-memory header if the stored one cannot be authenticated
            }
        }
    }

    @Synchronized
    fun decryptRecord(record: EncryptedRecord): RecordPayload {
        val k = requireKeys()
        val json = Crypto.openJson(k.records, record.sealed, Aad.record(k.vaultId, record.id, record.rev, record.vtag))
        return RecordPayload(json as? JsonObject ?: throw DecryptionError("Record payload is not an object"))
    }

    @Synchronized
    fun sealRecord(id: String, rev: Long, payload: RecordPayload): EncryptedRecord {
        val k = requireKeys()
        val vtag = Encoding.randomId()
        val s = Crypto.sealJson(k.records, payload.json, Aad.record(k.vaultId, id, rev, vtag))
        return EncryptedRecord(id, rev, vtag, s.iv, s.ct)
    }

    @Synchronized
    fun openSecurity(h: VaultHeader): SecurityConfig {
        val k = requireKeys()
        val json = Crypto.openJson(k.security, h.security, Aad.security(h.vaultId, h.rev, h.vtag))
        return SecurityConfig.fromJson(json as JsonObject)
    }

    // ------------------------------------------------------------------ reads

    @Synchronized
    fun allDecrypted(): List<DecryptedRecord> = decrypted.values.toList()
    @Synchronized
    fun encryptedRecords(): List<EncryptedRecord> = decrypted.values.map { it.record }
    @Synchronized
    fun items(): List<VaultItem> = decrypted.values.mapNotNull { it.payload.item() }
    @Synchronized
    fun collections(): List<VaultCollection> =
        decrypted.values.mapNotNull { it.payload.collection() }.sortedWith(compareBy<VaultCollection> { it.order }.thenBy { it.name })

    @Synchronized
    fun settings(): VaultSettings = decrypted[SETTINGS_RECORD_ID]?.payload?.settings() ?: VaultSettings.DEFAULT
    @Synchronized
    fun getItem(id: String): VaultItem? = decrypted[id]?.payload?.item()

    // ------------------------------------------------------------------ writes

    @Synchronized
    private fun writePayloads(entries: List<Pair<String, RecordPayload>>) {
        val sealed = entries.map { (id, payload) ->
            val prev = decrypted[id]
            DecryptedRecord(sealRecord(id, (prev?.record?.rev ?: 0) + 1, payload), payload)
        }
        store.putRecords(sealed.map { it.record })
        for (s in sealed) decrypted[s.record.id] = s
    }

    @Synchronized
    fun saveItems(items: List<VaultItem>) {
        val now = clock.now()
        writePayloads(items.map { it.id to RecordPayload.item(it, now, device.name) })
    }

    fun saveItem(item: VaultItem) = saveItems(listOf(item))

    /** Permanently delete: replace with an encrypted tombstone so the deletion syncs. */
    @Synchronized
    fun purgeItems(ids: List<String>) {
        val now = clock.now()
        writePayloads(ids.map { it to RecordPayload.tombstone(now, device.name) })
        val secrets = getDeviceSecrets()
        if (ids.any { it in secrets.usage }) setDeviceSecrets(secrets.copy(usage = secrets.usage - ids.toSet()))
    }

    @Synchronized
    fun saveCollections(collections: List<VaultCollection>) {
        val now = clock.now()
        writePayloads(collections.map { it.id to RecordPayload.collection(it, now, device.name) })
    }

    /** Delete a category/section without deleting its items (moved to [moveTo] or unassigned). */
    @Synchronized
    fun deleteCollection(id: String, moveTo: String?) {
        val target = collections().find { it.id == id } ?: return
        val now = clock.now()
        val isCategory = target.kind == "category"
        val affected = items().filter { (if (isCategory) it.categoryId else it.sectionId) == id }.map {
            if (isCategory) it.copy(categoryId = moveTo, updatedAt = now) else it.copy(sectionId = moveTo, updatedAt = now)
        }
        val children = collections().filter { it.parentId == id }.map { it.copy(parentId = target.parentId, updatedAt = now) }
        val entries = mutableListOf<Pair<String, RecordPayload>>()
        affected.forEach { entries += it.id to RecordPayload.item(it, now, device.name) }
        children.forEach { entries += it.id to RecordPayload.collection(it, now, device.name) }
        entries += id to RecordPayload.tombstone(now, device.name)
        val s = settings()
        if (s.defaultCategoryId == id) {
            entries += SETTINGS_RECORD_ID to RecordPayload.settings(s.copy(defaultCategoryId = null, updatedAt = now), now, device.name)
        }
        writePayloads(entries)
    }

    @Synchronized
    fun saveSettings(transform: (VaultSettings) -> VaultSettings) {
        val now = clock.now()
        val next = transform(settings()).copy(updatedAt = now)
        writePayloads(listOf(SETTINGS_RECORD_ID to RecordPayload.settings(next, now, device.name)))
    }

    /** Sync/restore: store already-encrypted records after verifying each one decrypts. */
    @Synchronized
    fun applyEncryptedRecords(records: List<EncryptedRecord>, deleteIds: List<String> = emptyList()) {
        val verified = records.map { DecryptedRecord(it, decryptRecord(it)) }
        store.putRecords(verified.map { it.record })
        store.deleteRecords(deleteIds)
        for (v in verified) decrypted[v.record.id] = v
        for (id in deleteIds) decrypted.remove(id)
    }

    /** Seal payloads at explicit revisions (conflict copies, revision bumps). */
    @Synchronized
    fun writeRaw(entries: List<Triple<String, Long, RecordPayload>>): List<EncryptedRecord> {
        val out = entries.map { (id, rev, payload) -> sealRecord(id, rev, payload) }
        store.putRecords(out)
        out.forEachIndexed { i, r -> decrypted[r.id] = DecryptedRecord(r, entries[i].third) }
        return out
    }

    // ------------------------------------------------------------------ header / security

    class HeaderDraft(var header: VaultHeader, var security: SecurityConfig)

    /** Commit a header change: bumps rev, new vtag, re-seals the security config bound to them. */
    @Synchronized
    fun commitHeader(minRev: Long = 0, mutate: (HeaderDraft) -> Unit) {
        val k = requireKeys()
        val draft = HeaderDraft(header, security)
        mutate(draft)
        val rev = maxOf(header.rev + 1, minRev)
        val vtag = Encoding.randomId()
        val sealedSec = Crypto.sealJson(k.security, draft.security.toJson(), Aad.security(header.vaultId, rev, vtag))
        val next = draft.header.copy(rev = rev, vtag = vtag, updatedAt = clock.now(), security = sealedSec)
        store.putHeader(next)
        header = next
        security = draft.security
    }

    /** Adopt a header received from sync (already authenticated by the caller). */
    @Synchronized
    fun adoptHeader(h: VaultHeader, sec: SecurityConfig) {
        store.putHeader(h)
        header = h
        security = sec
    }

    // ------------------------------------------------------------------ device-local secrets

    private fun secretsKey() = "secrets:${requireKeys().vaultId}"

    @Synchronized
    fun getDeviceSecrets(): DeviceSecrets {
        deviceSecretsCache?.let { return it }
        val k = requireKeys()
        var value = DeviceSecrets()
        val sealed = store.getLocal(secretsKey())
        if (sealed != null) {
            try {
                val s = VaultJson.json.decodeFromJsonElement(Sealed.serializer(), sealed)
                val json = Crypto.openJson(k.local, s, Aad.local(k.vaultId, "secrets"))
                value = VaultJson.json.decodeFromJsonElement(DeviceSecrets.serializer(), json)
            } catch (_: Exception) {
                // unreadable device secrets are reset (they are non-essential)
            }
        }
        deviceSecretsCache = value
        return value
    }

    @Synchronized
    fun setDeviceSecrets(value: DeviceSecrets) {
        val k = requireKeys()
        val json: JsonElement = VaultJson.json.encodeToJsonElement(DeviceSecrets.serializer(), value)
        val sealed = Crypto.sealJson(k.local, json, Aad.local(k.vaultId, "secrets"))
        store.setLocal(secretsKey(), VaultJson.json.encodeToJsonElement(Sealed.serializer(), sealed))
        deviceSecretsCache = value
    }

    @Synchronized
    fun markUsed(id: String) {
        val s = getDeviceSecrets()
        setDeviceSecrets(s.copy(usage = s.usage + (id to clock.now())))
    }

    @Synchronized
    fun lock() {
        keys = null
        decrypted = LinkedHashMap()
        deviceSecretsCache = null
    }

    companion object {
        fun open(store: VaultStore, keys: VaultKeys, header: VaultHeader, security: SecurityConfig, device: DeviceInfo, clock: Clock = Clock.SYSTEM) =
            VaultSession(store, keys, header, security, device, clock).also { it.reload() }
    }
}
