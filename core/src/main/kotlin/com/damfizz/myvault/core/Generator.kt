package com.damfizz.myvault.core

import kotlin.math.ln

/**
 * Password and passphrase generation — port of the web's `src/lib/generator.ts`.
 * All randomness comes from SecureRandom (no modulo bias); java.util.Random is never used.
 */
object Generator {
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/~"

    /** Characters easily confused with each other when read or typed by hand. */
    val AMBIGUOUS: Set<Char> = "Il1O0o|`'\"5S2Z8B".toSet()

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 128

    data class PasswordOptions(
        val length: Int = 20,
        val upper: Boolean = true,
        val lower: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = true,
        val avoidRepeats: Boolean = true,
    )

    data class PassphraseOptions(val words: Int = 5, val separator: String = "-", val capitalize: Boolean = true, val includeNumber: Boolean = true)

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun classPools(o: PasswordOptions): List<List<Char>> = buildList {
        for ((on, set) in listOf(o.upper to UPPER, o.lower to LOWER, o.digits to DIGITS, o.symbols to SYMBOLS)) {
            if (on) add(set.toList().filter { !o.excludeAmbiguous || it !in AMBIGUOUS })
        }
    }

    fun poolSize(o: PasswordOptions): Int = classPools(o).sumOf { it.size }

    /** Approximate entropy in bits of a password produced with these options. */
    fun passwordEntropy(o: PasswordOptions): Double = poolSize(o).let { if (it > 1) o.length * log2(it.toDouble()) else 0.0 }

    /**
     * A password with at least one character of every selected class. Rejection sampling over
     * uniformly random candidates, so every valid password is equally likely. With avoidRepeats no
     * character is reused when the pool is large enough; otherwise only consecutive repeats are avoided.
     */
    fun password(input: PasswordOptions): String {
        val o = input.copy(length = input.length.coerceIn(MIN_LENGTH, MAX_LENGTH))
        val pools = classPools(o)
        require(pools.isNotEmpty()) { "Select at least one character type" }
        val all = pools.flatten()
        val classes = pools.map { it.toSet() }
        val noReuse = o.avoidRepeats && all.size >= o.length * 2
        repeat(10_000) {
            val chars: List<Char> = if (noReuse) {
                val a = all.toMutableList()
                for (i in a.lastIndex downTo 1) {
                    val j = Encoding.secureRandomInt(i + 1)
                    val t = a[i]; a[i] = a[j]; a[j] = t
                }
                a.take(o.length)
            } else {
                val out = ArrayList<Char>(o.length)
                while (out.size < o.length) {
                    val c = all[Encoding.secureRandomInt(all.size)]
                    if (o.avoidRepeats && out.lastOrNull() == c) continue
                    out += c
                }
                out
            }
            if (o.length >= classes.size && !classes.all { set -> chars.any { it in set } }) return@repeat
            return chars.joinToString("")
        }
        throw IllegalStateException("Could not generate a password with these settings")
    }

    /** The 2048-word BIP-39 English list, the same one the web app uses. */
    val WORDLIST: List<String> by lazy {
        Generator::class.java.getResourceAsStream("/bip39-english.txt")!!.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
    }

    /** log2(2048) = 11 bits per word, plus the digit and its position. */
    fun passphraseEntropy(o: PassphraseOptions): Double {
        var bits = o.words * log2(WORDLIST.size.toDouble())
        if (o.includeNumber) bits += log2(10.0 * o.words)
        return bits
    }

    fun passphrase(input: PassphraseOptions): String {
        val count = input.words.coerceIn(3, 12)
        val words = MutableList(count) {
            val w = WORDLIST[Encoding.secureRandomInt(WORDLIST.size)]
            if (input.capitalize) w.replaceFirstChar { it.uppercase() } else w
        }
        if (input.includeNumber) {
            val idx = Encoding.secureRandomInt(count)
            words[idx] = words[idx] + Encoding.secureRandomInt(10)
        }
        return words.joinToString(input.separator)
    }
}
