package com.whale.music.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricStageGeometryTest {

    @Test
    fun `centers respect minimum slot and grow monotonically`() {
        val centers = lyricStageCenters(listOf(10f, 10f, 100f), minimumSlot = 56f, gap = 18f)
        assertEquals(0f, centers[0], 0.001f)
        assertTrue(centers[1] > centers[0])
        assertTrue(centers[2] > centers[1])
        // 100f 的高行：间距按 (10+100)/2+18 = 73 > 56
        assertEquals(73f, centers[2] - centers[1], 0.5f)
    }

    @Test
    fun `anchor interpolates between centers`() {
        val centers = listOf(0f, 100f, 200f)
        assertEquals(0f, lyricStageAnchor(centers, 0f), 0.001f)
        assertEquals(50f, lyricStageAnchor(centers, 0.5f), 0.001f)
        assertEquals(200f, lyricStageAnchor(centers, 9f), 0.001f) // 越界夹取
    }

    @Test
    fun `indexAtCenter is inverse of anchor`() {
        val centers = lyricStageCenters(List(8) { 40f }, minimumSlot = 56f, gap = 18f)
        for (i in centers.indices) {
            val idx = lyricIndexAtCenter(centers, centers[i])
            assertEquals(i.toFloat(), idx, 0.001f)
        }
        // 中点落在两行之间
        val mid = (centers[2] + centers[3]) / 2f
        val idx = lyricIndexAtCenter(centers, mid)
        assertTrue(idx in 2f..3f)
    }

    @Test
    fun `lineProgress clamps and uses next time`() {
        assertEquals(0.5f, lyricLineProgress(1000L, 2000L, 10000L, 1500L), 0.001f)
        assertEquals(0f, lyricLineProgress(1000L, 2000L, 10000L, 500L), 0.001f)
        assertEquals(1f, lyricLineProgress(1000L, 2000L, 10000L, 9000L), 0.001f)
        // 最后一句用曲长结尾
        assertEquals(0.5f, lyricLineProgress(1000L, null, 2000L, 1500L), 0.001f)
        // 未知曲长不编造
        assertEquals(0f, lyricLineProgress(1000L, null, 0L, 1500L), 0.001f)
    }

    @Test
    fun `nextTimes skips equal timestamps`() {
        val next = lyricNextTimes(listOf(0L, 100L, 100L, 200L))
        assertEquals(100L, next[0])
        assertEquals(200L, next[1])
        assertEquals(200L, next[2])
        assertEquals(null, next[3])
    }

    @Test
    fun `stageTime formats minutes and seconds`() {
        assertEquals("00:05", stageTime(5_000))
        assertEquals("01:07", stageTime(67_000))
        assertEquals("00:00", stageTime(-5))
    }

    @Test
    fun `pose emphasis peaks at current line`() {
        val poseNow = lyricStagePose(LyricStyle.FLUID, 0f, 0f, 1f, 0)
        val poseNext = lyricStagePose(LyricStyle.FLUID, 1f, 100f, 1f, 1)
        assertTrue(poseNow.emphasis > 0.99f)
        assertTrue(poseNow.opacity > poseNext.opacity)
        assertTrue(poseNext.blur > 0f)
    }

    @Test
    fun `spatial future lines use opposite vertical direction`() {
        val future = lyricStagePose(LyricStyle.SPATIAL, 1f, 50f, 1f, 0)
        val past = lyricStagePose(LyricStyle.SPATIAL, -1f, 50f, 1f, 0)
        // y = -distance * perspective：未来句向上
        assertTrue(future.y < 0f)
        assertTrue(past.y < 0f)
        assertTrue(future.rotationX != 0f)
    }
}
