package com.damfizz.myvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Shared helpers for the compatibility tests. */
object Fixtures {
    val TEST_KDF = KdfCost(memoryKiB = 8192, iterations = 2, parallelism = 1)

    val web: JsonObject by lazy {
        val text = Fixtures::class.java.getResource("/fixtures/web-vault.json")!!.readText(Charsets.UTF_8)
        Json.parseToJsonElement(text).jsonObject
    }

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
    fun JsonObject.num(key: String): Long = this[key]!!.jsonPrimitive.long

    /** A store holding the given synced vault, optionally with device-local state. */
    fun storeFrom(sync: SyncFile, device: JsonElement? = null, trustToken: JsonElement? = null): InMemoryVaultStore {
        val store = InMemoryVaultStore()
        store.replaceVault(sync.header, sync.records)
        if (device != null) store.setLocal("device", device)
        if (trustToken != null) store.setLocal("totpTrust:${sync.header.vaultId}", trustToken)
        return store
    }

    fun outDir(): File = File(System.getProperty("myvault.fixtureOut") ?: "build/fixture-out").also { it.mkdirs() }
}
