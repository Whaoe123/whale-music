package com.whale.music.data.sync

/**
 * 歌曲归一化 key —— 用于跨平台（网易云 / 酷我 / 汽水）判断「是不是同一首歌」。
 *
 * 各平台歌名后缀（DJ版 / 伴奏）、歌手拼接（/ 与 & 与 ,）都不一致，
 * 所以按「去括号内容 + 去标点空白 + 歌手拆分成多份」生成多个变体，
 * 任一变体命中即视为同一首。
 */
object SongKey {
    private val BRACKETS = Regex("[（(【\\[][^）)】\\]]*[）)】\\]]")
    private val NOISE = Regex("[\\s\\p{Punct}·、，,/+&｜|—\\-_'\"“”‘’]")
    private val ARTIST_SPLIT = Regex("[/&、,，;；]")

    fun normalize(s: String): String {
        if (s.isBlank()) return ""
        val noBrackets = BRACKETS.replace(s.lowercase(), "")
        return NOISE.replace(noBrackets, "")
    }

    fun artistParts(artist: String): List<String> =
        ARTIST_SPLIT.split(artist).map(::normalize).filter { it.isNotBlank() }

    /** 一首歌的所有候选 key（整段歌手 + 拆分后的每个歌手） */
    fun variants(title: String, artist: String): Set<String> {
        val t = normalize(title)
        if (t.isBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        val full = normalize(artist)
        if (full.isNotBlank()) out += "$t|$full"
        artistParts(artist).forEach { out += "$t|$it" }
        if (out.isEmpty()) out += "$t|"
        return out
    }

    fun keyOf(title: String, artist: String): String =
        "${normalize(title)}|${normalize(artist)}"
}
