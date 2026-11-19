package com.whale.music.ncm

/** 网易云账号资料 */
data class NcmProfile(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    /** 是否有会员权益（决定取流默认档位：会员无损 / 非会员 320k） */
    val vip: Boolean = false
)

/** 歌曲详情（兼容 v1 artists/album 与新版 ar/al 两种字段） */
data class NcmSongDto(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,   // https 封面
    val fee: Int
)

/** 扫码登录状态（与网易返回码一致；RISK_CONTROLLED = -462 网关风控；BLOCKED = 8821 账号级风控拒绝签发登录态） */
enum class QrStatus { WAITING, SCANNED, EXPIRED, SUCCESS, RISK_CONTROLLED, BLOCKED }

data class QrLoginState(
    val key: String,
    val qrUrl: String,      // 二维码内容：https://music.163.com/login?codekey=<key>
    val status: QrStatus = QrStatus.WAITING
)

/** 登录状态 */
enum class NcmLoginState { LOGGED_OUT, LOGGED_IN }
