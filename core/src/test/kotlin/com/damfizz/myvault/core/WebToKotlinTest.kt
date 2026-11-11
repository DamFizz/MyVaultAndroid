package com.damfizz.myvault.core

import com.damfizz.myvault.core.Fixtures.num
import com.damfizz.myvault.core.Fixtures.obj
import com.damfizz.myvault.core.Fixtures.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A vault created by the WEB app's own code (fixtures/web-vault.json) must open and decrypt in Kotlin. */
class WebToKotlinTest {
    private val fx = Fixtures.web
    private val sync = SyncFormat.parse(fx.str("syncFile"))
    private val password = fx.str("password")
    private val secret = fx.str("totpSecret")

    /** Frozen clock: the fixture's trust token and TOTP checks are evaluated at generation time. */
    private val clock = Clock { fx.num("generatedAt") + 1_000 }

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    private fun code() = Totp.totp(secret, clock.now())

    @Test
    fun `primitive vectors match the web (Argon2id default cost, HKDF, TOTP, recovery hash)`() {
        val v = fx.obj("vectors")
        val salt = v.str("kdfSalt")
        for (name in listOf("argon2Test", "argon2Default")) {
            val p = v.obj(name).obj("params")
            val params = KdfParams("argon2id", salt, p.num("memoryKiB").toInt(), p.num("iterations").toInt(), p.num("parallelism").toInt())
            assertEquals(v.obj(name).str("out"), Encoding.toBase64(Crypto.deriveFromPassword(password, params)), name)
        }
        assertEquals(64 * 1024, v.obj("argon2Default").obj("params").num("memoryKiB").toInt())

        val h = v.obj("hkdf")
        val ikm = Encoding.fromBase64(h.str("ikm"))
        val e = h.obj("emptySalt")
        assertEquals(e.str("out"), Encoding.toBase64(Crypto.hkdf(ikm, e.str("info"), e.str("salt"), 32)))
        val s = h.obj("salted")
        assertEquals(s.str("out"), Encoding.toBase64(Crypto.hkdf(ikm, s.str("info"), s.str("salt"), 64)))

        val t = v.obj("totp")
        for (c in t["cases"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(c.str("code6"), Totp.totp(t.str("secret"), c.num("timeSec") * 1000, 6))
            assertEquals(c.str("code8"), Totp.totp(t.str("secret"), c.num("timeSec") * 1000, 8))
        }
        val rc = fx.obj("recoveryCodeHashSample")
        assertEquals(rc.str("hash"), Keys.hashRecoveryCode(rc.str("vaultId"), rc.str("code")))
    }

    @Test
    fun `sync file parses and re-serializes losslessly`() {
        assertEquals(VAULT_FORMAT, sync.header.format)
        assertTrue(sync.records.isNotEmpty())
        val again = SyncFormat.parse(SyncFormat.serialize(sync.header, sync.records, "android"))
        assertEquals(sync.header.copy(raw = null), again.header.copy(raw = null))
        assertEquals(sync.records.sortedBy { it.id }, again.records)
        assertEquals(sync.header.raw, again.header.toJson())
    }

    @Test
    fun `web trust token skips two-step on the same device`() {
        val store = Fixtures.storeFrom(sync, fx["device"], fx["trustToken"])
        val step = VaultAccess(store, clock).unlockWithPassword(password)
        assertIs<UnlockStep.Unlocked>(step)
        assertTrue(step.session.corruptedIds.isEmpty())
    }

    @Test
    fun `trust token is bound to the device id`() {
        val otherDevice = DeviceInfo("another-device", "Android phone").toJson()
        val store = Fixtures.storeFrom(sync, otherDevice, fx["trustToken"])
        assertIs<UnlockStep.SecondFactor>(VaultAccess(store, clock).unlockWithPassword(password))
    }

    @Test
    fun `unlock with TOTP and decrypt every record exactly as the web wrote it`() {
        val store = Fixtures.storeFrom(sync, fx["device"])
        val access = VaultAccess(store, clock)
        assertFailsWith<WrongPasswordError> { access.unlockWithPassword("wrong password") }
        val step = assertIs<UnlockStep.SecondFactor>(access.unlockWithPassword(password))
        assertEquals(10, step.challenge.recoveryCodesLeft)
        assertFailsWith<InvalidCodeError> { step.challenge.verifyTotp("000000".takeIf { it != code() } ?: "111111") }
        val session = step.challenge.verifyTotp(code())

        val expected = fx.obj("expected")
        assertTrue(session.corruptedIds.isEmpty())
        assertEquals(expected.keys, session.allDecrypted().map { it.record.id }.toSet())
        for (d in session.allDecrypted()) assertEquals(expected.obj(d.record.id), d.payload.json, "record ${d.record.id}")

        // Typed views decode and re-encode without losing anything (unknown fields, explicit nulls).
        for (d in session.allDecrypted()) {
            val p = d.payload
            when (p.kind) {
                "item" -> assertEquals(p.json.obj("item"), p.item()!!.toJson(), "item ${d.record.id}")
                "collection" -> assertEquals(p.json.obj("collection"), p.collection()!!.toJson())
                "settings" -> assertEquals(p.json.obj("settings"), p.settings()!!.toJson())
            }
        }
        val login = session.getItem("item-login")!!
        assertEquals("Maybank2u — akaun utama 🏦", login.title)
        assertEquals("0042", login.customFields.first { it.label == "PIN" }.value)
        assertTrue(login.raw!!.containsKey("futureField"))
        assertEquals(setOf("item-login", "item-note", "item-wifi", "item-license"), session.items().map { it.id }.toSet())
        assertEquals(10, session.collections().size)
        assertEquals(10, session.settings().passwordHistoryLimit)
        assertEquals(2, session.allDecrypted().first { it.record.id == "item-wifi" }.record.rev)
        assertEquals("tombstone", session.allDecrypted().first { it.record.id == "item-gone" }.payload.kind)
        assertEquals(secret, session.security.totp!!.secret)

        // Replay protection: the same code cannot be used again on this device.
        val again = assertIs<UnlockStep.SecondFactor>(access.unlockWithPassword(password))
        assertFailsWith<InvalidCodeError> { again.challenge.verifyTotp(code()) }
    }

    @Test
    fun `NFC and NFD forms of the password both unlock`() {
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC)
        assertNotEquals(nfc, password)
        assertIs<UnlockStep.SecondFactor>(VaultAccess(Fixtures.storeFrom(sync), clock).unlockWithPassword(nfc))
    }

