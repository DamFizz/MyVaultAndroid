package com.damfizz.myvault.data

import com.damfizz.myvault.core.EncryptedRecord
import com.damfizz.myvault.core.VaultHeader
import com.damfizz.myvault.core.VaultJson
import com.damfizz.myvault.core.VaultStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * [VaultStore] backed by two JSON files in app-private storage:
 *  - vault.json: header + encrypted records (only ciphertext, same shape as the sync file)
 *  - local.json: device-only state (throttle, TOTP replay step, trust token, sealed Secret Key, quick unlock)
 * Every write goes to a temp file and is renamed over the old one, so a crash never leaves half a vault.
 */
class FileVaultStore(private val dir: File) : VaultStore, com.damfizz.myvault.core.Backup.SnapshotStore {
    private val vaultFile = File(dir, "vault.json")
    private val localFile = File(dir, "local.json")

    private var header: VaultHeader? = null
    private val records = LinkedHashMap<String, EncryptedRecord>()
    private val local = LinkedHashMap<String, JsonElement>()

    init {
        dir.mkdirs()
        if (vaultFile.exists()) {
            val o = VaultJson.json.parseToJsonElement(vaultFile.readText()).jsonObject
            header = (o["header"] as? JsonObject)?.let(VaultHeader::fromJson)
            o["records"]?.let { el ->
                VaultJson.json.decodeFromJsonElement(ListSerializer(EncryptedRecord.serializer()), el).forEach { records[it.id] = it }
            }
        }
        if (localFile.exists()) local.putAll(VaultJson.json.parseToJsonElement(localFile.readText()).jsonObject)
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save ${target.name}" }
        }
    }

    private fun saveVault() {
        val o = buildJsonObject {
            put("header", header?.toJson() ?: JsonNull)
            put("records", VaultJson.json.encodeToJsonElement(ListSerializer(EncryptedRecord.serializer()), records.values.toList()))
        }
        atomicWrite(vaultFile, VaultJson.json.encodeToString(JsonObject.serializer(), o))
    }

    private fun saveLocal() = atomicWrite(localFile, VaultJson.json.encodeToString(JsonObject.serializer(), JsonObject(local)))

    @Synchronized override fun getHeader() = header
    @Synchronized override fun putHeader(header: VaultHeader) { this.header = header; saveVault() }
    @Synchronized override fun allRecords() = records.values.toList()
    @Synchronized override fun putRecords(records: List<EncryptedRecord>) {
        if (records.isEmpty()) return
        for (r in records) this.records[r.id] = r
        saveVault()
    }
    @Synchronized override fun deleteRecords(ids: List<String>) {
        if (ids.isEmpty()) return
        for (id in ids) records.remove(id)
        saveVault()
    }
    @Synchronized override fun getLocal(key: String) = local[key]
    @Synchronized override fun setLocal(key: String, value: JsonElement) { local[key] = value; saveLocal() }
    @Synchronized override fun deleteLocal(key: String) { if (local.remove(key) != null) saveLocal() }
    @Synchronized override fun replaceVault(header: VaultHeader, records: List<EncryptedRecord>, clearLocalKeys: List<String>) {
        this.header = header
        this.records.clear()
        for (r in records) this.records[r.id] = r
        saveVault()
        if (clearLocalKeys.isNotEmpty()) { clearLocalKeys.forEach { local.remove(it) }; saveLocal() }
    }

    // ------------------------------------------------------------------ safety snapshots (ciphertext only)

    private val snapshotDir get() = File(dir, "snapshots").also { it.mkdirs() }

    @Synchronized override fun createSnapshot(reason: String, keep: Int): com.damfizz.myvault.core.Backup.Snapshot? {
        val h = header ?: return null
        val id = com.damfizz.myvault.core.Encoding.randomId()
        val now = System.currentTimeMillis()
        val o = buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("createdAt", kotlinx.serialization.json.JsonPrimitive(now))
            put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
            put("header", h.toJson())
            put("records", VaultJson.json.encodeToJsonElement(ListSerializer(EncryptedRecord.serializer()), records.values.toList()))
        }
        atomicWrite(File(snapshotDir, "$now-$id.json"), VaultJson.json.encodeToString(JsonObject.serializer(), o))
        snapshotDir.listFiles().orEmpty().sortedBy { it.name }.dropLast(keep).forEach { it.delete() }
        return com.damfizz.myvault.core.Backup.Snapshot(id, now, reason, h, records.values.toList())
    }

    @Synchronized override fun listSnapshots(): List<com.damfizz.myvault.core.Backup.Snapshot> =
        snapshotDir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedByDescending { it.name }.mapNotNull { f ->
            runCatching {
                val o = VaultJson.json.parseToJsonElement(f.readText()).jsonObject
                com.damfizz.myvault.core.Backup.Snapshot(
                    (o["id"] as kotlinx.serialization.json.JsonPrimitive).content,
                    (o["createdAt"] as kotlinx.serialization.json.JsonPrimitive).content.toLong(),
                    (o["reason"] as kotlinx.serialization.json.JsonPrimitive).content,
                    VaultHeader.fromJson(o["header"] as JsonObject),
                    VaultJson.json.decodeFromJsonElement(ListSerializer(EncryptedRecord.serializer()), o["records"]!!),
                )
            }.getOrNull()
        }

    @Synchronized override fun deleteSnapshot(id: String) {
        snapshotDir.listFiles().orEmpty().filter { it.name.endsWith("-$id.json") }.forEach { it.delete() }
    }

    /** Remove the vault and every device-local secret from this phone. */
    @Synchronized fun wipe() {
        header = null
        records.clear()
        local.clear()
        vaultFile.delete()
        localFile.delete()
        snapshotDir.listFiles().orEmpty().forEach { it.delete() }
    }
}
