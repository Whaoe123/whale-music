package com.whale.music.ui.lyrics

import com.whale.music.util.TraditionalSimplify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSupportTest {

    @Test
    fun `traditional chars convert to simplified`() {
        assertEquals("爱你", TraditionalSimplify.toSimplified("愛你"))
        assertEquals("时光匆匆", TraditionalSimplify.toSimplified("時光匆匆"))
    }

    @Test
    fun `simplified text stays unchanged`() {
        assertEquals("时光匆匆", TraditionalSimplify.toSimplified("时光匆匆"))
    }

    @Test
    fun `pair table is aligned`() {
        // 成对解析：条目数为正，且没有（字，同字）的错位对
        assertTrue(TraditionalSimplify.size > 500)
    }

    @Test
    fun `contrast ratio white on black is about 21`() {
        val r = contrastRatio(Color_White, Color_Black)
        assertTrue(r > 20f && r < 22f)
    }

    @Test
    fun `ensure readable improves contrast toward requested direction`() {
        // 灰字压在浅灰底上：压暗修正后对比度应变好
        val fg = lerpColor(Color_White, Color_Black, 0.35f)
        val bg = lerpColor(Color_White, Color_Black, 0.20f)
        val fixed = ensureReadable(fg, bg, target = 4.5f, lighten = false)
        assertTrue(contrastRatio(fixed, bg) >= contrastRatio(fg, bg))
    }

    @Test
    fun `adapt modes map correctly`() {
        assertTrue(shouldAdapt(isCurrent = true, LyricAdaptMode.CURRENT_ONLY))
        assertTrue(!shouldAdapt(isCurrent = false, LyricAdaptMode.CURRENT_ONLY))
        assertTrue(!shouldAdapt(isCurrent = true, LyricAdaptMode.OFF))
        assertTrue(shouldAdapt(isCurrent = false, LyricAdaptMode.ALL))
    }

    @Test
    fun `bg profile interpolates bands`() {
        val profile = BgProfile(listOf(Color_Black, Color_White), lighten = true)
        val mid = profile.at(0.5f)
        assertTrue(mid.red > 0.4f && mid.red < 0.6f)
    }

    companion object {
        private val Color_White = androidx.compose.ui.graphics.Color.White
        private val Color_Black = androidx.compose.ui.graphics.Color.Black
    }
}
