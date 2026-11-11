package com.damfizz.myvault.core

/**
 * Three-way record merge used by synchronization — port of `src/sync/merge.ts` (pure, deterministic).
 *
 *   L == R                 → unchanged
 *   only L / only R        → copy to the other side
 *   L == base, R changed   → pull R (unless R is an older revision → push L)
 *   R == base, L changed   → push L (bump rev above R when needed)
 *   both changed           → conflict, resolved by the sync engine without losing data
 */
object Merge {
    data class Conflict(val id: String, val local: EncryptedRecord, val remote: EncryptedRecord)
    data class Bump(val id: String, val minRev: Long)

    data class Result(
        val merged: LinkedHashMap<String, EncryptedRecord>,
        val pull: List<EncryptedRecord>,
        val bump: List<Bump>,
        val conflicts: List<Conflict>,
        val remoteChanged: Boolean,
        val pushed: Int,
    )

    fun mergeRecords(local: List<EncryptedRecord>, remote: List<EncryptedRecord>, base: Map<String, String>): Result {
        val l = local.associateBy { it.id }
        val r = remote.associateBy { it.id }
        val ids = (l.keys + r.keys).sortedWith(::compareJsStrings)
        val merged = LinkedHashMap<String, EncryptedRecord>()
        val pull = mutableListOf<EncryptedRecord>()
        val bump = mutableListOf<Bump>()
        val conflicts = mutableListOf<Conflict>()
        var remoteChanged = false
        var pushed = 0

        for (id in ids) {
            val lv = l[id]
            val rv = r[id]
            val b = base[id]
            when {
                lv != null && rv == null -> { merged[id] = lv; remoteChanged = true; pushed++ }
                rv != null && lv == null -> { merged[id] = rv; pull += rv }
                lv == null || rv == null -> Unit
                lv.vtag == rv.vtag -> merged[id] = lv
                lv.vtag == b -> if (rv.rev < lv.rev) {
                    merged[id] = lv; remoteChanged = true; pushed++
                } else {
                    merged[id] = rv; pull += rv
                }
                rv.vtag == b -> {
                    merged[id] = lv
                    if (lv.rev <= rv.rev) bump += Bump(id, rv.rev + 1)
                    remoteChanged = true
                    pushed++
                }
                else -> { conflicts += Conflict(id, lv, rv); merged[id] = lv; remoteChanged = true }
            }
        }
        return Result(merged, pull, bump, conflicts, remoteChanged, pushed)
    }

    fun baseFromRecords(records: Iterable<EncryptedRecord>): Map<String, String> = records.associate { it.id to it.vtag }
}
