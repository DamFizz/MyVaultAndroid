package com.damfizz.myvault.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Encrypted backups (.myvault files) — port of `src/vault/backup.ts`.
 * Outer layer: Argon2id(backup password) → HKDF("myvault/v1/backup") → AES-GCM over the payload.
 * Inner layer: the vault exactly as stored (header + encrypted records).
 */
object Backup {
    const val BACKUP_FORMAT = "myvault-backup"
    const val BACKUP_FORMAT_VERSION = 1

    class CorruptBackupError(message: String = "This file is not a valid MyVault backup or it is damaged.") : Exception(message)
    class BackupPasswordError : Exception("Wrong backup password, or the backup file was modified/corrupted.")

    data class Inspected(val version: Int, val createdAt: Long, val appVersion: String, val kdf: KdfParams, val payload: Sealed)
    data class Opened(val createdAt: Long, val appVersion: String, val version: Int, val header: VaultHeader, val records: List<EncryptedRecord>)

    private fun backupKey(password: String, kdf: KdfParams): AesKey {
        val stretched = Crypto.deriveFromPassword(password, kdf)
        try {
            return Crypto.deriveAesKey(stretched, "myvault/v1/backup", "")
        } finally {
            Encoding.wipe(stretched)
        }
    }

    fun create(header: VaultHeader, records: List<EncryptedRecord>, backupPassword: String, appVersion: String, createdAt: Long = System.currentTimeMillis()): String {
        val kdf = Crypto.newKdfParams()
        val key = backupKey(backupPassword, kdf)
        val payload = buildJsonObject {
            put("vaultId", header.vaultId)
            put("header", header.toJson())
            put("records", VaultJson.json.encodeToJsonElement(ListSerializer(EncryptedRecord.serializer()), records))
        }
        val file = buildJsonObject {
            put("format", BACKUP_FORMAT)
            put("version", BACKUP_FORMAT_VERSION)
            put("createdAt", createdAt)
            put("appVersion", appVersion)
            put("kdf", VaultJson.json.encodeToJsonElement(KdfParams.serializer(), kdf))
            put("payload", VaultJson.json.encodeToJsonElement(Sealed.serializer(), Crypto.sealJson(key, payload, Aad.backup(createdAt))))
        }
        return VaultJson.json.encodeToString(JsonObject.serializer(), file)
    }

