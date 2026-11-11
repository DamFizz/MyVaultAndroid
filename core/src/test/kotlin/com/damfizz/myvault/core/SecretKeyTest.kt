package com.damfizz.myvault.core

import com.damfizz.myvault.core.Fixtures.obj
import com.damfizz.myvault.core.Fixtures.str
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Secret Key (format v2): web-made vault opens in Kotlin, plus Kotlin-side lifecycle tests. */
class SecretKeyTest {
    private val v2 = Fixtures.web.obj("v2")
    private val sync = SyncFormat.parse(v2.str("syncFile"))
    private val password = v2.str("password")

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    @Test
    fun `display format, checksum and id match the web`() {
        val vec = v2.obj("vector")
        val raw = Encoding.fromBase64(vec.str("raw"))
        assertEquals(vec.str("display"), SecretKeys.format(raw))
        assertEquals(vec.str("id"), SecretKeys.id(raw, vec.str("vaultId")))
        assertContentEquals(raw, SecretKeys.parse(vec.str("display").lowercase().replace("-", " ")).raw)
        val d = vec.str("display")
        val typo = d.substring(0, d.length - 6) + (if (d[d.length - 6] == 'A') 'B' else 'A') + d.substring(d.length - 5)
        assertFailsWith<InvalidSecretKeyError> { SecretKeys.parse(typo) }
        val qr = SecretKeys.parse(SecretKeys.qrPayload("v-1", raw))
        assertEquals("v-1", qr.vaultId)
        assertContentEquals(raw, qr.raw)
    }

    @Test
    fun `web Secret Key vault needs the key on a new device`() {
        assertEquals(2, sync.header.version)
        assertEquals(sync.header.raw, sync.header.toJson(), "v2 header re-serializes losslessly")
        val store = Fixtures.storeFrom(sync)
        val access = VaultAccess(store)
        assertFalse(access.hasSecretKeyOnDevice())
        assertFailsWith<SecretKeyRequiredError> { access.unlockWithPassword(password) }
        assertFailsWith<WrongSecretKeyError> { access.unlockWithPassword(password, SecretKeySource(SecretKeys.generate().display)) }
        assertFailsWith<WrongPasswordError> { access.unlockWithPassword("wrong", SecretKeySource(v2.str("secretKey"))) }
        assertFalse(access.hasSecretKeyOnDevice(), "not remembered after a failed unlock")

        val session = assertIs<UnlockStep.Unlocked>(access.unlockWithPassword(password, SecretKeySource(v2.str("secretKey")))).session
        val expected = v2.obj("expected")
        assertEquals(expected.keys, session.allDecrypted().map { it.record.id }.toSet())
        for (d in session.allDecrypted()) assertEquals(expected.obj(d.record.id), d.payload.json)
        assertEquals(v2.str("secretKey"), SecretKeys.format(access.currentSecretKey(session)!!))
        assertTrue(access.hasSecretKeyOnDevice())
        assertIs<UnlockStep.Unlocked>(access.unlockWithPassword(password)) // remembered

        // The password-only (v1) KEK no longer unwraps the vault key.
        val v1 = Keys.passwordKek(password, sync.header.kdf, sync.header.vaultId)
        assertFailsWith<DecryptionError> { Keys.unwrapVaultKey(v1, sync.header.passwordWrap, sync.header.vaultId, WrapPurpose.PASSWORD) }
    }

    @Test
    fun `do not remember on a shared device`() {
        val access = VaultAccess(Fixtures.storeFrom(sync))
        assertIs<UnlockStep.Unlocked>(access.unlockWithPassword(password, SecretKeySource(v2.str("secretKey"), rememberSecretKey = false)))
        assertFailsWith<SecretKeyRequiredError> { access.unlockWithPassword(password) }
    }

    @Test
    fun `recovery key on a new device recovers the web Secret Key`() {
        val store = Fixtures.storeFrom(sync)
        val access = VaultAccess(store)
        assertIs<UnlockStep.Unlocked>(access.unlockWithRecoveryKey(v2.str("recoveryKey"), "Pulih-Di-Android-9"))
        assertIs<UnlockStep.Unlocked>(access.unlockWithPassword("Pulih-Di-Android-9"))
        assertEquals(2, store.getHeader()!!.version)
    }

    @Test
    fun `Kotlin lifecycle - recovery key required, enable, change password, rotate, disable`() {
        val store = InMemoryVaultStore()
        val access = VaultAccess(store)
        val session = access.createVault("Kotlin-Pass-1")
        assertFailsWith<RecoveryKeyRequiredError> { access.enableSecretKey(session, "Kotlin-Pass-1", SecretKeys.generate().display) }
        access.createRecoveryKey(session, "Kotlin-Pass-1")
        assertFalse(session.header.toJson().containsKey("secretKey"), "v1 header keeps the web's shape")

        val first = SecretKeys.generate()
        access.enableSecretKey(session, "Kotlin-Pass-1", first.display)
        access.changeMasterPassword(session, "Kotlin-Pass-1", "Kotlin-Pass-2")
        val fresh = { InMemoryVaultStore().apply { replaceVault(store.getHeader()!!, store.allRecords()) } }
        val other = fresh()
        assertFailsWith<SecretKeyRequiredError> { VaultAccess(other).unlockWithPassword("Kotlin-Pass-2") }
        assertIs<UnlockStep.Unlocked>(VaultAccess(other).unlockWithPassword("Kotlin-Pass-2", SecretKeySource(first.display)))

        val second = SecretKeys.generate()
        access.enableSecretKey(session, "Kotlin-Pass-2", second.display) // rotate
        other.putHeader(store.getHeader()!!)
        assertFailsWith<SecretKeyRequiredError> { VaultAccess(other).unlockWithPassword("Kotlin-Pass-2") } // stale key discarded
        assertFailsWith<WrongSecretKeyError> { VaultAccess(other).unlockWithPassword("Kotlin-Pass-2", SecretKeySource(first.display)) }

        access.disableSecretKey(session, "Kotlin-Pass-2")
        assertNull(session.header.secretKey)
        assertEquals(2, session.header.version)
        assertIs<UnlockStep.Unlocked>(VaultAccess(fresh()).unlockWithPassword("Kotlin-Pass-2"))
    }

    @Test
    fun `device storage goes through the secret box`() {
        val store = InMemoryVaultStore()
        var sealedCalls = 0
        val box = object : LocalSecretBox {
            override fun seal(plain: ByteArray): ByteArray { sealedCalls++; return plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray() }
            override fun open(sealed: ByteArray): ByteArray = sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        val keys = StoreDeviceSecretKeys(store, box)
        val raw = SecretKeys.generate().raw
        keys.save("v", "id-1", raw)
        assertEquals(1, sealedCalls)
        assertFalse(store.local.toString().contains(Encoding.toBase64(raw)))
        assertContentEquals(raw, keys.load("v")!!.raw)
    }
}
