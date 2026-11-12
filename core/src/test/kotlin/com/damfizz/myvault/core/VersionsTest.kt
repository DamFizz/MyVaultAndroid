package com.damfizz.myvault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionsTest {
    @Test
    fun `semantic version order`() {
        val ordered = listOf("0.1.0-alpha", "0.2.0-alpha", "0.2.0-beta", "0.2.0", "0.2.1-alpha", "0.2.1-alpha.2", "0.2.1-alpha.10", "0.2.1", "0.10.0", "1.0.0")
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals(i.compareTo(j), Versions.compare(ordered[i], ordered[j]).coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
        }
        assertTrue(Versions.isNewer("v0.2.1-alpha", "0.2.0-alpha"))
        assertFalse(Versions.isNewer("v0.2.0-alpha", "0.2.0-alpha"))
        assertEquals(0, Versions.compare("1.2", "1.2.0"))
    }
}