    @Test
    fun `single-use recovery code is consumed`() {
        val store = Fixtures.storeFrom(sync, fx["device"])
        val access = VaultAccess(store, clock)
        val codeStr = fx["recoveryCodes"]!!.jsonArray[3].jsonPrimitive.content
        val session = assertIs<UnlockStep.SecondFactor>(access.unlockWithPassword(password)).challenge.useRecoveryCode(codeStr.lowercase())
        assertEquals(9, session.security.recoveryCodeHashes.size)
        assertEquals(sync.header.rev + 1, store.getHeader()!!.rev)
        val next = assertIs<UnlockStep.SecondFactor>(access.unlockWithPassword(password))
        assertFailsWith<InvalidCodeError> { next.challenge.useRecoveryCode(codeStr) }
    }

    @Test
    fun `recovery key resets the master password and still requires two-step`() {
        val store = Fixtures.storeFrom(sync, fx["device"], fx["trustToken"])
        val access = VaultAccess(store, clock)
        val step = access.unlockWithRecoveryKey(fx.str("recoveryKey"), "Baharu-Selepas-Pemulihan-1")
        val session = assertIs<UnlockStep.SecondFactor>(step, "trusted device must not skip two-step here").challenge.verifyTotp(code())
        assertEquals(4, session.items().size)
        assertFailsWith<WrongPasswordError> { access.unlockWithPassword(password) }
        assertIs<UnlockStep.Unlocked>(access.unlockWithPassword("Baharu-Selepas-Pemulihan-1")) // trust token still valid
        assertFailsWith<InvalidCodeError> { VaultAccess(Fixtures.storeFrom(sync), clock).unlockWithRecoveryKey("AAAA-".repeat(8).dropLast(1), "x") }
    }

    @Test
    fun `web backup opens with the backup password`() {
        val opened = Backup.open(fx.str("backup"), fx.str("backupPassword"))
        assertEquals(sync.header.vaultId, opened.header.vaultId)
        assertEquals("web-fixture", opened.appVersion)
        assertFailsWith<Backup.BackupPasswordError> { Backup.open(fx.str("backup"), "wrong") }
        // The backup holds the vault as it was before the trust unlock — still decryptable.
        val store = InMemoryVaultStore().apply { replaceVault(opened.header, opened.records) }
        val session = assertIs<UnlockStep.SecondFactor>(VaultAccess(store, clock).unlockWithPassword(password)).challenge.verifyTotp(code())
        assertEquals(4, session.items().size)
    }

    @Test
    fun `header security config round-trips with explicit nulls`() {
        val store = Fixtures.storeFrom(sync, fx["device"], fx["trustToken"])
        val session = assertIs<UnlockStep.Unlocked>(VaultAccess(store, clock).unlockWithPassword(password)).session
        val raw = Crypto.openJson(session.getKeys().security, sync.header.security, Aad.security(sync.header.vaultId, sync.header.rev, sync.header.vtag)) as JsonObject
        assertEquals(raw, session.security.toJson())
    }
}
