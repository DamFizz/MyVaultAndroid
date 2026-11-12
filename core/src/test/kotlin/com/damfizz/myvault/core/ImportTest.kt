package com.damfizz.myvault.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportTest {
    private val now = 1_800_000_000_000L

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    private fun vault(pw: String = "Password-For-Tests-1"): Pair<InMemoryVaultStore, VaultSession> {
        val store = InMemoryVaultStore()
        return store to VaultAccess(store, Clock { now }).createVault(pw)
    }

    @Test
    fun `CSV parser handles quotes, embedded newlines, CRLF and BOM`() {
        val rows = Csv.parse("﻿name,notes\r\n\"A, Inc\",\"line1\nline2 \"\"q\"\"\"\r\n\r\nB,\n")
        assertEquals(listOf(listOf("name", "notes"), listOf("A, Inc", "line1\nline2 \"q\""), listOf("B", "")), rows)
    }

    @Test
    fun `detects common export formats and maps columns`() {
        assertEquals("Chrome / Edge", Csv.detect("name,url,username,password,note\nA,a.com,u,p,n").detectedFormat)
        assertEquals("Bitwarden", Csv.detect("folder,favorite,type,name,notes,login_uri,login_username,login_password,login_totp\nF,1,login,A,,a.com,u,p,").detectedFormat)
        assertEquals("LastPass", Csv.detect("url,username,password,totp,extra,name,grouping,fav\na,u,p,,,A,G,0").detectedFormat)
        assertEquals("KeePass", Csv.detect("Group,Title,Username,Password,URL,Notes\nG,A,u,p,a.com,").detectedFormat)
        assertFailsWith<IllegalArgumentException> { Csv.detect("a,b\n1,2") }
        assertFailsWith<IllegalArgumentException> { Csv.detect("name,password") }
    }

    @Test
    fun `CSV import creates folders, notes, favourites, tags and hidden TOTP fields`() {
        val (_, s) = vault()
        val csv = "folder,favorite,type,name,notes,login_uri,login_username,login_password,login_totp,tags\n" +
            "Work,1,login,Mail,,https://www.mail.example/x,me,pw1,JBSWY3DPEHPK3PXP,\"a; b\"\n" +
            "Work,,note,Secret note,just text,,,,,\n" +
            ",,login,,,shop.example,u2,pw2,,\n" +
            ",,,,,,,,,\n"
        val n = Csv.import(s, Csv.detect(csv), now)
        assertEquals(3, n)
        val work = s.collections().single { it.kind == "section" && it.name == "Work" }
        val mail = s.items().single { it.title == "Mail" }
        assertEquals(work.id, mail.sectionId)
        assertTrue(mail.favorite)
        assertEquals(listOf("a", "b"), mail.tags)
        assertTrue(mail.customFields.single().hidden)
        assertEquals(now, mail.passwordChangedAt)
        assertEquals(ItemType.NOTE, s.items().single { it.title == "Secret note" }.type)
        assertNotNull(s.items().singleOrNull { it.title == "shop.example" })
        assertEquals(1, s.collections().count { it.kind == "section" })
    }

    @Test
    fun `merge restore never overwrites and replace restore snapshots first`() {
        val (store, s) = vault()
        Items.save(s, VaultItem.empty(ItemType.LOGIN, "same", now).copy(title = "Same", password = "p"), now)
        Items.save(s, VaultItem.empty(ItemType.LOGIN, "changed", now).copy(title = "Changed", password = "old"), now)
        Items.save(s, VaultItem.empty(ItemType.LOGIN, "gone", now).copy(title = "Only in backup", password = "x"), now)
        val backup = Backup.open(Backup.create(s.header, s.encryptedRecords(), "backup-pass", "test", now), "backup-pass")

        Items.save(s, s.getItem("changed")!!.copy(password = "new"), now + 1)
        s.purgeItems(listOf("gone"))
        assertFailsWith<WrongPasswordError> { Backup.restoreMerge(s, backup, "wrong", null, now + 2) }
        val r = Backup.restoreMerge(s, backup, "Password-For-Tests-1", null, now + 2)
        assertEquals(1, r.added)
        assertEquals(1, r.copies)
        assertEquals(1, r.skipped)
        assertEquals("new", s.getItem("changed")!!.password, "the current version is kept")
        assertEquals("old", s.items().single { it.title == "Changed (restored copy)" }.password)
        assertNotNull(s.getItem("gone"))

        var snapshots = 0
        val snap = object : Backup.SnapshotStore {
            override fun createSnapshot(reason: String, keep: Int): Backup.Snapshot? {
                snapshots++
                return null
            }
            override fun listSnapshots() = emptyList<Backup.Snapshot>()
            override fun deleteSnapshot(id: String) = Unit
        }
        store.setLocal("sync:${s.vaultId}", kotlinx.serialization.json.JsonPrimitive(1))
        Backup.restoreReplace(store, snap, backup)
        assertEquals(1, snapshots)
        assertNull(store.getLocal("sync:${s.vaultId}"))
        assertEquals(backup.records.size, store.allRecords().size)
    }
}
