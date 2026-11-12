package com.damfizz.myvault.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Local, heuristic password strength estimate — port of the web's `src/lib/strength.ts`, kept
 * identical so both apps show the same score. An estimate, not a guarantee; nothing is sent anywhere.
 */
object Strength {
    private val COMMON = (
        "123456 123456789 12345678 password qwerty 12345 qwerty123 1q2w3e 111111 1234567 123123 1234567890 " +
            "000000 abc123 password1 iloveyou 1234 qwertyuiop 654321 555555 lovely 7777777 welcome 888888 princess " +
            "dragon 123qwe sunshine 666666 football monkey charlie aa123456 donald password123 qwe123 admin letmein " +
            "baseball master shadow michael superman batman trustno1 hello freedom whatever starwars passw0rd " +
            "zaq12wsx login access flower hottie loveme jordan23 mustang 121212 asdfgh asdfghjkl zxcvbnm 112233 " +
            "secret google samsung computer internet jesus ninja azerty solo abcdef abcd1234 changeme default " +
            "root toor test test123 guest pass 1qaz2wsx q1w2e3r4 summer winter spring autumn liverpool chelsea " +
            "arsenal killer pokemon minecraft fortnite roblox naruto anime soccer hunter ranger buster thomas " +
            "jennifer jessica ashley daniel andrew joshua michelle matthew hannah amanda nicole family friends " +
            "malaysia kualalumpur sayang cinta rahsia katalaluan password! p@ssw0rd p@ssword admin123 user123"
        ).split(' ').toSet()

    private val DICTIONARY = listOf(
        "password", "passw0rd", "admin", "welcome", "love", "dragon", "monkey", "qwerty", "letmein", "football",
        "baseball", "sunshine", "master", "shadow", "princess", "secret", "login", "hello", "summer", "winter",
        "google", "facebook", "instagram", "apple", "samsung", "computer", "internet", "super", "star", "angel",
        "sayang", "cinta", "rahsia", "malaysia", "iloveyou", "freedom", "pokemon", "gaming", "player", "user",
    )

