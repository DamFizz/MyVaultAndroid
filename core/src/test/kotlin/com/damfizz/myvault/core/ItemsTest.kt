package com.damfizz.myvault.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemsTest {
    private var now = 1_800_000_000_000L
    private val clock = Clock { now }

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    private fun session() = VaultAccess(InMemoryVaultStore(), clock).createVault("Password-For-Tests-1")

    @Test
    fun `only http and https URLs are openable`() {
        assertEquals("https://example.com", Items.safeHttpUrl("example.com"))
        assertEquals("http://example.com/a?b=1", Items.safeHttpUrl(" http://example.com/a?b=1 "))
        for (bad in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "data:text/html,x", "", null)) {
            assertNull(Items.safeHttpUrl(bad), "$bad")
        }
        assertEquals("example.com", Items.hostOf("https://www.Example.com/login"))
        assertEquals(setOf("title", "url"), Items.validate(VaultItem.empty(ItemType.LOGIN, "x", 0).copy(url = "javascript:x")))
    }

    @Test
    fun `saving keeps password history up to the vault limit`() {
        val s = session()
        s.saveSettings { it.copy(passwordHistoryLimit = 2) }
        val id = "login-1"
        Items.save(s, VaultItem.empty(ItemType.LOGIN, id, now).copy(title = "  Site  ", password = "p1"), now)
        assertEquals("Site", s.getItem(id)!!.title)
        assertEquals(now, s.getItem(id)!!.passwordChangedAt)
        for (p in listOf("p2", "p3", "p4")) {
            now += 1000
            Items.save(s, s.getItem(id)!!.copy(password = p), now)
        }
        val item = s.getItem(id)!!
        assertEquals(listOf("p3", "p2"), item.passwordHistory!!.map { it.password })
        now += 1000
        Items.save(s, item.copy(notes = "no password change"), now)
        assertEquals(2, s.getItem(id)!!.passwordHistory!!.size)
        assertEquals("Untitled", run { Items.save(s, VaultItem.empty(ItemType.NOTE, "n", now), now); s.getItem("n")!!.title })
    }

    @Test
    fun `trash, restore, archive, favourite, duplicate and undo`() {
        val s = session()
        Items.save(s, VaultItem.empty(ItemType.LOGIN, "a", now).copy(title = "A", password = "x"), now)
        Items.save(s, VaultItem.empty(ItemType.LOGIN, "b", now).copy(title = "B"), now)
        val before = Items.trash(s, listOf("a", "b"), now)
        assertTrue(s.items().all { it.trashedAt != null })
        Items.undo(s, before, now)
        assertTrue(s.items().all { it.trashedAt == null })
        Items.archive(s, listOf("a"), true, now)
        assertNotNull(s.getItem("a")!!.archivedAt)
        Items.restore(s, listOf("a"), now)
        assertNull(s.getItem("a")!!.archivedAt)
        Items.favorite(s, listOf("b"), true, now)
        assertTrue(s.getItem("b")!!.favorite)
        val copy = Items.duplicate(s, "a", now)!!
        assertEquals("A (copy)", copy.title)
        assertEquals("x", s.getItem(copy.id)!!.password)
        assertEquals(3, s.items().size)
    }

    @Test
    fun `expired trash is purged with tombstones`() {
        val s = session()
        s.saveSettings { it.copy(trashRetentionDays = 30) }
        Items.save(s, VaultItem.empty(ItemType.NOTE, "old", now).copy(title = "old"), now)
        Items.save(s, VaultItem.empty(ItemType.NOTE, "new", now).copy(title = "new"), now)
        Items.trash(s, listOf("old"), now)
        now += 31 * 86_400_000L
        Items.trash(s, listOf("new"), now)
        assertEquals(1, Items.purgeExpiredTrash(s, now))
        assertNull(s.getItem("old"))
        assertEquals("tombstone", s.allDecrypted().first { it.record.id == "old" }.payload.kind)
        assertNotNull(s.getItem("new"))
    }

    @Test
    fun `filters, search and sorting follow the web rules`() {
        val folder = VaultCollection(id = "f", kind = "section", name = "Games")
        val sub = VaultCollection(id = "f2", kind = "section", name = "Steam", parentId = "f")
        val cols = listOf(folder, sub)
        fun item(id: String, title: String, block: VaultItem.() -> VaultItem = { this }) = VaultItem.empty(ItemType.LOGIN, id, now).copy(title = title).block()
        val items = listOf(
            item("1", "beta") { copy(sectionId = "f2", username = "gamer", password = "hunter2", updatedAt = 5) },
            item("2", "Alpha") { copy(favorite = true, tags = listOf("work"), updatedAt = 9) },
            item("3", "gamma") { copy(type = ItemType.WIFI, ssid = "Home", archivedAt = 1) },
            item("4", "delta") { copy(trashedAt = 1) },
            item("5", "note") { copy(type = ItemType.NOTE, customFields = listOf(CustomField("c", "pin", "9911", hidden = true))) },
        )
        fun ids(f: ItemFilter, q: String = "", sort: SortBy = SortBy.TITLE) = Items.visible(items, f, q, cols, mapOf("1" to 7L), sort, now).map { it.id }
        assertEquals(listOf("2", "1", "5"), ids(ItemFilter.All))
        assertEquals(listOf("2"), ids(ItemFilter.Favorites))
        assertEquals(listOf("1"), ids(ItemFilter.Section("f")), "a folder includes its sub-folders")
        assertEquals(listOf("2"), ids(ItemFilter.Tag("work")))
        assertEquals(listOf("5"), ids(ItemFilter.Type("note")))
        assertEquals(listOf("3"), ids(ItemFilter.Archive))
        assertEquals(listOf("4"), ids(ItemFilter.Trash))
        assertEquals(listOf("2", "5"), ids(ItemFilter.Uncategorized))
        assertEquals(listOf("1"), ids(ItemFilter.RecentUsed))
        assertEquals(listOf("5", "2", "1"), ids(ItemFilter.All, sort = SortBy.UPDATED))
        assertEquals(listOf("1"), ids(ItemFilter.Favorites, "steam gamer"), "search looks across all active items, including folder names")
        assertEquals(listOf("3"), ids(ItemFilter.All, "home"), "search also finds archived items")
        assertTrue(ids(ItemFilter.All, "hunter2").isEmpty(), "passwords are never searched")
        assertFalse("5" in ids(ItemFilter.All, "9911"), "hidden custom fields are never searched")
    }
}
