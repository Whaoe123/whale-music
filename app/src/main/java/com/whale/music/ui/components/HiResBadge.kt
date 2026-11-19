package com.whale.music.ui.components

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whale.music.data.model.Song
import com.whale.music.ncm.NcmEapi
import com.whale.music.ncm.NcmLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Hi-Res 小金标 —— 播放页封面右下角的「Hi-Res AUDIO」徽章。
 *
 * 判定策略移植自 Sonance 播放器（`HiResQuality`），注释里自称「情绪价值友好」：
 * 不抠严格的 88.2kHz/24bit 规格，只看**真实拿到的参数**：
 *
 * - **在线歌**：EAPI 实际下发的档位 ≥ 320k 才点亮；128k / 192k / 试听片段不亮
 *   （请求的是无损却只给 320k 时按实际给的算，不自欺）
 * - **本地歌**：无损格式（FLAC / WAV / APE / ALAC / DSD / WavPack …）
 *   或真实码率 ≥ 250 kbps（涵盖 320k MP3、256k+ AAC）点亮；128k / 192k 不亮
 */

/** 在线：EAPI 实际下发档位 ≥ 320k 点亮 */
fun shouldHighlightOnline(url: String): Boolean = when (NcmEapi.levelOfUrl(url)) {
    NcmLevel.EXHIGH, NcmLevel.LOSSLESS, NcmLevel.HIRES -> true
    else -> false
}

/** 码率门槛：250kbps 起算「高音质」（Sonance 同款常量） */
private const val HIGH_BITRATE_THRESHOLD_BPS = 250_000

private val LOSSLESS_MIME = setOf(
    "audio/flac", "audio/wav", "audio/x-wav", "audio/aiff", "audio/x-aiff",
    "audio/alac", "audio/ape", "audio/x-ape", "audio/wavpack", "audio/x-wavpack",
    "audio/dsd", "audio/dsf", "audio/x-dsd", "audio/x-dff"
)

private val LOSSLESS_EXT = setOf(
    "flac", "wav", "aiff", "aif", "ape", "wv", "dsd", "dff", "dsf",
    "alac", "tta", "tak"
)

/** 本地歌是否点亮：无损格式 或 真实码率 ≥ 250kbps */
suspend fun shouldHighlightLocal(context: Context, song: Song): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            // ① 扩展名：MediaExtractor 认不出的老格式（APE/TAK…）靠它兜底
            val ext = song.uri.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext in LOSSLESS_EXT) return@runCatching true

            // ② MediaExtractor 读真实轨参数：mime + 码率
            val info = probeAudio(context, song.uri) ?: return@runCatching false
            if (info.mime != null && info.mime in LOSSLESS_MIME) return@runCatching true
            info.bitrate >= HIGH_BITRATE_THRESHOLD_BPS
        }.getOrDefault(false)
    }

private data class AudioInfo(val mime: String?, val bitrate: Int)

private fun probeAudio(context: Context, uri: String): AudioInfo? = runCatching {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            // 码率字段有些容器没有，缺了按 0 算（不会误亮，靠 mime 判无损）
            val bitrate = runCatching {
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    format.getInteger(MediaFormat.KEY_BIT_RATE)
                } else 0
            }.getOrDefault(0)
            return@runCatching AudioInfo(mime, bitrate)
        }
        null
    } finally {
        runCatching { extractor.release() }
    }
}.getOrNull()

/**
 * 当前这首歌该不该点亮金标。
 *
 * 在线歌看 EAPI 记录的**实际**档位（网络内存，零开销）；
 * 本地歌要读一次轨参数，所以放 LaunchedEffect 里异步算，不卡 UI。
 */
@Composable
fun rememberHiResVisible(song: Song?): Boolean {
    val context = LocalContext.current
    var visible by remember(song?.id, song?.uri) { mutableStateOf(false) }
    LaunchedEffect(song?.id, song?.uri) {
        val s = song
        visible = when {
            s == null -> false
            s.uri.startsWith("http") -> shouldHighlightOnline(s.uri)
            else -> shouldHighlightLocal(context, s)
        }
    }
    return visible
}

/**
 * 「Hi-Res AUDIO」金标 —— 参考网易云样式：
 * 金色圆角方块 + 黑字 `Hi·Res`，下面一条黑底金字 `AUDIO`，
 * 外圈一道暗金描边。两行等宽（IntrinsicSize.Min + fillMaxWidth）。
 */
@Composable
fun HiResBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(Color(0xFF9A7726), Color(0xFF5A4310))
                ),
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        Column(modifier = Modifier.width(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFFFFF2C0),
                                Color(0xFFF4CE55),
                                Color(0xFFDCA92E)
                            )
                        )
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "Hi·Res",
                    color = Color(0xFF141414),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.2).sp
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "AUDIO",
                    color = Color(0xFFF4CE55),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
            }
        }
    }
}
