package com.whale.music.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.whale.music.MainActivity
import com.whale.music.R
import com.whale.music.data.repository.MusicRepository
import com.whale.music.player.audio.EqProcessor
import com.whale.music.player.audio.PcmTapProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 系统级播放服务。
 *
 * ExoPlayer 的生命周期由 Service 持有；界面只通过 MediaController 控制它。
 * Media3 会基于 MediaSession 自动维护前台通知、锁屏、耳机和蓝牙控制。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicPlayerService : MediaLibraryService() {

    @Inject
    lateinit var repository: MusicRepository

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val libraryRootItem: MediaItem by lazy {
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(libraryRootItem, params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID || page < 0 || pageSize < 1) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
                )
            }

            val player = session.player
            val allItems = List(player.mediaItemCount) { player.getMediaItemAt(it) }
            val fromIndex = (page.toLong() * pageSize).coerceAtMost(allItems.size.toLong()).toInt()
            val toIndex = (fromIndex + pageSize).coerceAtMost(allItems.size)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(allItems.subList(fromIndex, toIndex), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val player = session.player
            val item = (0 until player.mediaItemCount)
                .map(player::getMediaItemAt)
                .firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                )
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }
    }

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            StableMediaNotificationProvider(this) { songId ->
                serviceScope.launch { repository.toggleFavorite(songId) }
            }
        )

        // 音频链路：EQ → PCM 取样器。
        // 取样器放最后，可视化读到的是**用户实际听到的声音**（开了低音增强，
        // 柱子也真的往低频那边动）；反过来就会出现「调了音效但柱子没反应」。
        //
        // ⚠️ 必须显式关掉 Audio Offload：硬件 offload 会把编码流直接送给 DSP，
        // 不做 PCM 解码，整条 AudioProcessor 链会被完全绕过 —— 表现就是
        // 「某些机型 / 某些格式下所有音效静默失效」。
        //
        // ⚠️ 必须保持 DefaultAudioSink 默认浮点关闭（setEnableFloatOutput(false)）：
        //    media3 在 enableFloatOutput=true 且播放 24/32bit Hi-Res 音轨时可能
        //    跳过整个 audioProcessorChain（均衡器、频谱全失效）。各 Processor 已
        //    通过 PcmCodec 原生解码 16/24/32/Float 并在内部全程 32/64 位处理。
        val audioSink = DefaultAudioSink.Builder(this)
            .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
            .setEnableFloatOutput(false)
            .setAudioProcessors(arrayOf<AudioProcessor>(EqProcessor(), PcmTapProcessor()))
            .build()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(SinkRenderersFactory(this, audioSink))
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, libraryCallback)
            .setBitmapLoader(FallbackBitmapLoader(this))
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ROOT_ID = "ohmusic_root"
    }
}

/**
 * 唯一目的：把带 [EqProcessor] / [PcmTapProcessor] 的 [AudioSink] 交给 ExoPlayer。
 *
 * `ExoPlayer.Builder` 没有 `setAudioSink`，注入音频链路的官方入口就是
 * [DefaultRenderersFactory.buildAudioSink]。
 */
@OptIn(UnstableApi::class)
private class SinkRenderersFactory(
    context: Context,
    private val audioSink: AudioSink
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = audioSink
}
