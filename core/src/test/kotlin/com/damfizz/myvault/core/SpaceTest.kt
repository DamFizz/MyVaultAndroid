package com.damfizz.myvault.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** My Space: bank accounts + payment QR images stored as encrypted vault records. */
class SpaceTest {
    private var now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val pw = "Password-For-Tests-1"

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    /** A small fake "image" (the core never decodes images; it stores the exact bytes). */
    private val png = ByteArray(3000) { (it * 31 % 251).toByte() }

    private fun bank(id: String, number: String = "000123456789", qr: String? = null) = SpaceEntry(
        id = id, type = SpaceType.BANK, title = "Maybank Personal", bankId = "maybank", bankName = "Maybank",
        holder = "Ahmad", accountNumber = number, notes = "private note", qrAssetId = qr, createdAt = now, updatedAt = now,
    )

    private fun asset(id: String, owner: String, bytes: ByteArray = png) = SpaceAsset.of(id, owner, "image/png", 300, 300, bytes, now)

    @Test
    fun `account numbers are strings - leading zeros and exact digits survive`() {
        assertEquals("000123456789", Space.normalizeAccountNumber(" 0001-2345 6789 "))
        val e = bank("b1", "0012345678901234")
        val back = SpaceEntry.fromJson(e.toJson())
        assertEquals("0012345678901234", back.accountNumber)
        assertEquals("•••• 1234", Space.masked(back.accountNumber))
        assertEquals("0012 3456 7890 1234", Space.grouped(back.accountNumber))
        assertEquals("123", Space.masked("123"))
    }

    @Test
    fun `entries saved before the owner field are the user's own, someone else's are named after the person`() {
        val old = SpaceEntry.fromJson(bank("b1").toJson().let { kotlinx.serialization.json.JsonObject(it - "owner") })
        assertTrue(old.isMine)
        val friend = bank("f1").copy(title = "", owner = SpaceOwner.OTHER, holder = "Ali bin Abu")
        val back = SpaceEntry.fromJson(friend.toJson())
        assertFalse(back.isMine)
        assertEquals("Ali bin Abu", back.displayName)
        assertEquals("Maybank", bank("m1").copy(title = "").displayName)
    }

    @Test
    fun `share text contains only what was chosen and never notes`() {
        val e = bank("b1")
        assertEquals("Bank: Maybank\nAccount holder: Ahmad\nAccount number: 000123456789", Space.shareText(e))
        assertEquals("Account number: 000123456789", Space.shareText(e, Space.ShareOptions(includeBank = false, includeHolder = false)))
        assertFalse(Space.shareText(e).contains("private note"))
    }

    @Test
    fun `search matches names and account digits, favourites sort first`() {
        val a = bank("a").copy(title = "Work", bankName = "CIMB Bank", accountNumber = "7000111222")
        val b = bank("b").copy(title = "Savings", favorite = true)
        assertEquals(listOf("a"), Space.search(listOf(a, b), "cimb").map { it.id })
        assertEquals(listOf("a"), Space.search(listOf(a, b), "111 222").map { it.id })
        assertEquals(listOf("b", "a"), Space.sorted(listOf(a, b)).map { it.id })
    }

    @Test
    fun `entry and QR image are encrypted at rest and survive lock and unlock`() {
        val store = InMemoryVaultStore()
        val access = VaultAccess(store, clock)
        val s = access.createVault(pw)
        s.saveSpaceEntry(bank("b1", qr = "q1"), asset("q1", "b1"))
        // Nothing readable in what is persisted.
        val persisted = store.allRecords().joinToString { it.ct + it.iv + it.id }
        assertFalse(persisted.contains("000123456789"))
        assertFalse(persisted.contains("Ahmad"))
        s.lock()
        val s2 = assertIs<UnlockStep.Unlocked>(access.unlockWithPassword(pw)).session
        val e = assertNotNull(s2.spaceEntry("b1"))
        assertEquals("000123456789", e.accountNumber)
        val a = assertNotNull(s2.asset(e.qrAssetId))
        assertContentEquals(png, a.bytes())
        assertTrue(a.intact())
        // Password items are untouched by space records.
        assertTrue(s2.items().isEmpty())
    }

    @Test
    fun `replacing a QR drops the old image, deleting the entry drops both`() {
        val s = VaultAccess(InMemoryVaultStore(), clock).createVault(pw)
        s.saveSpaceEntry(bank("b1", qr = "q1"), asset("q1", "b1"))
        now += 10
        val other = ByteArray(10) { 7 }
        s.saveSpaceEntry(s.spaceEntry("b1")!!.copy(qrAssetId = "q2"), asset("q2", "b1", other), dropAssetId = "q1")
        assertNull(s.asset("q1"))
        assertContentEquals(other, s.asset("q2")!!.bytes())
        now += 10
        s.deleteSpaceEntry("b1")
        assertNull(s.spaceEntry("b1"))
        assertNull(s.asset("q2"))
    }

    @Test
    fun `entries and their QR images sync to another device, concurrent edits keep a conflict copy`() {
        val remote = MemoryRemote()
        val storeA = InMemoryVaultStore()
        val a = VaultAccess(storeA, clock, "PC").createVault(pw)
        SyncEngine(a, remote, clock).sync()
        val storeB = InMemoryVaultStore()
        SyncEngine.downloadToDevice(storeB, remote, now = now)
        val b = assertIs<UnlockStep.Unlocked>(VaultAccess(storeB, clock, "Phone").unlockWithPassword(pw)).session
        SyncEngine(b, remote, clock).sync()

        now += 10
        a.saveSpaceEntry(bank("b1", qr = "q1"), asset("q1", "b1"))
        SyncEngine(a, remote, clock).sync()
        assertFalse(remote.only().content.contains("000123456789"))
        SyncEngine(b, remote, clock).sync()
        assertEquals("000123456789", b.spaceEntry("b1")!!.accountNumber)
        assertContentEquals(png, b.asset("q1")!!.bytes())

        now += 10
        a.saveSpaceEntry(a.spaceEntry("b1")!!.copy(title = "From PC", updatedAt = now))
        now += 10
        b.saveSpaceEntry(b.spaceEntry("b1")!!.copy(title = "From Phone", updatedAt = now))
        SyncEngine(a, remote, clock).sync()
        SyncEngine(b, remote, clock).sync()
        SyncEngine(a, remote, clock).sync()
        val titles = a.spaceEntries().map { it.title }.sorted()
        assertEquals(listOf("From PC (conflict copy)", "From Phone"), titles)
        // Both versions still show the same QR image.
        assertTrue(a.spaceEntries().all { a.asset(it.qrAssetId) != null })
    }

    @Test
    fun `backup includes entries and QR images, restore merge brings them back`() {
        val src = VaultAccess(InMemoryVaultStore(), clock).createVault(pw)
        src.saveSpaceEntry(bank("b1", qr = "q1"), asset("q1", "b1"))
        val text = Backup.create(src.header, src.encryptedRecords(), "Backup-Pass-2026", "test", now)
        assertFalse(text.contains("000123456789"))

        val dst = VaultAccess(InMemoryVaultStore(), clock).createVault("Another-Password-9")
        val opened = Backup.open(text, "Backup-Pass-2026")
        val r = Backup.restoreMerge(dst, opened, pw, null, now)
        assertEquals(1, r.added)
        val e = assertNotNull(dst.spaceEntry("b1"))
        assertContentEquals(png, dst.asset(e.qrAssetId)!!.bytes())
        // Restoring the same backup again changes nothing.
        assertEquals(0, Backup.restoreMerge(dst, opened, pw, null, now).added)
    }
}
