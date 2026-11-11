package com.damfizz.myvault.core

import com.damfizz.myvault.core.Fixtures.num
import com.damfizz.myvault.core.Fixtures.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Writes tools/webfixture/out/kotlin-vault.json for the web verifier (verify-kotlin.fixture.ts):
 *  - "created": a vault created entirely by Kotlin (TOTP, recovery key/codes, trust token, backup)
 *  - "editedWeb": the web fixture vault after Kotlin edited items, collections, codes and the master password
 */
class KotlinToWebTest {
    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    private fun payloads(session: VaultSession) = JsonObject(session.allDecrypted().associate { it.record.id to it.payload.json })
    private fun codes(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

    private fun createdByKotlin(): JsonObject {
        val password = "Android-Cipta-Vault-Ñ-77 ✓"
        val store = InMemoryVaultStore()
        val access = VaultAccess(store)
        val session = access.createVault(password)
        val now = System.currentTimeMillis()

        val section = VaultCollection(id = Encoding.randomId(), kind = "section", name = "Kerja", icon = "briefcase", color = "#3b82f6", order = 0, createdAt = now, updatedAt = now)
        session.saveCollections(listOf(section))
        val work = session.collections().first { it.name == "Work" }
        val login = VaultItem.empty(ItemType.LOGIN, Encoding.randomId(), now).copy(
            title = "GitHub — kerja 💼",
            url = "https://github.com",
            username = "DamFizz",
            password = "p@ss\"\\word\n✓",
            categoryId = work.id,
            sectionId = section.id,
            tags = listOf("dev"),
            customFields = listOf(CustomField(Encoding.randomId(), "2FA backup", "1234 5678", hidden = true)),
            favorite = true,
            reprompt = true,
            passwordChangedAt = now,
        )
        val note = VaultItem.empty(ItemType.NOTE, Encoding.randomId(), now).copy(title = "Nota Android", notes = "Baris 1\nBaris 2")
        val gone = VaultItem.empty(ItemType.LOGIN, Encoding.randomId(), now).copy(title = "Akan dipadam")
        session.saveItems(listOf(login, note, gone))
        session.purgeItems(listOf(gone.id))
        session.saveSettings { it.copy(trashRetentionDays = 7) }

        val recoveryKey = access.createRecoveryKey(session, password)
        val secret = Totp.generateSecret()
        val recoveryCodes = access.enableTotp(session, secret, Totp.totp(secret))
        val backup = Backup.create(session.header, session.encryptedRecords(), "Sandaran-Android-1", "android-fixture")

        // Trust this device via a real unlock (clear the replay step left by enrollment).
        store.deleteLocal("totpLastStep:${session.vaultId}")
        val s2 = assertIs<UnlockStep.SecondFactor>(access.unlockWithPassword(password)).challenge.verifyTotp(Totp.totp(secret), trustDays = 30)
        assertIs<UnlockStep.Unlocked>(access.unlockWithPassword(password), "Kotlin trust token works in Kotlin")

        return buildJsonObject {
            put("password", password)
            put("totpSecret", secret)
            put("recoveryKey", recoveryKey)
            put("recoveryCodes", codes(recoveryCodes))
            put("backup", backup)
            put("backupPassword", "Sandaran-Android-1")
            put("device", store.getLocal("device")!!)
            put("trustToken", store.getLocal("totpTrust:${s2.vaultId}")!!)
            put("syncFile", SyncFormat.serialize(s2.header, store.allRecords(), "android-fixture-writer"))
            put("expected", payloads(s2))
            put("loginId", login.id)
            put("noteId", note.id)
            put("goneId", gone.id)
        }
    }

    private fun editedWebVault(): JsonObject {
        val fx = Fixtures.web
        val clock = Clock { fx.num("generatedAt") + 1_000 }
        val store = Fixtures.storeFrom(SyncFormat.parse(fx.str("syncFile")), fx["device"], fx["trustToken"])
        val access = VaultAccess(store, clock)
        val session = assertIs<UnlockStep.Unlocked>(access.unlockWithPassword(fx.str("password"))).session
        val now = clock.now()

        val login = session.getItem("item-login")!!
        session.saveItem(
            login.copy(
                title = "Maybank2u (disunting di Android)",
                password = "kata-laluan-baharu",
                passwordHistory = (login.passwordHistory ?: emptyList()) + PasswordHistoryEntry(login.password!!, now),
                passwordChangedAt = now,
                updatedAt = now,
            ),
        )
        val added = VaultItem.empty(ItemType.WIFI, "item-android-wifi", now).copy(title = "Pejabat", ssid = "Office-5G", password = "wifi-✓")
        session.saveItem(added)
        session.purgeItems(listOf("item-note"))
        session.saveCollections(listOf(VaultCollection(id = "col-android", kind = "category", name = "Dari Android", order = 20, createdAt = now, updatedAt = now)))
        val newCodes = access.regenerateRecoveryCodes(session)
        val newPassword = "Android-Baharu-✓-99"
        access.changeMasterPassword(session, fx.str("password"), newPassword)

        assertTrue(session.getItem("item-login")!!.raw!!.containsKey("futureField"))
        return buildJsonObject {
            put("password", newPassword)
            put("oldPassword", fx.str("password"))
            put("totpSecret", fx.str("totpSecret"))
            put("recoveryKey", fx.str("recoveryKey"))
            put("recoveryCodes", codes(newCodes))
            put("oldRecoveryCode", (fx["recoveryCodes"] as JsonArray)[0])
            put("syncFile", SyncFormat.serialize(session.header, store.allRecords(), "android-fixture-writer"))
            put("expected", payloads(session))
        }
    }

    @Test
    fun `write Kotlin-made vaults for the web verifier`() {
        val out = buildJsonObject {
            put("comment", "Generated by KotlinToWebTest. Verified by tools/webfixture/verify-kotlin.fixture.ts.")
            put("generatedAt", System.currentTimeMillis())
            put("created", createdByKotlin())
            put("editedWeb", editedWebVault())
        }
        val file = File(Fixtures.outDir(), "kotlin-vault.json")
        file.writeText(VaultJson.json.encodeToString(JsonObject.serializer(), out), Charsets.UTF_8)
        println("Wrote ${file.absolutePath}")
        assertEquals(out, VaultJson.json.parseToJsonElement(file.readText()))
    }
}
