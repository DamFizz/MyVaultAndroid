package com.damfizz.myvault.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persistence boundary, mirroring the web's IndexedDB tables (`src/vault/db.ts`):
 *  - header: KDF params + wrapped vault key + encrypted security config
 *  - records: encrypted items, collections, settings and tombstones
 *  - local: device-only state (throttle, TOTP replay step, trust token, sync bookkeeping, sealed device secrets)
 *
 * Calls are blocking; Android callers run them off the main thread. Stage 3 adds a Room/file
 * implementation; tests use [InMemoryVaultStore].
 */
interface VaultStore {
    fun getHeader(): VaultHeader?
    fun putHeader(header: VaultHeader)
    fun allRecords(): List<EncryptedRecord>
    fun putRecords(records: List<EncryptedRecord>)
    fun deleteRecords(ids: List<String>)
    fun getLocal(key: String): JsonElement?
    fun setLocal(key: String, value: JsonElement)
    fun deleteLocal(key: String)

    /** Atomically replace header + records; device-local keys are kept unless listed. */
    fun replaceVault(header: VaultHeader, records: List<EncryptedRecord>, clearLocalKeys: List<String> = emptyList())
}

class InMemoryVaultStore : VaultStore {
    private var header: VaultHeader? = null
    private val records = LinkedHashMap<String, EncryptedRecord>()
    val local = LinkedHashMap<String, JsonElement>()

    @Synchronized override fun getHeader() = header
    @Synchronized override fun putHeader(header: VaultHeader) { this.header = header }
    @Synchronized override fun allRecords() = records.values.toList()
    @Synchronized override fun putRecords(records: List<EncryptedRecord>) { for (r in records) this.records[r.id] = r }
    @Synchronized override fun deleteRecords(ids: List<String>) { for (id in ids) records.remove(id) }
    @Synchronized override fun getLocal(key: String) = local[key]
    @Synchronized override fun setLocal(key: String, value: JsonElement) { local[key] = value }
    @Synchronized override fun deleteLocal(key: String) { local.remove(key) }
    @Synchronized override fun replaceVault(header: VaultHeader, records: List<EncryptedRecord>, clearLocalKeys: List<String>) {
        this.records.clear()
        for (r in records) this.records[r.id] = r
        this.header = header
        for (k in clearLocalKeys) local.remove(k)
    }
}

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

data class DeviceInfo(val id: String, val name: String) {
    fun toJson(): JsonObject = buildJsonObject { put("id", id); put("name", name) }
}

/** Port of `src/vault/device.ts`: a random per-device id stored in local state. */
object Devices {
    const val DEFAULT_NAME = "Android phone"

    fun get(store: VaultStore, defaultName: String = DEFAULT_NAME): DeviceInfo {
        val obj = store.getLocal("device") as? JsonObject
        val id = (obj?.get("id") as? JsonPrimitive)?.contentOrNull
        if (obj != null && id != null) return DeviceInfo(id, obj["name"]?.jsonPrimitive?.contentOrNull ?: defaultName)
        val device = DeviceInfo(Encoding.randomId(), defaultName)
        store.setLocal("device", device.toJson())
        return device
    }

    fun rename(store: VaultStore, name: String): DeviceInfo {
        val d = get(store)
        val next = d.copy(name = name.trim().take(40).ifEmpty { d.name })
        store.setLocal("device", next.toJson())
        return next
    }
}

/**
 * Failed-attempt throttling (port of `src/vault/throttle.ts`): after 5 consecutive failures
 * each further failure doubles a lockout window (30 s … capped at 15 min). Per device.
 * This only slows guessing through the app; offline attacks are slowed by Argon2id.
 */
object Throttle {
    private const val KEY = "throttle"
    private const val FREE_ATTEMPTS = 5
    private const val BASE_MS = 30_000L
    private const val MAX_MS = 15 * 60_000L

    data class State(val failures: Int, val lockedUntil: Long)

    class ThrottledError(val retryAt: Long) : Exception("Too many attempts. Please wait before trying again.")

    fun state(store: VaultStore): State {
        val o = store.getLocal(KEY) as? JsonObject ?: return State(0, 0)
        return State(
            o["failures"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0,
            o["lockedUntil"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0,
        )
    }

    fun assertNotThrottled(store: VaultStore, now: Long) {
        val s = state(store)
        if (s.lockedUntil > now) throw ThrottledError(s.lockedUntil)
    }

    fun recordFailure(store: VaultStore, now: Long): State {
        val failures = state(store).failures + 1
        var lockedUntil = 0L
        if (failures >= FREE_ATTEMPTS) {
            val shift = (failures - FREE_ATTEMPTS).coerceAtMost(20)
            lockedUntil = now + minOf(MAX_MS, BASE_MS shl shift)
        }
        store.setLocal(KEY, buildJsonObject { put("failures", failures); put("lockedUntil", lockedUntil) })
        return State(failures, lockedUntil)
    }

    fun recordSuccess(store: VaultStore) = store.deleteLocal(KEY)
}
