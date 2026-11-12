package com.damfizz.myvault.core

/** Category / folder rules — port of the web's `views/OrganizeView.tsx` logic. */
object Collections {
    /** Icon names shared with the web app (lucide names); unknown names fall back to "folder". */
    val ICONS = listOf(
        "folder", "message-circle", "users", "gamepad", "trophy", "landmark", "wallet", "credit-card", "piggy-bank", "briefcase",
        "building", "graduation-cap", "book", "mail", "at-sign", "shopping-bag", "gift", "clapperboard", "music", "camera", "globe",
        "code", "terminal", "server", "database", "laptop", "monitor", "smartphone", "wifi", "home", "car", "plane", "heart", "flame",
        "zap", "lightbulb", "palette", "package", "user", "key", "shield", "lock",
    )
    val COLORS = listOf(
        "#2dd4bf", "#22d3ee", "#3b82f6", "#6366f1", "#8b5cf6", "#a855f7", "#ec4899", "#f43f5e",
        "#ef4444", "#f97316", "#f59e0b", "#eab308", "#84cc16", "#10b981", "#64748b", "#94a3b8",
    )

    fun newTemplate(kind: String, existing: List<VaultCollection>, now: Long) = VaultCollection(
        id = Encoding.randomId(), kind = kind, name = "",
        icon = if (kind == "section") "folder" else "key",
        color = COLORS[existing.size % COLORS.size],
        parentId = null, pinned = false, order = existing.count { it.kind == kind },
        createdAt = now, updatedAt = now,
        raw = kotlinx.serialization.json.JsonObject(mapOf("parentId" to kotlinx.serialization.json.JsonNull)),
    )

    /** Same kind + same parent + same name (ignoring case) already exists. */
    fun isDuplicate(c: VaultCollection, all: List<VaultCollection>): Boolean =
        all.any { it.id != c.id && it.kind == c.kind && it.name.trim().equals(c.name.trim(), ignoreCase = true) && it.parentId == c.parentId }

    /** A folder may not be moved inside itself or one of its own sub-folders. */
    fun validParents(c: VaultCollection, all: List<VaultCollection>): List<VaultCollection> {
        val blocked = Items.descendantSectionIds(all, c.id)
        return all.filter { it.kind == "section" && it.id !in blocked }
    }

    fun save(session: VaultSession, c: VaultCollection, now: Long) =
        session.saveCollections(listOf(c.copy(name = c.name.trim().take(60), updatedAt = now)))

    private fun siblings(c: VaultCollection, all: List<VaultCollection>) =
        all.filter { it.kind == c.kind && (c.kind == "category" || it.parentId == c.parentId) }.sortedWith(compareBy<VaultCollection> { it.order }.thenBy { it.name })

    fun canMove(c: VaultCollection, all: List<VaultCollection>, dir: Int): Boolean {
        val s = siblings(c, all)
        val i = s.indexOfFirst { it.id == c.id }
        return i >= 0 && i + dir in s.indices
    }

    /** Swap with the previous (dir = -1) or next (dir = +1) sibling; only changed records are re-saved. */
    fun move(session: VaultSession, c: VaultCollection, dir: Int, now: Long) {
        val all = session.collections()
        val s = siblings(c, all)
        val i = s.indexOfFirst { it.id == c.id }
        if (i < 0 || i + dir !in s.indices) return
        val order = s.indices.toMutableList()
        order[i] = i + dir
        order[i + dir] = i
        val changed = s.mapIndexedNotNull { idx, x -> if (x.order != order[idx]) x.copy(order = order[idx], updatedAt = now) else null }
        if (changed.isNotEmpty()) session.saveCollections(changed)
    }

    fun itemCount(c: VaultCollection, items: List<VaultItem>): Int =
        items.count { it.trashedAt == null && (if (c.kind == "category") it.categoryId == c.id else it.sectionId == c.id) }
}
