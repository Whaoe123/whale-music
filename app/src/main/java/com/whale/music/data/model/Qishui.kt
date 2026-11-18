package com.whale.music.data.model

/**
 * 汽水音乐（字节）数据模型。
 *
 * 注意 trackId 是 19 位长整型字符串，超出部分场景的 Int 精度，统一用 String 保存。
 */

/** 汽水推荐歌单 */
data class QsPlaylist(
    val id: String,
    val title: String,
    val cover: String,
    val trackCount: Int,
    val owner: String = "",
    val desc: String = ""
)

/** 汽水曲目 —— 只用于展示与匹配，实际播放走酷我 */
data class QsTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    val duration: Long,
    /** 酷我侧匹配到的歌曲 ID，-1 表示还没匹配 / 匹配失败 */
    val kwRid: Long = -1L
)

/** 歌单分页结果 */
data class QsPage(
    val tracks: List<QsTrack> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)
