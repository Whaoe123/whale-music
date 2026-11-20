package com.whale.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtlCacheTest {

    private var now = 0L
    private fun cache(maxSize: Int = 16) = TtlCache<String>(maxSize) { now }

    @Test
    fun `put then get returns value`() {
        val c = cache()
        c.put("k", "v", ttlMs = 1_000)
        assertEquals("v", c.get("k"))
    }

    @Test
    fun `expired entry returns null`() {
        val c = cache()
        c.put("k", "v", ttlMs = 1_000)
        now = 999
        assertEquals("v", c.get("k"))
        now = 1_000
        assertNull(c.get("k"))
    }

    @Test
    fun `empty value is a valid negative cache entry`() {
        val c = cache()
        c.put("k", "", ttlMs = 1_000)
        assertEquals("", c.get("k"))
    }

    @Test
    fun `zero or negative ttl is not stored`() {
        val c = cache()
        c.put("k", "v", ttlMs = 0)
        assertNull(c.get("k"))
    }

    @Test
    fun `clear removes everything`() {
        val c = cache()
        c.put("a", "1", ttlMs = 1_000)
        c.put("b", "2", ttlMs = 1_000)
        c.clear()
        assertNull(c.get("a"))
        assertNull(c.get("b"))
        assertEquals(0, c.size())
    }

    @Test
    fun `evicts oldest beyond max size`() {
        val c = cache(maxSize = 2)
        c.put("a", "1", ttlMs = 10_000)
        c.put("b", "2", ttlMs = 10_000)
        c.put("c", "3", ttlMs = 10_000)
        assertNull(c.get("a"))
        assertEquals("2", c.get("b"))
        assertEquals("3", c.get("c"))
    }
}
