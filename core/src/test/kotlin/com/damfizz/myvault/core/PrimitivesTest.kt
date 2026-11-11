package com.damfizz.myvault.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrimitivesTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `RFC 6238 SHA-1 vectors`() {
        val secret = Encoding.base32Encode("12345678901234567890".toByteArray())
        val cases = mapOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471", 1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130")
        for ((t, code) in cases) assertEquals(code, Totp.totp(secret, t * 1000, digits = 8), "t=$t")
    }

    @Test
    fun `RFC 5869 test case 3 (empty salt and info)`() {
        // HKDF with an empty salt; our hkdf() takes UTF-8 strings, so use the raw helper path via empty strings.
        val okm = Crypto.hkdf(ByteArray(22) { 0x0b }, info = "", salt = "", length = 42)
        assertContentEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    @Test
    fun `TOTP verification window and replay protection`() {
        val secret = Totp.generateSecret()
        val now = 1_800_000_000_000L
        val code = Totp.totp(secret, now)
        val ok = Totp.verify(secret, code, nowMs = now)
        assertTrue(ok.ok)
        assertFalse(Totp.verify(secret, code, nowMs = now, lastUsedStep = ok.step).ok, "replayed code must fail")
        assertTrue(Totp.verify(secret, code, nowMs = now + 30_000).ok, "one step of drift is accepted")
        assertFalse(Totp.verify(secret, code, nowMs = now + 90_000).ok)
        assertEquals(-90L, Totp.detectClockDrift(secret, code, nowMs = now + 90_000))
        assertFalse(Totp.verify(secret, "12345", nowMs = now).ok)
        assertTrue(Totp.verify(secret, code.substring(0, 3) + " " + code.substring(3), nowMs = now).ok)
    }

    @Test
    fun `base32, base64 and recovery key formats`() {
        for (n in 0..40) {
            val b = Encoding.randomBytes(n)
            assertContentEquals(b, Encoding.base32Decode(Encoding.base32Encode(b)))
            assertContentEquals(b, Encoding.fromBase64Url(Encoding.toBase64Url(b)))
        }
        assertFailsWith<IllegalArgumentException> { Encoding.fromBase64("not base64!") }
        val rk = Keys.generateRecoveryKey()
        assertTrue(Regex("^([A-Z2-7]{4}-){7}[A-Z2-7]{4}$").matches(rk.display))
        assertContentEquals(rk.raw, Keys.parseRecoveryKey(rk.display.lowercase().replace("-", " ")))
        Keys.generateRecoveryCodes().forEach { assertTrue(Regex("^[A-Z2-7]{5}-[A-Z2-7]{5}$").matches(it)) }
        assertEquals(22, Encoding.randomId().length)
    }

    @Test
    fun `AES-GCM rejects wrong context and tampering`() {
        val key = AesKey(Encoding.randomBytes(32))
        val sealed = Crypto.seal(key, "hello".toByteArray(), "aad/1")
        assertEquals("hello", String(Crypto.open(key, sealed, "aad/1")))
        assertFailsWith<DecryptionError> { Crypto.open(key, sealed, "aad/2") }
        val ct = Encoding.fromBase64(sealed.ct).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<DecryptionError> { Crypto.open(key, sealed.copy(ct = Encoding.toBase64(ct)), "aad/1") }
        assertFailsWith<DecryptionError> { Crypto.open(AesKey(Encoding.randomBytes(32)), sealed, "aad/1") }
    }

    @Test
    fun `three-way merge matches the web rules`() {
        fun r(id: String, rev: Long, vtag: String) = EncryptedRecord(id, rev, vtag, "AAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAA==")
        val local = listOf(r("a", 1, "a1"), r("b", 2, "b2"), r("c", 3, "c3L"), r("d", 2, "d2"), r("e", 2, "e2L"), r("f", 5, "f5"))
        val remote = listOf(r("b", 2, "b2"), r("c", 3, "c3R"), r("d", 3, "d3"), r("e", 2, "e1"), r("f", 4, "f4"), r("g", 1, "g1"))
        val base = mapOf("c" to "c2", "d" to "d2", "e" to "e1", "f" to "f5")
        val m = Merge.mergeRecords(local, remote, base)
        assertEquals(listOf("d", "g"), m.pull.map { it.id })
        assertEquals(listOf("c"), m.conflicts.map { it.id })
        assertEquals(listOf(Merge.Bump("e", 3)), m.bump)
        assertEquals("f5", m.merged["f"]!!.vtag, "an older remote revision never wins")
        assertEquals(3, m.pushed) // a (new), e (local edit), f (stale remote)
        assertTrue(m.remoteChanged)
    }

    @Test
    fun `throttle locks after five failures`() {
        val store = InMemoryVaultStore()
        repeat(4) { Throttle.recordFailure(store, 1000) }
        Throttle.assertNotThrottled(store, 1000)
        val s = Throttle.recordFailure(store, 1000)
        assertEquals(31_000, s.lockedUntil)
        assertFailsWith<Throttle.ThrottledError> { Throttle.assertNotThrottled(store, 2000) }
        Throttle.recordSuccess(store)
        assertNull(store.getLocal("throttle"))
    }
}
