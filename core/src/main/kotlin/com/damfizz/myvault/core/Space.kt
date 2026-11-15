package com.damfizz.myvault.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * "My Space": personal essentials kept next to the password vault. First type: bank accounts and
 * payment QR codes.
 *
 * Storage: each entry is an encrypted vault record of kind `"space"`; each QR image is its own
 * encrypted record of kind `"asset"`. Both use the vault's record key and AAD binding, so they sync
 * and back up exactly like items. Older clients (and the web app today) ignore record kinds they do
 * not know, but keep them in the vault and the sync file.
 */
object SpaceType {
    /** Bank account: number and/or an attached payment QR. */
    const val BANK = "bank"
    /** A payment QR on its own (no account number). */
    const val QR = "qr"
}

@Serializable
data class SpaceEntry(
    val id: String,
    val type: String = SpaceType.BANK,
    /** Nickname, e.g. "Maybank Personal". */
    val title: String = "",
    /** Id from [Banks.ALL], or "custom". */
    val bankId: String? = null,
    /** Display name (kept even for known banks, so renames in the catalogue never change saved data). */
    val bankName: String? = null,
    val holder: String? = null,
    /** Always a string: leading zeros and the exact digits are preserved. */
    val accountNumber: String? = null,
    val duitNowId: String? = null,
    /** "savings" | "current" | "other" | free text */
    val accountType: String? = null,
    val notes: String? = null,
    /** Hex colour, e.g. "#2563eb". */
    val color: String? = null,
    val icon: String? = null,
    val favorite: Boolean = false,
    /** Id of the [SpaceAsset] record with the payment QR, if any. */
    val qrAssetId: String? = null,
    val order: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject = VaultJson.encodePreserving(serializer(), this, raw)

    /** What the card shows as its name. */
    val displayName: String get() = title.ifBlank { bankName.orEmpty() }.ifBlank { if (type == SpaceType.QR) "Payment QR" else "Bank account" }

    companion object {
        fun fromJson(obj: JsonObject): SpaceEntry = VaultJson.sparse.decodeFromJsonElement(serializer(), obj).copy(raw = obj)
    }
}

/** An encrypted image (the original bytes as imported, base64 inside the encrypted record). */
@Serializable
data class SpaceAsset(
    val id: String,
    val ownerId: String? = null,
    val mime: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
    val data: String,
    /** SHA-256 of the bytes (hex), to detect corruption and identical re-imports. */
    val sha256: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    @Transient val raw: JsonObject? = null,
) {
    fun toJson(): JsonObject = VaultJson.encodePreserving(serializer(), this, raw)

    fun bytes(): ByteArray = Base64.getDecoder().decode(data)

    /** True when the stored bytes still match their checksum. */
    fun intact(): Boolean = sha256 == null || runCatching { Space.sha256Hex(bytes()) == sha256 }.getOrDefault(false)

    companion object {
        fun fromJson(obj: JsonObject): SpaceAsset = VaultJson.sparse.decodeFromJsonElement(serializer(), obj).copy(raw = obj)

        fun of(id: String, ownerId: String?, mime: String, width: Int, height: Int, bytes: ByteArray, now: Long) = SpaceAsset(
            id = id, ownerId = ownerId, mime = mime, width = width, height = height,
            data = Base64.getEncoder().encodeToString(bytes), sha256 = Space.sha256Hex(bytes), createdAt = now, updatedAt = now,
        )
    }
}

/** Malaysian banks offered in the picker. One list, used everywhere (UI never hardcodes names). */
object Banks {
    data class Bank(val id: String, val name: String, val short: String)

    const val CUSTOM = "custom"

    val ALL: List<Bank> = listOf(
        Bank("maybank", "Maybank", "MBB"),
        Bank("cimb", "CIMB Bank", "CIMB"),
        Bank("publicbank", "Public Bank", "PBB"),
        Bank("rhb", "RHB Bank", "RHB"),
        Bank("hongleong", "Hong Leong Bank", "HLB"),
        Bank("ambank", "AmBank", "AMB"),
        Bank("bankislam", "Bank Islam", "BIMB"),
        Bank("bankrakyat", "Bank Rakyat", "BKR"),
        Bank("bsn", "BSN", "BSN"),
        Bank("affin", "Affin Bank", "AFF"),
        Bank("alliance", "Alliance Bank", "ABMB"),
        Bank("hsbc", "HSBC", "HSBC"),
        Bank("ocbc", "OCBC", "OCBC"),
        Bank("standardchartered", "Standard Chartered", "SC"),
        Bank("bankmuamalat", "Bank Muamalat", "BMMB"),
        Bank("agrobank", "Agrobank", "AGRO"),
        Bank("uob", "UOB", "UOB"),
        Bank("tng", "Touch 'n Go eWallet", "TNG"),
    )

    fun byId(id: String?): Bank? = ALL.find { it.id == id }

    /** Up to 3-4 letters for the generic bank badge (no logos). */
    fun badge(e: SpaceEntry): String = byId(e.bankId)?.short
        ?: e.bankName?.split(' ', '-')?.filter { it.isNotBlank() }?.take(3)?.joinToString("") { it.first().uppercase() }?.takeIf { it.isNotEmpty() }
        ?: "QR"
}

object Space {
    /** Strip spaces and dashes typed for readability; everything else (leading zeros!) is kept. */
    fun normalizeAccountNumber(input: String): String = input.filterNot { it.isWhitespace() || it == '-' }

    /** "•••• 1234" (or the whole number when it is 4 characters or fewer). */
    fun masked(number: String?): String {
        val n = number.orEmpty()
        return if (n.length <= 4) n else "•••• ${n.takeLast(4)}"
    }

    /** Groups of 4 for reading only. Copy/share always use the original string. */
    fun grouped(number: String?): String = number.orEmpty().chunked(4).joinToString(" ")

    data class ShareOptions(val includeBank: Boolean = true, val includeHolder: Boolean = true)

    /**
     * Plain-text message with only what the user chose: bank, holder, account number (exact).
     * Notes, DuitNow ID and other metadata are never added implicitly.
     */
    fun shareText(e: SpaceEntry, o: ShareOptions = ShareOptions(), labels: Labels = Labels.EN): String = buildList {
        if (o.includeBank && !e.bankName.isNullOrBlank()) add("${labels.bank}: ${e.bankName}")
        if (o.includeHolder && !e.holder.isNullOrBlank()) add("${labels.holder}: ${e.holder}")
        if (!e.accountNumber.isNullOrBlank()) add("${labels.number}: ${e.accountNumber}")
    }.joinToString("\n")

    data class Labels(val bank: String, val holder: String, val number: String) {
        companion object {
            val EN = Labels("Bank", "Account holder", "Account number")
        }
    }

    fun search(entries: List<SpaceEntry>, query: String): List<SpaceEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        val digits = Space.normalizeAccountNumber(q)
        return entries.filter { e ->
            listOfNotNull(e.title, e.bankName, e.holder, e.duitNowId, e.notes).any { it.lowercase().contains(q) } ||
                (digits.isNotEmpty() && e.accountNumber?.contains(digits) == true)
        }
    }

    /** Favourites first, then manual order, then name. */
    fun sorted(entries: List<SpaceEntry>): List<SpaceEntry> =
        entries.sortedWith(compareByDescending<SpaceEntry> { it.favorite }.thenBy { it.order }.thenBy { it.displayName.lowercase() })

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