    private val KEYBOARD_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "1qaz2wsx3edc4rfv", "qazwsxedc")
    val LABELS = listOf("veryWeak", "weak", "fair", "strong", "veryStrong")

    data class Result(
        /** 0 very weak … 4 very strong */
        val score: Int,
        /** estimated effective entropy in bits */
        val bits: Int,
        val label: String,
        /** common, short, repeats, sequence, keyboard, dictionary, year, singleClass */
        val warnings: List<String>,
    )

    private fun charsetSize(pw: String): Int {
        var size = 0
        if (pw.any { it in 'a'..'z' }) size += 26
        if (pw.any { it in 'A'..'Z' }) size += 26
        if (pw.any { it in '0'..'9' }) size += 10
        if (pw.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }) size += 33
        if (pw.any { it.code > 0x7f }) size += 100
        return maxOf(size, 1)
    }

    private fun deleet(s: String): String = buildString {
        for (ch in s) append(
            when (ch) {
                '@', '4' -> 'a'
                '3' -> 'e'
                '1', '!', '|' -> 'i'
                '0' -> 'o'
                '$', '5' -> 's'
                '7' -> 't'
                else -> ch
            },
        )
    }

    private fun isAlnum(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code

    fun estimate(password: String): Result {
        if (password.isEmpty()) return Result(0, 0, "veryWeak", emptyList())
        val warnings = LinkedHashSet<String>()
        val lower = password.lowercase()
        val stripped = lower.replace(Regex("[^a-z]+$"), "").replace(Regex("^[^a-z]+"), "")
        if (lower in COMMON || deleet(lower) in COMMON || (stripped.length >= 4 && stripped in COMMON && password.length < 12)) {
            return Result(0, 8, "veryWeak", listOf("common"))
        }

        val perChar = ln(charsetSize(password).toDouble()) / ln(2.0)
        val chars = password.codePoints().toArray()
        val costs = DoubleArray(chars.size) { perChar }
        fun cap(k: Int, v: Double) { if (k in costs.indices) costs[k] = minOf(costs[k], v) }
        fun put(k: Int, v: Double) { if (k in costs.indices) costs[k] = v }

        // Repeats and simple sequences cost very little to guess
        for (i in 1 until chars.size) {
            val a = chars[i - 1]
            val b = chars[i]
            if (a == b) {
                costs[i] = 1.0
                warnings += "repeats"
            } else if (abs(a - b) == 1 && isAlnum(b) && isAlnum(a)) {
                cap(i, 2.0)
                if (i >= 2 && abs(chars[i - 2] - a) == 1) warnings += "sequence"
            }
        }

        // Keyboard walks of length ≥ 4
        for (row in KEYBOARD_ROWS) {
            for (len in minOf(row.length, lower.length) downTo 4) {
                for (start in 0..row.length - len) {
                    val walk = row.substring(start, start + len)
                    var idx = lower.indexOf(walk)
                    if (idx < 0) idx = lower.indexOf(walk.reversed())
                    if (idx >= 0) {
                        warnings += "keyboard"
                        for (k in idx + 1 until idx + len) cap(k, 1.0)
                    }
                }
            }
        }

        // Dictionary words (including leetspeak variants)
        val normalized = deleet(lower)
        for (word in DICTIONARY) {
            val idx = normalized.indexOf(word)
            if (idx >= 0) {
                warnings += "dictionary"
                for (k in idx until idx + word.length) put(k, 0.0)
                put(idx, 11.0) // ~ choosing a word from a few thousand common words
            }
        }

        // Years
        Regex("(19|20)\\d{2}").find(password)?.let { m ->
            warnings += "year"
            for (k in m.range.first until m.range.first + 4) put(k, 0.0)
            put(m.range.first, 7.0)
        }

        var bits = costs.sum()
        val classes = listOf<(Char) -> Boolean>({ it in 'a'..'z' }, { it in 'A'..'Z' }, { it in '0'..'9' }, { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') })
            .count { test -> password.any(test) }
        if (classes == 1 && password.length < 16) warnings += "singleClass"
        if (password.length < 8) {
            warnings += "short"
            bits = minOf(bits, 30.0)
        } else if (password.length < 12) {
            warnings += "short"
        }

        val rounded = Math.floor(bits + 0.5).toInt() // JS Math.round
        val score = when {
            rounded < 28 -> 0
            rounded < 40 -> 1
            rounded < 60 -> 2
            rounded < 80 -> 3
            else -> 4
        }
        return Result(score, rounded, LABELS[score], warnings.toList())
    }

    /** Minimum standard for a master password: fair strength and at least 10 characters. */
    fun isAcceptableMasterPassword(password: String): Boolean = password.length >= 10 && estimate(password).score >= 2
}

/** Local security analysis — port of the web's `src/lib/security-analysis.ts`. Nothing leaves the device. */
object SecurityAnalysis {
    data class Report(
        /** 0–100 */
        val score: Int,
        val total: Int,
        val withPassword: Int,
        val weak: List<String>,
        val reused: List<List<String>>,
        val duplicates: List<List<String>>,
        val old: List<String>,
        val missingUrl: List<String>,
        val missingLogin: List<String>,
        val uncategorized: List<String>,
    )

    fun analyze(items: List<VaultItem>, oldPasswordDays: Int, now: Long): Report {
        val active = items.filter { it.trashedAt == null && it.archivedAt == null }
        val logins = active.filter { it.type == ItemType.LOGIN }
        val withPassword = logins.filter { !it.password.isNullOrEmpty() }

        val weak = withPassword.filter { Strength.estimate(it.password!!).score < 3 }.map { it.id }
        val reused = withPassword.groupBy { it.password!! }.values.filter { it.size > 1 }.map { g -> g.map { it.id } }

        val byAccount = LinkedHashMap<String, MutableList<String>>()
        for (i in logins) {
            val who = (i.username?.ifEmpty { null } ?: i.email.orEmpty()).lowercase()
            val host = Items.hostOf(i.url).ifEmpty { i.title.lowercase().trim() }
            if (who.isEmpty() || host.isEmpty()) continue
            byAccount.getOrPut("$host|$who") { mutableListOf() } += i.id
        }
        val duplicates = byAccount.values.filter { it.size > 1 }

        val cutoff = now - oldPasswordDays * 86_400_000L
        val old = withPassword.filter { (it.passwordChangedAt ?: it.createdAt) < cutoff }.map { it.id }
        val missingUrl = logins.filter { it.url.isNullOrEmpty() }.map { it.id }
        val missingLogin = logins.filter { it.username.isNullOrEmpty() && it.email.isNullOrEmpty() }.map { it.id }
        val uncategorized = active.filter { it.categoryId.isNullOrEmpty() && it.sectionId.isNullOrEmpty() }.map { it.id }

        var score = 100.0
        if (withPassword.isNotEmpty()) {
            score -= weak.size.toDouble() / withPassword.size * 45
            score -= reused.sumOf { it.size }.toDouble() / withPassword.size * 35
            score -= old.size.toDouble() / withPassword.size * 10
        }
        if (logins.isNotEmpty()) score -= duplicates.sumOf { it.size - 1 }.toDouble() / logins.size * 10
        return Report(maxOf(0, Math.floor(score + 0.5).toInt()), active.size, withPassword.size, weak, reused, duplicates, old, missingUrl, missingLogin, uncategorized)
    }
}

/**
 * Optional breach check against Have I Been Pwned "Pwned Passwords" with k-anonymity — port of
 * `src/lib/hibp.ts`. Only the first 5 hex characters of the SHA-1 hash are sent; the rest is compared
 * locally. The password itself never leaves the device. Runs only when the user starts it.
 */
object BreachCheck {
    const val URL_PREFIX = "https://api.pwnedpasswords.com/range/"

    fun sha1Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(Encoding.utf8(value)).joinToString("") { "%02X".format(it.toInt() and 0xff) }

    /** [fetchRange] downloads `URL_PREFIX + prefix` (with the "Add-Padding: true" header) and returns the body. */
    fun pwnedCount(password: String, fetchRange: (prefix: String) -> String): Int {
        val hash = sha1Hex(password)
        val suffix = hash.substring(5)
        for (line in fetchRange(hash.substring(0, 5)).split('\n')) {
            val parts = line.trim().split(':')
            if (parts.size >= 2 && parts[0] == suffix) return parts[1].trim().toIntOrNull() ?: 0
        }
        return 0
    }
}
