package com.whale.music.data.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LxRidTest {

    @Test
    fun `rid is deterministic for same script and song id`() {
        val a = LxSourceManager.ridOfImpl("script1", "123456")
        val b = LxSourceManager.ridOfImpl("script1", "123456")
        assertEquals(a, b)
    }

    @Test
    fun `rid is always a positive long`() {
        // 抽样一批，确保没有符号位问题（Song.id 为负会撞 MediaStore 语义）
        for (i in 0 until 200) {
            val rid = LxSourceManager.ridOfImpl("s$i", "song_$i")
            assertTrue("rid 必须为正：$rid", rid > 0)
        }
    }

    @Test
    fun `rid differs across scripts or songs`() {
        val base = LxSourceManager.ridOfImpl("s1", "song1")
        assertNotEquals(base, LxSourceManager.ridOfImpl("s2", "song1"))
        assertNotEquals(base, LxSourceManager.ridOfImpl("s1", "song2"))
    }
}
