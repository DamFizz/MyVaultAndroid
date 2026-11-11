package com.damfizz.myvault.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Port of `src/vault/types.ts` and `src/sync/format.ts`. */

object VaultJson {
    /** Wire format: tolerant of unknown keys, writes explicit nulls (like the web for header/security). */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }

    /** For item/collection/settings payloads: absent optional fields stay absent (web uses undefined). */
    val sparse = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Encode a typed value on top of the JSON object it was decoded from, so that fields this app
     * does not know about (written by a newer web version) and explicit `null`s survive an edit.
     */
    fun <T> encodePreserving(serializer: KSerializer<T>, value: T, original: JsonObject?): JsonObject {
        val encoded = sparse.encodeToJsonElement(serializer, value).jsonObject
        if (original == null) return encoded
        val known = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }.toSet()
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in original) {
            when {
                k !in known -> out[k] = v
                k in encoded -> out[k] = encoded.getValue(k)
                v is JsonNull -> out[k] = JsonNull // was explicitly null and still is
                else -> Unit // cleared by this edit → omitted, like `undefined` on the web
            }
        }
        for ((k, v) in encoded) if (k !in out) out[k] = v
        return JsonObject(out)
    }
}

const val VAULT_FORMAT = "myvault-vault"
const val VAULT_FORMAT_VERSION = 1
/** Written once a Secret Key has been enabled. */
const val VAULT_FORMAT_VERSION_SECRET_KEY = 2
/** Highest header version this app reads; newer files are refused, never overwritten. */
const val VAULT_FORMAT_MAX_VERSION = 2

/** Drop `key: null` unless the original JSON had it (the web leaves these keys out until used). */
internal fun JsonObject.omitNullUnlessIn(raw: JsonObject?, vararg keys: String): JsonObject {
    val drop = keys.filter { this[it] is JsonNull && raw?.containsKey(it) != true }
    return if (drop.isEmpty()) this else JsonObject(this - drop.toSet())
}
const val SETTINGS_RECORD_ID = "vault-settings"

@Serializable
data class CustomField(val id: String, val label: String = "", val value: String = "", val hidden: Boolean = false)

@Serializable
data class PasswordHistoryEntry(val password: String, val changedAt: Long)

object ItemType {
    const val LOGIN = "login"
    const val NOTE = "note"
    const val WIFI = "wifi"
    const val RECOVERY_CODES = "recoveryCodes"
    const val LICENSE = "license"
    const val CUSTOM = "custom"
}

@Serializable
data class VaultItem(
    val id: String,
    val type: String = ItemType.LOGIN,
    val title: String = "",
    val url: String? = null,
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
    val notes: String? = null,
    val categoryId: String? = null,
    val sectionId: String? = null,
    val tags: List<String> = emptyList(),
    val recoveryEmail: String? = null,
    val recoveryInfo: String? = null,
    val importantNotes: String? = null,
    val customFields: List<CustomField> = emptyList(),
    val ssid: String? = null,
    val wifiSecurity: String? = null,
    val product: String? = null,
    val licenseKey: String? = null,
    val licensee: String? = null,
    val version: String? = null,
    val purchaseDate: String? = null,
    val service: String? = null,
    val codes: String? = null,
    val favorite: Boolean = false,
    val reprompt: Boolean = false,
    val archivedAt: Long? = null,
    val trashedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val passwordChangedAt: Long? = null,
    val passwordHistory: List<PasswordHistoryEntry>? = null,
    /** JSON this item was decoded from (keeps unknown fields and explicit nulls on re-save). */
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject = VaultJson.encodePreserving(serializer(), this, raw)

    companion object {
        fun fromJson(obj: JsonObject): VaultItem = VaultJson.sparse.decodeFromJsonElement(serializer(), obj).copy(raw = obj)

        /** Same shape as the web's emptyItem(): these four keys are explicit nulls. */
        fun empty(type: String, id: String, now: Long): VaultItem {
            val raw = buildJsonObject {
                put("categoryId", JsonNull); put("sectionId", JsonNull)
                put("archivedAt", JsonNull); put("trashedAt", JsonNull)
            }
            return VaultItem(id = id, type = type, createdAt = now, updatedAt = now, raw = raw)
        }
    }
}

@Serializable
data class VaultCollection(
    val id: String,
    /** "category" | "section" */
    val kind: String,
    val name: String,
    val icon: String = "folder",
    val color: String = "#64748b",
    val parentId: String? = null,
    val pinned: Boolean = false,
    val order: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject = VaultJson.encodePreserving(serializer(), this, raw)

    companion object {
        fun fromJson(obj: JsonObject): VaultCollection = VaultJson.sparse.decodeFromJsonElement(serializer(), obj).copy(raw = obj)
    }
}

