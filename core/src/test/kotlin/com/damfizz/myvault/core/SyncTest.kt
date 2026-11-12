package com.damfizz.myvault.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory stand-in for Google Drive (port of the web's tests/memory-remote.ts). */
class MemoryRemote : RemoteStore {
    class F(var content: String, var version: Int)
    val files = LinkedHashMap<String, F>()
    private var nextId = 1
    var beforeUpdate: (() -> Unit)? = null
    var afterDownload: (() -> Unit)? = null
    var updates = 0

    override fun find() = files.entries.firstOrNull()?.let { RemoteFileMeta(it.key, it.value.version.toString()) }
    override fun getMeta(id: String) = files[id]?.let { RemoteFileMeta(id, it.version.toString()) }
    override fun download(id: String): String {
        val c = files[id]?.content ?: throw RemoteError("not-found", "File not found", 404)
        afterDownload?.invoke()
        return c
    }
    override fun create(content: String): RemoteFileMeta {
        val id = "file-${nextId++}"
        files[id] = F(content, 1)
        return RemoteFileMeta(id, "1")
    }
    override fun update(id: String, content: String): RemoteFileMeta {
        beforeUpdate?.invoke()
        val f = files[id] ?: throw RemoteError("not-found", "File not found", 404)
        f.content = content
        f.version++
        updates++
        return RemoteFileMeta(id, f.version.toString())
    }
    override fun remove(id: String) { files.remove(id) }
    fun only() = files.values.single()
}

/** Port of the web's tests/sync.test.ts "sync engine" suite, plus the Secret Key rule. */
class SyncTest {
    private var now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val pw = "Password-For-Tests-1"

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    private class Dev(val store: InMemoryVaultStore, val access: VaultAccess, var s: VaultSession)

    private fun login(id: String, title: String, password: String = "pw") = VaultItem.empty(ItemType.LOGIN, id, now).copy(title = title, password = password)
    private fun save(d: Dev, item: VaultItem) { now += 10; Items.save(d.s, item, now) }
    private fun sync(d: Dev, remote: MemoryRemote) = SyncEngine(d.s, remote, clock).sync()

    private fun twoDevices(): Triple<MemoryRemote, Dev, Dev> {
        val remote = MemoryRemote()
        val storeA = InMemoryVaultStore()
        val accessA = VaultAccess(storeA, clock, "PC")
        val a = Dev(storeA, accessA, accessA.createVault(pw))
        sync(a, remote)
        val storeB = InMemoryVaultStore()
        SyncEngine.downloadToDevice(storeB, remote, now = now)
        val accessB = VaultAccess(storeB, clock, "Phone")
        val b = Dev(storeB, accessB, assertIs<UnlockStep.Unlocked>(accessB.unlockWithPassword(pw)).session)
        sync(b, remote)
        return Triple(remote, a, b)
    }

    @Test
    fun `first sync uploads only ciphertext`() {
        val remote = MemoryRemote()
        val store = InMemoryVaultStore()
        val access = VaultAccess(store, clock)
        val d = Dev(store, access, access.createVault(pw))
        save(d, login("i1", "Instagram", "insta-SECRET-pw"))
        val r = sync(d, remote)
        assertTrue(r.created)
        val content = remote.only().content
        assertFalse(content.contains("Instagram"))
        assertFalse(content.contains("insta-SECRET-pw"))
        assertEquals(d.s.vaultId, SyncFormat.parse(content).header.vaultId)
    }

    @Test
    fun `round trip, and a second sync with no changes does not rewrite the remote`() {
        val (remote, a, b) = twoDevices()
        save(a, login("i1", "Instagram", "old"))
        sync(a, remote)
        assertEquals(1, sync(b, remote).pulled)
        save(b, b.s.getItem("i1")!!.copy(password = "new"))
        sync(b, remote)
        sync(a, remote)
        assertEquals("new", a.s.getItem("i1")!!.password)
        assertEquals("old", a.s.getItem("i1")!!.passwordHistory!!.single().password)
        val before = remote.updates
        val r = sync(a, remote)
        assertEquals(before, remote.updates)
        assertEquals(0, r.pulled + r.pushed)
    }

    @Test
    fun `concurrent edits keep both versions as a conflict copy and warn`() {
        val (remote, a, b) = twoDevices()
        save(a, login("i1", "Bank", "v0"))
        sync(a, remote); sync(b, remote)
        save(a, a.s.getItem("i1")!!.copy(password = "from-pc"))
        save(b, b.s.getItem("i1")!!.copy(password = "from-phone"))
        sync(a, remote)
        val r = sync(b, remote)
        assertEquals(1, r.conflicts.size)
        assertEquals("from-phone", b.s.getItem("i1")!!.password, "newest wins")
        val copy = b.s.items().single { it.title == "Bank (conflict copy)" }
        assertEquals("from-pc", copy.password)
        sync(a, remote)
        assertEquals("from-phone", a.s.getItem("i1")!!.password)
        assertNotNull(a.s.items().singleOrNull { it.title == "Bank (conflict copy)" })
        assertEquals(1, b.s.getDeviceSecrets().conflicts.size)
    }

    @Test
    fun `deletions propagate, but an edit beats a concurrent deletion`() {
        val (remote, a, b) = twoDevices()
        save(a, login("del", "Delete me"))
        save(a, login("keep", "Edited elsewhere"))
        sync(a, remote); sync(b, remote)
        a.s.purgeItems(listOf("del", "keep"))
        save(b, b.s.getItem("keep")!!.copy(notes = "edited on phone"))
        sync(a, remote)
        sync(b, remote)
        assertNull(b.s.getItem("del"))
        assertEquals("edited on phone", b.s.getItem("keep")!!.notes)
        sync(a, remote)
        assertEquals("edited on phone", a.s.getItem("keep")!!.notes)
        assertNull(a.s.getItem("del"))
    }

    @Test
    fun `retries when another device writes during sync (no lost update)`() {
        val (remote, a, b) = twoDevices()
        save(a, login("a1", "From PC"))
        save(b, login("b1", "From phone"))
        var injected = false
        remote.afterDownload = {
            if (!injected) {
                injected = true
                remote.afterDownload = null
                sync(b, remote) // phone writes between the PC's download and upload
            }
        }
        sync(a, remote)
        assertNotNull(a.s.getItem("b1"))
        sync(b, remote)
        assertNotNull(b.s.getItem("a1"))
    }

    @Test
    fun `a rolled-back remote file never overwrites newer local data`() {
        val (remote, a, b) = twoDevices()
        save(a, login("i1", "Item", "v1"))
        sync(a, remote)
        val stale = remote.only().content
        save(a, a.s.getItem("i1")!!.copy(password = "v2"))
        sync(a, remote)
        remote.only().content = stale
        remote.only().version++
        sync(a, remote)
        assertEquals("v2", a.s.getItem("i1")!!.password)
        sync(b, remote)
        assertEquals("v2", b.s.getItem("i1")!!.password)
    }

    @Test
    fun `master password change on one device applies to the other`() {
        val (remote, a, b) = twoDevices()
        a.access.changeMasterPassword(a.s, pw, "Changed-Password-22")
        sync(a, remote)
        assertTrue(sync(b, remote).headerUpdated)
        b.s.lock()
        assertFailsWith<WrongPasswordError> { b.access.unlockWithPassword(pw) }
        assertIs<UnlockStep.Unlocked>(b.access.unlockWithPassword("Changed-Password-22"))
    }

    @Test
    fun `refuses a different vault and skips tampered remote records`() {
        val (remote, a, b) = twoDevices()
        val otherStore = InMemoryVaultStore()
        val other = VaultAccess(otherStore, clock).createVault(pw)
        assertFailsWith<VaultMismatchError> { SyncEngine(other, remote, clock).sync() }

        save(a, login("i1", "Item"))
        sync(a, remote)
        val f = remote.only()
        val parsed = SyncFormat.parse(f.content)
        val bad = parsed.records.map { if (it.id == "i1") it.copy(ct = it.ct.replaceRange(4, 5, if (it.ct[4] == 'A') "B" else "A")) else it }
        f.content = SyncFormat.serialize(parsed.header, bad, "attacker", now)
        f.version++
        val r = sync(b, remote)
        assertEquals(1, r.skippedCorrupt)
        assertNull(b.s.getItem("i1"))
    }

    @Test
    fun `a concurrent password change never silently removes the Secret Key`() {
        val (remote, a, b) = twoDevices()
        a.access.createRecoveryKey(a.s, pw)
        sync(a, remote); sync(b, remote)
        a.access.enableSecretKey(a.s, pw, SecretKeys.generate().display)
        sync(a, remote)
        now += 5_000
        b.access.changeMasterPassword(b.s, pw, "Changed-On-Phone-55") // later in time, without the Secret Key
        val r = sync(b, remote)
        assertTrue(r.conflicts.any { it.kind == "header" })
        assertEquals(a.s.header.secretKey!!.id, b.s.header.secretKey?.id, "the Secret Key header won despite being older")
        sync(a, remote)
        assertNotNull(a.s.header.secretKey)
        assertNotNull(SyncFormat.parse(remote.only().content).header.secretKey)
    }
}