    fun inspect(text: String): Inspected {
        val data = try {
            VaultJson.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw CorruptBackupError()
        if ((data["format"] as? JsonPrimitive)?.contentOrNull != BACKUP_FORMAT) throw CorruptBackupError("This file is not a MyVault backup.")
        val version = (data["version"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: throw CorruptBackupError()
        if (version > BACKUP_FORMAT_VERSION) throw CorruptBackupError("This backup was made by a newer version of MyVault. Please update the app.")
        return try {
            Inspected(
                version.toInt(),
                (data["createdAt"] as JsonPrimitive).content.toDouble().toLong(),
                (data["appVersion"] as? JsonPrimitive)?.contentOrNull ?: "",
                VaultJson.json.decodeFromJsonElement(KdfParams.serializer(), data["kdf"]!!),
                VaultJson.json.decodeFromJsonElement(Sealed.serializer(), data["payload"]!!),
            )
        } catch (e: Exception) {
            throw CorruptBackupError()
        }
    }

    fun open(text: String, backupPassword: String): Opened {
        val file = inspect(text)
        val key = try {
            backupKey(backupPassword, file.kdf)
        } catch (e: IllegalArgumentException) {
            throw CorruptBackupError("Backup key-derivation parameters are invalid.")
        }
        val payload = try {
            Crypto.openJson(key, file.payload, Aad.backup(file.createdAt)) as JsonObject
        } catch (e: DecryptionError) {
            throw BackupPasswordError()
        } catch (e: Exception) {
            throw CorruptBackupError()
        }
        try {
            val header = VaultHeader.fromJson(payload["header"] as JsonObject)
            val records = SyncFormat.validateRecords(payload["records"])
            return Opened(file.createdAt, file.appVersion, file.version, header, records)
        } catch (e: InvalidVaultFileError) {
            throw CorruptBackupError(e.message ?: "")
        } catch (e: Exception) {
            throw CorruptBackupError()
        }
    }

    // ------------------------------------------------------------------ restore

    /** Encrypted safety copy taken before a destructive operation (web: db.ts Snapshot). */
    class Snapshot(val id: String, val createdAt: Long, val reason: String, val header: VaultHeader, val records: List<EncryptedRecord>)

    interface SnapshotStore {
        fun createSnapshot(reason: String, keep: Int = 5): Snapshot?
        fun listSnapshots(): List<Snapshot>
        fun deleteSnapshot(id: String)
    }

    class MergeResult(val added: Int, val copies: Int, val skipped: Int, val collectionsAdded: Int)

    /**
     * Decrypt the records of a backup (possibly of another vault) with ITS master password, and its
     * Secret Key when it has one. Records that fail authentication are skipped.
     */
    fun decryptForImport(backup: Opened, password: String, secretKey: String? = null): List<RecordPayload> {
        val h = backup.header
        val kek = if (h.secretKey != null) {
            val raw = SecretKeys.parse(secretKey ?: throw SecretKeyRequiredError(h.vaultId)).raw
            if (SecretKeys.id(raw, h.vaultId) != h.secretKey.id) throw WrongSecretKeyError()
            SecretKeys.passwordSecretKek(password, h.kdf, h.vaultId, raw)
        } else {
            Keys.passwordKek(password, h.kdf, h.vaultId)
        }
        val vk = try {
            Keys.unwrapVaultKey(kek, h.passwordWrap, h.vaultId, if (h.secretKey != null) WrapPurpose.PASSWORD_SK else WrapPurpose.PASSWORD)
        } catch (e: DecryptionError) {
            throw WrongPasswordError("Incorrect master password for this backup")
        }
        val keys = Keys.deriveVaultKeys(vk, h.vaultId)
        Encoding.wipe(vk)
        return backup.records.mapNotNull { r ->
            try {
                RecordPayload(Crypto.openJson(keys.records, r.sealed, Aad.record(h.vaultId, r.id, r.rev, r.vtag)) as JsonObject)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Merge a backup into the unlocked vault: missing items are added, identical ones skipped, and
     * differing versions are added as "(restored copy)" so nothing is ever overwritten.
     */
    fun restoreMerge(session: VaultSession, backup: Opened, backupMasterPassword: String, backupSecretKey: String?, now: Long): MergeResult {
        val payloads = decryptForImport(backup, backupMasterPassword, backupSecretKey)
        val existingItems = session.items().associateBy { it.id }
        val existingCollections = session.collections().associateBy { it.id }
        val byName = session.collections().associate { "${it.kind}:${it.name.lowercase()}" to it.id }
        val idMap = HashMap<String, String>()
        val saveCollections = mutableListOf<VaultCollection>()
        val saveItems = mutableListOf<VaultItem>()
        var added = 0
        var copies = 0
        var skipped = 0

        for (p in payloads) {
            val c = p.collection() ?: continue
            if (c.id in existingCollections) continue
            val same = byName["${c.kind}:${c.name.lowercase()}"]
            if (same != null) {
                idMap[c.id] = same
                continue
            }
            saveCollections += c.copy(updatedAt = now)
        }
        fun remap(id: String?) = id?.let { idMap[it] ?: it }
        fun comparable(i: VaultItem) = JsonObject(i.toJson() - "updatedAt")

        for (p in payloads) {
            val src = p.item() ?: continue
            val item = src.copy(categoryId = remap(src.categoryId), sectionId = remap(src.sectionId))
            val existing = existingItems[item.id]
            when {
                existing == null -> {
                    saveItems += item.copy(updatedAt = now)
                    added++
                }
                comparable(existing) == comparable(item) -> skipped++
                else -> {
                    saveItems += item.copy(id = Encoding.randomId(), title = "${item.title.ifEmpty { "Untitled" }} (restored copy)", updatedAt = now)
                    copies++
                }
            }
        }
        if (saveCollections.isNotEmpty()) session.saveCollections(saveCollections)
        if (saveItems.isNotEmpty()) session.saveItems(saveItems)
        return MergeResult(added, copies, skipped, saveCollections.size)
    }

    /**
     * Replace the local vault with the backup after taking a safety snapshot. The app must then lock;
     * the user unlocks with the backup's master password.
     */
    fun restoreReplace(store: VaultStore, snapshots: SnapshotStore?, backup: Opened) {
        val current = store.getHeader()
        if (current != null) snapshots?.createSnapshot("before-restore")
        val clear = when {
            current == null -> emptyList()
            // Same vault: keep device secrets but reset sync bookkeeping so the next sync does a full merge.
            current.vaultId == backup.header.vaultId -> listOf("sync:${current.vaultId}")
            else -> listOf("sync:${current.vaultId}", "secrets:${current.vaultId}", "quickUnlock:${current.vaultId}")
        }
        store.replaceVault(backup.header, backup.records, clear)
    }
}