@Serializable
data class VaultSettings(
    val passwordHistoryLimit: Int = 5,
    val defaultCategoryId: String? = null,
    val oldPasswordDays: Int = 365,
    val trashRetentionDays: Int = 30,
    val repromptByDefault: Boolean = true,
    val updatedAt: Long = 0,
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject {
        // The web writes defaultCategoryId: null explicitly.
        val base = raw ?: buildJsonObject { put("defaultCategoryId", JsonNull) }
        return VaultJson.encodePreserving(serializer(), this, base)
    }

    companion object {
        val DEFAULT = VaultSettings()
        fun fromJson(obj: JsonObject): VaultSettings = VaultJson.sparse.decodeFromJsonElement(serializer(), obj).copy(raw = obj)
    }
}

/**
 * Decrypted payload of an encrypted record, kept as JSON so nothing written by another client is
 * lost. `kind` is inside the ciphertext: "item" | "collection" | "settings" | "tombstone".
 */
class RecordPayload(val json: JsonObject) {
    val kind: String get() = json["kind"]?.jsonPrimitive?.content ?: ""
    val updatedAt: Long get() = json["updatedAt"]?.jsonPrimitive?.long ?: 0
    val device: String get() = json["device"]?.jsonPrimitive?.content ?: ""

    fun item(): VaultItem? = if (kind == "item") (json["item"] as? JsonObject)?.let(VaultItem::fromJson) else null
    fun collection(): VaultCollection? = if (kind == "collection") (json["collection"] as? JsonObject)?.let(VaultCollection::fromJson) else null
    fun settings(): VaultSettings? = if (kind == "settings") (json["settings"] as? JsonObject)?.let(VaultSettings::fromJson) else null

    override fun equals(other: Any?) = other is RecordPayload && other.json == json
    override fun hashCode() = json.hashCode()
    override fun toString() = "RecordPayload($kind)"

    companion object {
        private fun make(kind: String, key: String?, value: JsonObject?, updatedAt: Long, device: String) = RecordPayload(
            buildJsonObject {
                put("kind", kind)
                if (key != null && value != null) put(key, value)
                put("updatedAt", updatedAt)
                put("device", device)
            },
        )

        fun item(item: VaultItem, updatedAt: Long, device: String) = make("item", "item", item.toJson(), updatedAt, device)
        fun collection(c: VaultCollection, updatedAt: Long, device: String) = make("collection", "collection", c.toJson(), updatedAt, device)
        fun settings(s: VaultSettings, updatedAt: Long, device: String) = make("settings", "settings", s.toJson(), updatedAt, device)
        fun tombstone(updatedAt: Long, device: String) = make("tombstone", null, null, updatedAt, device)
    }
}

/** Encrypted record as persisted and synced. Only id/rev/vtag are visible; all are bound via AAD. */
@Serializable
data class EncryptedRecord(val id: String, val rev: Long, val vtag: String, val iv: String, val ct: String) {
    val sealed: Sealed get() = Sealed(iv, ct)
}

@Serializable
data class VaultHeader(
    val format: String = VAULT_FORMAT,
    val version: Int = VAULT_FORMAT_VERSION,
    val vaultId: String,
    val createdAt: Long,
    val rev: Long,
    val vtag: String,
    val updatedAt: Long,
    val kdf: KdfParams,
    val passwordWrap: Sealed,
    val recoveryWrap: Sealed? = null,
    val security: Sealed,
    /** Format v2: set when the password wrap also needs the device-held Secret Key. */
    val secretKey: SecretKeyInfo? = null,
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject {
        val encoded = VaultJson.json.encodeToJsonElement(serializer(), this).jsonObject.omitNullUnlessIn(raw, "secretKey")
        val extra = raw?.filterKeys { it !in encoded && it != "secretKey" } ?: emptyMap()
        return JsonObject(encoded + extra)
    }

    companion object {
        fun fromJson(obj: JsonObject): VaultHeader {
            SyncFormat.validateHeader(obj)
            return VaultJson.json.decodeFromJsonElement(serializer(), obj).copy(raw = obj)
        }
    }
}

@Serializable
data class TotpSettings(
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
    val enrolledAt: Long,
)

@Serializable
data class SecurityConfig(
    val totp: TotpSettings? = null,
    /** SHA-256 hashes (base64) of the remaining single-use recovery codes */
    val recoveryCodeHashes: List<String> = emptyList(),
    val recoveryCodesCreatedAt: Long? = null,
    val recoveryKeyCreatedAt: Long? = null,
    val passwordChangedAt: Long = 0,
    /** Secret Key (26 base32 chars) kept inside the encrypted vault so unlocked devices can show it again */
    val secretKey: String? = null,
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject {
        val encoded = VaultJson.json.encodeToJsonElement(serializer(), this).jsonObject.omitNullUnlessIn(raw, "secretKey")
        val extra = raw?.filterKeys { it !in encoded && it != "secretKey" } ?: emptyMap()
        return JsonObject(encoded + extra)
    }

    companion object {
        fun fromJson(obj: JsonObject): SecurityConfig = VaultJson.json.decodeFromJsonElement(serializer(), obj).copy(raw = obj)
    }
}

