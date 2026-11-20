package com.whale.music.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalTextTest {

    @Test
    fun `chinese chars become upright tokens`() {
        val tokens = tokenizeVerticalLine("时光匆匆")
        assertEquals(4, tokens.size)
        assertTrue(tokens.all { it is VerticalToken.UprightChar })
    }

    @Test
    fun `short digits become compact horizontal`() {
        val tokens = tokenizeVerticalLine("45")
        assertEquals(1, tokens.size)
        assertTrue(tokens[0] is VerticalToken.CompactHorizontal)
    }

    @Test
    fun `latin words rotate and long words split`() {
        val tokens = tokenizeVerticalLine("hello")
        assertEquals(1, tokens.size)
        assertTrue(tokens[0] is VerticalToken.RotatedWord)
        val long = tokenizeVerticalLine("abcdefghijklmnopqrstuvwxyz")
        assertTrue(long.size >= 2) // 超过 14 字母切段
    }

    @Test
    fun `punctuation maps to vertical forms`() {
        assertEquals('︵', mapToVerticalPunctuation('('))
        assertEquals('︽', mapToVerticalPunctuation('《'))
        assertEquals('·', mapToVerticalPunctuation('，'))
        assertEquals('A', mapToVerticalPunctuation('A')) // 普通字符原样
    }

    @Test
    fun `mixed chinese english tokenizes without empty gaps`() {
        val tokens = tokenizeVerticalLine("爱你 love 3秒")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.none { it is VerticalToken.SpacerGap && tokens.first() == it })
    }

    @Test
    fun `columns split by weight limit`() {
        val tokens = tokenizeVerticalLine("一二三四五六七八九十")
        val cols = splitTokensIntoColumns(tokens, maxWeightPerColumn = 5)
        assertEquals(2, cols.size)
        assertEquals(5, cols[0].size)
        assertEquals(5, cols[1].size)
    }

    @Test
    fun `empty line becomes bullet`() {
        val tokens = tokenizeVerticalLine("   ")
        assertEquals(1, tokens.size)
        assertTrue(tokens[0] is VerticalToken.UprightChar)
    }

    @Test
    fun `rotated word weight is bounded`() {
        val w = VerticalToken.RotatedWord("abcdefghijklmnopqrst").weightChars
        assertTrue(w in 2..6)
    }
}
