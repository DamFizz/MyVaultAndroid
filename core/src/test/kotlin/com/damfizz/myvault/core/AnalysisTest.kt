package com.damfizz.myvault.core

import com.damfizz.myvault.core.Fixtures.num
import com.damfizz.myvault.core.Fixtures.obj
import com.damfizz.myvault.core.Fixtures.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strength, security analysis, generator and breach-check hashing must match the web app's outputs. */
class AnalysisTest {
    private val fx: JsonObject = Json.parseToJsonElement(
        AnalysisTest::class.java.getResource("/fixtures/analysis.json")!!.readText(Charsets.UTF_8),
    ).jsonObject

    private fun JsonObject.strings(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.groups(key: String) = this[key]!!.jsonArray.map { g -> (g as JsonArray).map { it.jsonPrimitive.content } }

    @Test
    fun `strength estimates and SHA-1 match the web for every sample`() {
        for (v in fx["strength"]!!.jsonArray.map { it.jsonObject }) {
            val pw = v.str("pw")
            val r = Strength.estimate(pw)
            assertEquals(v["score"]!!.jsonPrimitive.int, r.score, "score of '$pw'")
            assertEquals(v["bits"]!!.jsonPrimitive.int, r.bits, "bits of '$pw'")
            assertEquals(v.str("label"), r.label, "label of '$pw'")
            assertEquals(v.strings("warnings").toSet(), r.warnings.toSet(), "warnings of '$pw'")
            assertEquals(v.str("sha1"), BreachCheck.sha1Hex(pw), "sha1 of '$pw'")
        }
        assertTrue(Strength.isAcceptableMasterPassword("timber-glide-orbit-lemon"))
        assertFalse(Strength.isAcceptableMasterPassword("password123"))
    }

    @Test
    fun `security report matches the web`() {
        val s = fx.obj("security")
        val items = s["items"]!!.jsonArray.map { VaultItem.fromJson(it.jsonObject) }
        val r = SecurityAnalysis.analyze(items, s.num("oldPasswordDays").toInt(), s.num("now"))
        val w = s.obj("report")
        assertEquals(w["score"]!!.jsonPrimitive.int, r.score)
        assertEquals(w["total"]!!.jsonPrimitive.int, r.total)
        assertEquals(w["withPassword"]!!.jsonPrimitive.int, r.withPassword)
        assertEquals(w.strings("weak"), r.weak)
        assertEquals(w.groups("reused"), r.reused)
        assertEquals(w.groups("duplicates"), r.duplicates)
        assertEquals(w.strings("old"), r.old)
        assertEquals(w.strings("missingUrl"), r.missingUrl)
        assertEquals(w.strings("missingLogin"), r.missingLogin)
        assertEquals(w.strings("uncategorized"), r.uncategorized)
    }

    @Test
    fun `generator pools, entropy and word list match the web`() {
        val g = fx.obj("generator")
        val def = Generator.PasswordOptions()
        val short = def.copy(length = 12, symbols = false, excludeAmbiguous = false)
        assertEquals(g["defaultPool"]!!.jsonPrimitive.int, Generator.poolSize(def))
        assertEquals(g["defaultEntropy"]!!.jsonPrimitive.double, Generator.passwordEntropy(def), 1e-9)
        assertEquals(g["shortPool"]!!.jsonPrimitive.int, Generator.poolSize(short))
        assertEquals(g["shortEntropy"]!!.jsonPrimitive.double, Generator.passwordEntropy(short), 1e-9)
        assertEquals(g["passphraseEntropy"]!!.jsonPrimitive.double, Generator.passphraseEntropy(Generator.PassphraseOptions()), 1e-9)
        assertEquals(g["words"]!!.jsonPrimitive.int, Generator.WORDLIST.size)
        assertEquals(g.str("firstWord"), Generator.WORDLIST.first())
        assertEquals(g.str("lastWord"), Generator.WORDLIST.last())
    }

    @Test
    fun `generated passwords obey the options`() {
        repeat(300) {
            val pw = Generator.password(Generator.PasswordOptions())
            assertEquals(20, pw.length)
            assertTrue(pw.none { it in Generator.AMBIGUOUS }, pw)
            assertEquals(pw.length, pw.toSet().size, "no character reused: $pw")
            assertTrue(pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() } && pw.any { it.isDigit() } && pw.any { it in Generator.SYMBOLS }, pw)
        }
        repeat(200) {
            val pw = Generator.password(Generator.PasswordOptions(length = 40, upper = false, symbols = false, excludeAmbiguous = false))
            assertEquals(40, pw.length)
            assertTrue(pw.all { it in Generator.LOWER || it in Generator.DIGITS }, pw)
            assertTrue(pw.zipWithNext().none { (a, b) -> a == b }, "no consecutive repeats: $pw")
        }
        assertEquals(Generator.MIN_LENGTH, Generator.password(Generator.PasswordOptions(length = 1)).length)
        repeat(100) {
            val pp = Generator.passphrase(Generator.PassphraseOptions(words = 4, separator = ".", capitalize = true, includeNumber = true))
            val parts = pp.split('.')
            assertEquals(4, parts.size)
            assertEquals(1, parts.count { it.last().isDigit() })
            assertTrue(parts.all { it.first().isUpperCase() && it.trimEnd { c -> c.isDigit() }.lowercase() in Generator.WORDLIST }, pp)
        }
    }

    @Test
    fun `breach check sends only the hash prefix and matches the suffix locally`() {
        var asked = ""
        val hash = BreachCheck.sha1Hex("password")
        val body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n${hash.substring(5)}:42\r\nFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:0"
        assertEquals(42, BreachCheck.pwnedCount("password") { asked = it; body })
        assertEquals(hash.substring(0, 5), asked)
        assertEquals(5, asked.length)
        assertEquals(0, BreachCheck.pwnedCount("something-else-entirely") { body })
    }
}
