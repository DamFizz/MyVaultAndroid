package com.damfizz.myvault.core

import java.net.URI

/** What the list shows — port of the web's `Filter` (state/app.ts) and `ui/filtering.ts`. */
sealed interface ItemFilter {
    data object All : ItemFilter
    data object Favorites : ItemFilter
    data object RecentAdded : ItemFilter
    data object RecentUsed : ItemFilter
    /** "login", "note" or "other" (everything else) */
    data class Type(val type: String) : ItemFilter
    data class Category(val id: String) : ItemFilter
    data class Section(val id: String) : ItemFilter
    data class Tag(val tag: String) : ItemFilter
    data object Uncategorized : ItemFilter
    data object Archive : ItemFilter
    data object Trash : ItemFilter
}

enum class SortBy { TITLE, UPDATED, CREATED, USED }

/** Item rules shared by every screen — port of the web's `state/app.ts` item actions and `ui/items/helpers.ts`. */
object Items {
    val TYPES = listOf(ItemType.LOGIN, ItemType.NOTE, ItemType.WIFI, ItemType.RECOVERY_CODES, ItemType.LICENSE, ItemType.CUSTOM)
    private const val RECENT_MS = 30 * 86_400_000L

    /**
     * Only http(s) URLs may be opened. Anything else (javascript:, data:, file:, intent:, …) is rejected,
     * so a malicious imported or synced entry cannot launch something unexpected when tapped.
     */
    fun safeHttpUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val candidate = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return try {
            val u = URI(candidate)
            val scheme = u.scheme?.lowercase()
            if ((scheme == "https" || scheme == "http") && !u.host.isNullOrBlank()) u.toString() else null
        } catch (_: Exception) {
            null
        }
    }

    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            URI(if (url.contains("://")) url.trim() else "https://${url.trim()}").host?.removePrefix("www.")?.lowercase() ?: url.lowercase()
        } catch (_: Exception) {
            url.lowercase()
        }
    }

    fun subtitle(item: VaultItem): String = when (item.type) {
        ItemType.LOGIN -> item.username?.ifBlank { null } ?: item.email?.ifBlank { null } ?: hostOf(item.url)
        ItemType.WIFI -> item.ssid.orEmpty()
        ItemType.LICENSE -> item.product.orEmpty()
        ItemType.RECOVERY_CODES -> item.service.orEmpty()
        else -> ""
    }

    /** A blank item for the editor, pre-filled from the current view and the vault settings. */
    fun newTemplate(type: String, settings: VaultSettings, filter: ItemFilter, now: Long): VaultItem {
        var item = VaultItem.empty(type, Encoding.randomId(), now)
        when (filter) {
            is ItemFilter.Category -> item = item.copy(categoryId = filter.id)
            is ItemFilter.Section -> item = item.copy(sectionId = filter.id)
            is ItemFilter.Tag -> item = item.copy(tags = listOf(filter.tag))
            ItemFilter.Favorites -> item = item.copy(favorite = true)
            else -> Unit
        }
        if (item.categoryId == null && settings.defaultCategoryId != null) item = item.copy(categoryId = settings.defaultCategoryId)
        return item.copy(reprompt = settings.repromptByDefault)
    }

    /** Validation errors for the editor: "title" and/or "url". */
    fun validate(item: VaultItem): Set<String> = buildSet {
        if (item.title.isBlank()) add("title")
        if (!item.url.isNullOrBlank() && safeHttpUrl(item.url) == null) add("url")
    }

    /** Save with password-history bookkeeping, exactly like the web's saveItem(). */
    fun save(session: VaultSession, next: VaultItem, now: Long, untitled: String = "Untitled") {
        val prev = session.getItem(next.id)
        var item = next.copy(updatedAt = now, title = next.title.trim().ifEmpty { untitled })
        if (prev == null) {
            item = item.copy(createdAt = now, passwordChangedAt = if (!item.password.isNullOrEmpty()) now else item.passwordChangedAt)
        } else if ((prev.password ?: "") != (item.password ?: "")) {
            val limit = session.settings().passwordHistoryLimit
            val history = if (limit > 0 && !prev.password.isNullOrEmpty()) {
                (listOf(PasswordHistoryEntry(prev.password, prev.passwordChangedAt ?: prev.updatedAt)) + prev.passwordHistory.orEmpty()).take(limit)
            } else {
                emptyList()
            }
            item = item.copy(passwordChangedAt = now, passwordHistory = history)
        }
        session.saveItem(item)
    }

    /** Apply [patch] to the given items; returns the previous versions (for undo). */
    private fun patch(session: VaultSession, ids: Collection<String>, now: Long, patch: (VaultItem) -> VaultItem): List<VaultItem> {
        val before = ids.mapNotNull(session::getItem)
        if (before.isNotEmpty()) session.saveItems(before.map { patch(it).copy(updatedAt = now) })
        return before
    }

    fun trash(session: VaultSession, ids: Collection<String>, now: Long) = patch(session, ids, now) { it.copy(trashedAt = now) }
    fun restore(session: VaultSession, ids: Collection<String>, now: Long) = patch(session, ids, now) { it.copy(trashedAt = null, archivedAt = null) }
    fun archive(session: VaultSession, ids: Collection<String>, archived: Boolean, now: Long) =
        patch(session, ids, now) { it.copy(archivedAt = if (archived) now else null) }
    fun favorite(session: VaultSession, ids: Collection<String>, value: Boolean, now: Long) = patch(session, ids, now) { it.copy(favorite = value) }
    fun move(session: VaultSession, ids: Collection<String>, now: Long, categoryId: String?, sectionId: String?, keepCategory: Boolean = false, keepSection: Boolean = false) =
        patch(session, ids, now) { it.copy(categoryId = if (keepCategory) it.categoryId else categoryId, sectionId = if (keepSection) it.sectionId else sectionId) }

    /** Put back the versions returned by an action above (undo), keeping revisions moving forward. */
    fun undo(session: VaultSession, before: List<VaultItem>, now: Long) = session.saveItems(before.map { it.copy(updatedAt = now) })

    fun duplicate(session: VaultSession, id: String, now: Long, suffix: String = "(copy)"): VaultItem? {
        val src = session.getItem(id) ?: return null
        val copy = src.copy(
            id = Encoding.randomId(), title = "${src.title} $suffix", createdAt = now, updatedAt = now,
            favorite = false, archivedAt = null, trashedAt = null, passwordHistory = emptyList(),
        )
        session.saveItem(copy)
        return copy
    }

    /** Permanently delete items that have been in the trash longer than the vault's retention. */
    fun purgeExpiredTrash(session: VaultSession, now: Long): Int {
        val days = session.settings().trashRetentionDays
        if (days <= 0) return 0
        val cutoff = now - days * 86_400_000L
        val expired = session.items().filter { it.trashedAt != null && it.trashedAt < cutoff }.map { it.id }
        if (expired.isNotEmpty()) session.purgeItems(expired)
        return expired.size
    }

    fun allTags(items: List<VaultItem>): List<String> = items.flatMap { it.tags }.distinct().sortedBy { it.lowercase() }

    // ------------------------------------------------------------------ filtering, search, sorting

    fun descendantSectionIds(collections: List<VaultCollection>, rootId: String): Set<String> {
        val out = mutableSetOf(rootId)
        var added = true
        while (added) {
            added = false
            for (c in collections) {
                if (c.kind == "section" && c.parentId != null && c.parentId in out && out.add(c.id)) added = true
            }
        }
        return out
    }

    fun matchesFilter(item: VaultItem, filter: ItemFilter, usage: Map<String, Long>, sectionScope: Set<String>?, now: Long): Boolean {
        if (filter == ItemFilter.Trash) return item.trashedAt != null
        if (item.trashedAt != null) return false
        if (filter == ItemFilter.Archive) return item.archivedAt != null
        if (item.archivedAt != null) return false
        return when (filter) {
            ItemFilter.All -> true
            ItemFilter.Favorites -> item.favorite
            ItemFilter.RecentAdded -> now - item.createdAt < RECENT_MS
            ItemFilter.RecentUsed -> usage.containsKey(item.id)
            is ItemFilter.Type -> if (filter.type == "other") item.type != ItemType.LOGIN && item.type != ItemType.NOTE else item.type == filter.type
            is ItemFilter.Category -> item.categoryId == filter.id
            is ItemFilter.Section -> item.sectionId != null && item.sectionId in (sectionScope ?: setOf(filter.id))
            is ItemFilter.Tag -> filter.tag in item.tags
            ItemFilter.Uncategorized -> item.categoryId == null && item.sectionId == null
            else -> false
        }
    }

    /** Search across non-secret fields only: passwords and hidden custom fields are never matched. */
    fun matchesSearch(item: VaultItem, query: String, collections: List<VaultCollection>): Boolean {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val cat = collections.find { it.id == item.categoryId }?.name
        val sec = collections.find { it.id == item.sectionId }?.name
        val hay = (listOf(item.title, item.url, hostOf(item.url), item.username, item.email, item.ssid, item.product, item.service, item.notes, cat, sec) +
            item.tags + item.customFields.filter { !it.hidden }.map { "${it.label} ${it.value}" })
            .filter { !it.isNullOrEmpty() }.joinToString(" ").lowercase()
        return terms.all { it in hay }
    }

    fun sort(items: List<VaultItem>, sortBy: SortBy, usage: Map<String, Long>, filter: ItemFilter): List<VaultItem> {
        val by = when (filter) {
            ItemFilter.RecentUsed -> SortBy.USED
            ItemFilter.RecentAdded -> SortBy.CREATED
            else -> sortBy
        }
        return when (by) {
            SortBy.TITLE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SortBy.UPDATED -> items.sortedByDescending { it.updatedAt }
            SortBy.CREATED -> items.sortedByDescending { it.createdAt }
            SortBy.USED -> items.sortedByDescending { usage[it.id] ?: 0 }
        }
    }

    /** A search looks across all active items regardless of the current view (except trash and archive). */
    fun visible(items: List<VaultItem>, filter: ItemFilter, search: String, collections: List<VaultCollection>, usage: Map<String, Long>, sortBy: SortBy, now: Long): List<VaultItem> {
        val scope = (filter as? ItemFilter.Section)?.let { descendantSectionIds(collections, it.id) }
        val q = search.trim()
        val base = if (q.isNotEmpty() && filter != ItemFilter.Trash && filter != ItemFilter.Archive) {
            items.filter { it.trashedAt == null }
        } else {
            items.filter { matchesFilter(it, filter, usage, scope, now) }
        }
        return sort(base.filter { matchesSearch(it, q, collections) }, sortBy, usage, filter)
    }
}