// ---------------------------------------------------------------------------------- sync file

class InvalidVaultFileError(message: String) : Exception(message)

data class SyncFile(val version: Int, val writtenAt: Long, val writer: String, val header: VaultHeader, val records: List<EncryptedRecord>)

object SyncFormat {
    const val SYNC_FORMAT = "myvault-sync"
    const val SYNC_FORMAT_VERSION = 1
    const val SYNC_FILE_NAME = "myvault.vault.json"

    private fun isSealed(e: JsonElement?): Boolean =
        e is JsonObject && (e["iv"] as? JsonPrimitive)?.isString == true && (e["ct"] as? JsonPrimitive)?.isString == true

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

    fun validateHeader(h: JsonElement?) {
        if (h !is JsonObject) throw InvalidVaultFileError("Missing vault header")
        if (h.str("format") != VAULT_FORMAT) throw InvalidVaultFileError("Not a MyVault vault")
        val v = h.num("version") ?: throw InvalidVaultFileError("Unsupported vault format version")
        if (v > VAULT_FORMAT_MAX_VERSION) throw InvalidVaultFileError("Unsupported vault format version ${v.toLong()}")
        if (h.str("vaultId") == null || h.str("vtag") == null || h.num("rev") == null) throw InvalidVaultFileError("Vault header is incomplete")
        val kdf = h["kdf"] as? JsonObject
        if (kdf == null || kdf.str("alg") != "argon2id" || kdf.str("salt") == null) throw InvalidVaultFileError("Unsupported key derivation")
        if (!isSealed(h["passwordWrap"]) || !isSealed(h["security"])) throw InvalidVaultFileError("Vault header is incomplete")
        val rw = h["recoveryWrap"]
        if (rw != null && rw !is JsonNull && !isSealed(rw)) throw InvalidVaultFileError("Vault header is corrupted")
        val sk = h["secretKey"]
        if (sk != null && sk !is JsonNull) {
            if (v < 2 || sk !is JsonObject || sk.str("id") == null || sk.num("createdAt") == null) throw InvalidVaultFileError("Vault header is corrupted")
        }
    }

    fun validateRecords(records: JsonElement?): List<EncryptedRecord> {
        if (records !is kotlinx.serialization.json.JsonArray) throw InvalidVaultFileError("Missing records")
        val seen = HashSet<String>()
        return records.map { r ->
            if (r !is JsonObject) throw InvalidVaultFileError("A record is corrupted")
            val rev = r.num("rev")
            if (r.str("id") == null || r.str("vtag") == null || rev == null || rev != Math.floor(rev) || rev < 1 || !isSealed(r)) {
                throw InvalidVaultFileError("A record is corrupted")
            }
            val rec = EncryptedRecord(r.str("id")!!, rev.toLong(), r.str("vtag")!!, r.str("iv")!!, r.str("ct")!!)
            if (!seen.add(rec.id)) throw InvalidVaultFileError("Duplicate record id")
            rec
        }
    }

    fun parse(text: String): SyncFile {
        val data = try {
            VaultJson.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw InvalidVaultFileError("Cloud vault file is not valid JSON (corrupted?)")
        }
        if (data == null || data.str("format") != SYNC_FORMAT) throw InvalidVaultFileError("Not a MyVault sync file")
        val version = data.num("version")
        if (version == null || version > SYNC_FORMAT_VERSION) {
            throw InvalidVaultFileError("Cloud vault was written by a newer MyVault version. Please update this app.")
        }
        val header = VaultHeader.fromJson(data["header"] as? JsonObject ?: throw InvalidVaultFileError("Missing vault header"))
        val records = validateRecords(data["records"])
        return SyncFile(version.toInt(), data.num("writtenAt")?.toLong() ?: 0, data.str("writer") ?: "", header, records)
    }

    fun serialize(header: VaultHeader, records: List<EncryptedRecord>, writer: String, writtenAt: Long = System.currentTimeMillis()): String {
        val obj = buildJsonObject {
            put("format", SYNC_FORMAT)
            put("version", SYNC_FORMAT_VERSION)
            put("writtenAt", writtenAt)
            put("writer", writer)
            put("header", header.toJson())
            put(
                "records",
                VaultJson.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(EncryptedRecord.serializer()),
                    records.sortedWith { a, b -> compareJsStrings(a.id, b.id) },
                ),
            )
        }
        return VaultJson.json.encodeToString(JsonObject.serializer(), obj)
    }
}

/** JS `<` on strings compares UTF-16 code units, which is what Kotlin's String.compareTo does. */
internal fun compareJsStrings(a: String, b: String): Int = a.compareTo(b)
