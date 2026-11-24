package com.umitm0d.tvizle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUtilTest {
    @Test fun sha256Validation() {
        assertTrue(UpdateUtil.isValidSha256("a".repeat(64)))
        assertFalse(UpdateUtil.isValidSha256("A".repeat(64)))
        assertFalse(UpdateUtil.isValidSha256("a".repeat(63)))
        assertFalse(UpdateUtil.isValidSha256(""))
    }

    @Test fun tagParsing() {
        assertEquals(107, UpdateUtil.codeFromTag("v107"))
        assertEquals(-1, UpdateUtil.codeFromTag("latest"))
    }

    @Test fun versionCodeIsMonotonic() {
        assertEquals(506, UpdateUtil.versionCode("5.6"))
        assertTrue(UpdateUtil.versionCode("5.10") > UpdateUtil.versionCode("5.9"))
        assertTrue(UpdateUtil.versionCode("6.0") > UpdateUtil.versionCode("5.99"))
        assertEquals(-1, UpdateUtil.versionCode("5.100"))
        assertEquals(-1, UpdateUtil.versionCode("abc"))
    }
}
