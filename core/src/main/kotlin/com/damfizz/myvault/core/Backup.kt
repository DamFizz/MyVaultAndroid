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
}
