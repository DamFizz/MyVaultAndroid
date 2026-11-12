package com.damfizz.myvault.core

/**
 * Compares app versions such as "0.2.1-alpha", "v0.3.0", "1.0.0-beta.2" (semantic-versioning order:
 * numbers first, and a release without a pre-release tag ranks above the same numbers with one).
 */
object Versions {
    private class Parsed(val numbers: List<Int>, val pre: List<String>)

    private fun parse(v: String): Parsed {
        val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val main = clean.substringBefore('-')
        val pre = if ('-' in clean) clean.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        val numbers = main.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(maxOf(0, 3 - it.size)) { 0 } }
        return Parsed(numbers, pre)
    }

    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.numbers.size, y.numbers.size)) {
            val c = (x.numbers.getOrElse(i) { 0 }).compareTo(y.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (x.pre.isEmpty() || y.pre.isEmpty()) return y.pre.size.coerceAtMost(1) - x.pre.size.coerceAtMost(1)
        for (i in 0 until maxOf(x.pre.size, y.pre.size)) {
            val p = x.pre.getOrNull(i) ?: return -1
            val q = y.pre.getOrNull(i) ?: return 1
            val pn = p.toIntOrNull()
            val qn = q.toIntOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0
}
