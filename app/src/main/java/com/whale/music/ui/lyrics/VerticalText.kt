package com.whale.music.ui.lyrics

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/*
 * 竖排分词/分列 — 移植自 Sonance（gxslcl/Sonance，MIT License，Copyright (c) 2026 chenlucc）。
 * 纯逻辑部分无 Android 依赖，可单测。
 */

sealed class VerticalToken(val weightChars: Int) {
    /** 单个正立字符（汉字、假名、韩文或已转换的竖排标点） */
    data class UprightChar(val ch: Char) : VerticalToken(1)

    /** 2~3 个字符的短数字或全大写缩写（纵中横 Tate-chu-yoko） */
    data class CompactHorizontal(val text: String) : VerticalToken(1)

    /** 顺时针旋转 90° 的英文单词或长数字串 */
    data class RotatedWord(val word: String) : VerticalToken(
        (word.length * 0.55f).toInt().coerceIn(2, 6)
    )

    /** 词间小留白 */
    data object SpacerGap : VerticalToken(1)
}

/**
 * 把一行歌词拆成适合竖排的 [VerticalToken] 序列：
 * - CJK：逐字正立；标点映射竖排标点（︵︶︽︾︱…）；
 * - 英文/数字：2~3 位纯数字或全大写缩写走纵中横，其余单词顺时针旋转 90°，
 *   超过 14 个字母按段切分防止单个旋转词撑爆一列。
 */
fun tokenizeVerticalLine(raw: String): List<VerticalToken> {
    val text = raw.trim()
    if (text.isEmpty()) return listOf(VerticalToken.UprightChar('•'))

    val out = ArrayList<VerticalToken>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> {
                if (out.isNotEmpty() && out.last() != VerticalToken.SpacerGap) {
                    out.add(VerticalToken.SpacerGap)
                }
                while (i < text.length && text[i].isWhitespace()) i++
            }

            isLatinWordChar(c) -> {
                val start = i
                while (i < text.length && isLatinWordChar(text[i])) i++
                val word = text.substring(start, i)
                while (i < text.length && text[i].isWhitespace()) i++
                appendLatinWordTokens(out, word)
            }

            else -> {
                val mapped = mapToVerticalPunctuation(c)
                if (mapped != null) {
                    out.add(VerticalToken.UprightChar(mapped))
                }
                i++
            }
        }
    }
    while (out.lastOrNull() == VerticalToken.SpacerGap) {
        out.removeAt(out.lastIndex)
    }
    return if (out.isEmpty()) listOf(VerticalToken.UprightChar('•')) else out
}

private fun isLatinWordChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
        c in '\u00C0'..'\u024F' || c == '\'' || c == '-' || c == '&' || c == '+' || c == '/'

private fun appendLatinWordTokens(out: MutableList<VerticalToken>, word: String) {
    if (word.length == 1) {
        out.add(VerticalToken.UprightChar(word[0]))
        return
    }
    val allDigits = word.all { it.isDigit() }
    val allUpperShort = word.length <= 2 && word.all { it in 'A'..'Z' || it.isDigit() }
    if ((allDigits && word.length <= 3) || allUpperShort) {
        out.add(VerticalToken.CompactHorizontal(word))
        return
    }
    val chunkSize = 14
    var pos = 0
    while (pos < word.length) {
        val end = minOf(word.length, pos + chunkSize)
        out.add(VerticalToken.RotatedWord(word.substring(pos, end)))
        pos = end
    }
}

/** 横排标点 → 竖排标点（Unicode CJK Compatibility Forms） */
fun mapToVerticalPunctuation(c: Char): Char? = when (c) {
    '(', '（' -> '︵'
    ')', '）' -> '︶'
    '[', '【', '〔' -> '︻'
    ']', '】', '〕' -> '︼'
    '{', '｛' -> '︷'
    '}', '｝' -> '︸'
    '<', '《' -> '︽'
    '>', '》' -> '︾'
    '〈' -> '︿'
    '〉' -> '﹀'
    '「' -> '﹁'
    '」' -> '﹂'
    '『' -> '﹃'
    '』' -> '﹄'
    '—', '－', '–', '―' -> '︱'
    '~', '～' -> '≀'
    '…' -> '⋮'
    '、', '，', ',', '。', '.' -> '·'
    else -> c
}

/**
 * 将 Token 序列按每列最大字符权重拆成 1~N 列。
 * 纯英文长句自动折多列，不溢出屏幕底部。
 */
fun splitTokensIntoColumns(
    tokens: List<VerticalToken>,
    maxWeightPerColumn: Int
): List<List<VerticalToken>> {
    val limit = maxWeightPerColumn.coerceAtLeast(5)
    val result = ArrayList<List<VerticalToken>>()
    var current = ArrayList<VerticalToken>()
    var currentWeight = 0

    for (token in tokens) {
        if (token == VerticalToken.SpacerGap && current.isEmpty()) continue
        if (current.isNotEmpty() && currentWeight + token.weightChars > limit) {
            while (current.lastOrNull() == VerticalToken.SpacerGap) {
                current.removeAt(current.lastIndex)
            }
            if (current.isNotEmpty()) result.add(current)
            current = ArrayList()
            currentWeight = 0
            if (token == VerticalToken.SpacerGap) continue
        }
        current.add(token)
        currentWeight += token.weightChars
    }
    while (current.lastOrNull() == VerticalToken.SpacerGap) {
        current.removeAt(current.lastIndex)
    }
    if (current.isNotEmpty()) result.add(current)
    return if (result.isEmpty()) listOf(listOf(VerticalToken.UprightChar('•'))) else result
}

/**
 * 英文单词顺时针旋转 90°，布局阶段宽高对调
 * （width = placeable.height, height = placeable.width），
 * 旋转后的词在 Column 中有精确纵向占位，不与上下汉字重叠。
 */
internal fun Modifier.rotate90Layout(): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = 0,
            maxWidth = androidx.compose.ui.unit.Constraints.Infinity,
            minHeight = 0,
            maxHeight = androidx.compose.ui.unit.Constraints.Infinity
        )
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = (placeable.height - placeable.width) / 2,
            y = (placeable.width - placeable.height) / 2,
            layerBlock = {
                rotationZ = 90f
            }
        )
    }
}
