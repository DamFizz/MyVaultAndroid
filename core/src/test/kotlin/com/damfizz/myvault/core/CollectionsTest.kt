package com.damfizz.myvault.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionsTest {
    private val now = 1_800_000_000_000L

    @BeforeTest fun fastKdf() = Crypto.setKdfCostForTesting(Fixtures.TEST_KDF)
    @AfterTest fun resetKdf() = Crypto.setKdfCostForTesting(null)

    private fun session() = VaultAccess(InMemoryVaultStore(), Clock { now }).createVault("Password-For-Tests-1")

    @Test
    fun `create, duplicate check, reorder and delete with move`() {
        val s = session()
        val cats = { s.collections().filter { it.kind == "category" } }
        assertEquals(8, cats().size)
        val games = Collections.newTemplate("section", s.collections(), now).copy(name = "  Games  ")
        Collections.save(s, games, now)
        assertEquals("Games", s.collections().first { it.id == games.id }.name)
        val steam = Collections.newTemplate("section", s.collections(), now).copy(name = "Steam", parentId = games.id)
        Collections.save(s, steam, now)
        assertTrue(Collections.isDuplicate(Collections.newTemplate("section", s.collections(), now).copy(name = "games"), s.collections()))
        assertFalse(Collections.isDuplicate(Collections.newTemplate("section", s.collections(), now).copy(name = "games", parentId = games.id), s.collections()))
        assertFalse(Collections.validParents(games, s.collections()).any { it.id == steam.id || it.id == games.id }, "no cycles")

        val first = cats()[0]
        val second = cats()[1]
        assertFalse(Collections.canMove(first, s.collections(), -1))
        Collections.move(s, first, +1, now)
        assertEquals(listOf(second.id, first.id), cats().take(2).map { it.id })

        Items.save(s, VaultItem.empty(ItemType.LOGIN, "i1", now).copy(title = "x", sectionId = steam.id, categoryId = first.id), now)
        assertEquals(1, Collections.itemCount(first, s.items()))
        s.deleteCollection(first.id, second.id)
        assertEquals(second.id, s.getItem("i1")!!.categoryId)
        s.deleteCollection(games.id, null)
        assertNull(s.collections().first { it.id == steam.id }.parentId, "child folders are re-parented, not deleted")
        assertEquals(steam.id, s.getItem("i1")!!.sectionId)
    }
}
