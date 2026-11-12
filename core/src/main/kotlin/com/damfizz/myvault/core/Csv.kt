package com.damfizz.myvault.core

/**
 * CSV import from other password managers (Bitwarden, Chrome/Edge, Firefox, 1Password, LastPass,
 * KeePass, Proton Pass, generic) — port of the web's `src/lib/csv.ts`. Parsing happens in memory;
 * the plaintext file is never stored, and imported items are encrypted on save.
 */
object Csv {
    /** RFC 4180 parser: quoted fields, escaped quotes, CRLF/LF, newlines inside quotes. */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        val src = if (text.startsWith("﻿")) text.substring(1) else text
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row += field.toString(); field.setLength(0) }
                '\n', '\r' -> {
                    if (c == '\r' && i + 1 < src.length && src[i + 1] == '\n') i++
                    row += field.toString(); field.setLength(0)
                    rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.filter { r -> r.any { it.trim().isNotEmpty() } }
    }

    private val SYNONYMS = linkedMapOf(
        "title" to listOf("name", "title", "account", "item name"),
        "url" to listOf("url", "login_uri", "website", "uri", "web site", "login url"),
        "username" to listOf("username", "login_username", "user", "login", "user name", "login name"),
        "email" to listOf("email", "e-mail", "email address"),
        "password" to listOf("password", "login_password", "pass"),
        "notes" to listOf("notes", "note", "extra", "comments", "comment"),
        "folder" to listOf("folder", "grouping", "group", "vault", "collection"),
        "favorite" to listOf("favorite", "fav", "favourite", "starred"),
        "tags" to listOf("tags", "labels"),
        "totp" to listOf("totp", "login_totp", "otpauth", "otp", "one-time password"),
        "type" to listOf("type"),
    )

    class Preview(val headers: List<String>, val mapping: Map<String, Int>, val rows: List<List<String>>, val detectedFormat: String)

    fun detect(text: String): Preview {
        val all = parse(text)
        require(all.size >= 2) { "The CSV file has no entries." }
        val headers = all[0].map { it.trim() }
        val lower = headers.map { it.lowercase() }
        val mapping = LinkedHashMap<String, Int>()
        for ((field, names) in SYNONYMS) {
            val idx = lower.indexOfFirst { it in names }
            if (idx >= 0) mapping[field] = idx
        }
        require("password" in mapping || "notes" in mapping) { "Could not find a password or notes column. Is this a password-manager export?" }
        val joined = lower.joinToString(",")
        val format = when {
            "login_uri" in lower -> "Bitwarden"
            "grouping" in lower && "extra" in lower -> "LastPass"
            "httprealm" in lower -> "Firefox"
            "otpauth" in lower -> "1Password"
            "vault" in lower && "createtime" in lower -> "Proton Pass"
            joined == "name,url,username,password,note" || joined == "name,url,username,password" -> "Chrome / Edge"
            "group" in lower && "title" in lower -> "KeePass"
            else -> "Generic CSV"
        }
        return Preview(headers, mapping, all.drop(1), format)
    }

    class Result(val items: List<VaultItem>, val folders: List<String>)

    /** [folderId] maps a folder name from the file to an existing or new folder id (or null). */
    fun toItems(preview: Preview, folderId: (String) -> String?, now: Long, reprompt: Boolean = false): Result {
        fun get(row: List<String>, f: String): String = preview.mapping[f]?.let { row.getOrNull(it) }?.trim().orEmpty()
        val folders = LinkedHashSet<String>()
        val items = mutableListOf<VaultItem>()
        for (row in preview.rows) {
            val password = get(row, "password")
            val notes = get(row, "notes")
            val url = get(row, "url")
            var title = get(row, "title")
            if (title.isEmpty() && url.isNotEmpty()) title = Items.hostOf(url).ifEmpty { url }
            val username = get(row, "username")
            val email = get(row, "email")
            if (title.isEmpty() && username.isEmpty() && password.isEmpty() && notes.isEmpty()) continue
            val rawType = get(row, "type").lowercase()
            val type = if (rawType == "note" || rawType == "securenote" || (password.isEmpty() && username.isEmpty() && url.isEmpty() && notes.isNotEmpty())) ItemType.NOTE else ItemType.LOGIN
            val folder = get(row, "folder")
            if (folder.isNotEmpty()) folders += folder
            val totp = get(row, "totp")
            val fav = get(row, "favorite").lowercase()
            items += VaultItem.empty(type, Encoding.randomId(), now).copy(
                title = title.ifEmpty { "Imported item" },
                url = url.ifEmpty { null },
                username = username.ifEmpty { null },
                email = email.ifEmpty { null },
                password = password.ifEmpty { null },
                notes = notes.ifEmpty { null },
                tags = get(row, "tags").split(';', ',').map { it.trim() }.filter { it.isNotEmpty() },
                customFields = if (totp.isNotEmpty()) listOf(CustomField(Encoding.randomId(), "One-time password secret (TOTP)", totp, hidden = true)) else emptyList(),
                favorite = fav == "1" || fav == "true" || fav == "yes",
                reprompt = reprompt,
                sectionId = if (folder.isNotEmpty()) folderId(folder) else null,
                passwordChangedAt = if (password.isNotEmpty()) now else null,
            )
        }
        return Result(items, folders.toList())
    }

    /** Import into the unlocked vault: missing folders are created, every item is encrypted on save. */
    fun import(session: VaultSession, preview: Preview, now: Long): Int {
        val existing = session.collections().filter { it.kind == "section" }.associate { it.name.lowercase() to it.id }.toMutableMap()
        val created = mutableListOf<VaultCollection>()
        val result = toItems(preview, { name ->
            existing.getOrPut(name.lowercase()) {
                Collections.newTemplate("section", session.collections() + created, now).copy(name = name.take(60)).also { created += it }.id
            }
        }, now, session.settings().repromptByDefault)
        if (created.isNotEmpty()) session.saveCollections(created)
        if (result.items.isNotEmpty()) session.saveItems(result.items)
        return result.items.size
    }
}
