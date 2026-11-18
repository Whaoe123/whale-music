package com.whale.music.util

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/**
 * Resolves artwork from a previous local cache when available, then falls back
 * to the device MediaStore. The bitmap loader supplies the bundled placeholder
 * if MediaStore does not contain artwork for the album.
 */
object CoverResolver {
    private var coverDir: File? = null
    private var externalAppCoverDir: File? = null

    /** 下载歌曲的封面：MediaStore mediaId → 本地 jpg，保证下载后图标和原曲一致 */
    private var downloadCoverDir: File? = null
    private val downloadCovers = java.util.concurrent.ConcurrentHashMap<Long, File>()

    /** 网易云在线封面旁路：albumId → https 封面地址 */
    private val ncmCovers = java.util.concurrent.ConcurrentHashMap<Long, Uri>()

    fun registerNcmCover(albumId: Long, coverUrl: String) {
        if (albumId > 0 && coverUrl.isNotEmpty()) {
            ncmCovers[albumId] = CoverUrl.hd(coverUrl).toUri()
        }
    }

    fun init(context: Context) {
        coverDir = File(context.filesDir, "covers").also { if (!it.exists()) it.mkdirs() }
        externalAppCoverDir = context.getExternalFilesDir(null)?.let { File(it, "covers") }
            ?.also { if (!it.exists()) it.mkdirs() }

        downloadCoverDir = File(context.filesDir, "download_covers")
            .also { if (!it.exists()) it.mkdirs() }
        // 文件名就是 mediaId，冷启动直接重建映射，不需要额外索引文件
        downloadCoverDir?.listFiles()?.forEach { file ->
            val id = file.nameWithoutExtension.toLongOrNull() ?: return@forEach
            if (file.length() > 1000) downloadCovers[id] = file
        }
    }

    /** 下载完成后登记封面，参数为 MediaStore 返回的媒体 id */
    fun registerDownloadCover(mediaId: Long, file: File) {
        if (mediaId > 0 && file.exists() && file.length() > 1000) {
            downloadCovers[mediaId] = file
        }
    }

    fun downloadCoverDir(): File? = downloadCoverDir

    /** 删除歌曲时一并清掉它的本地封面 */
    fun removeDownloadCover(mediaId: Long) {
        downloadCovers.remove(mediaId)?.delete()
    }

    fun resolve(albumId: Long): Uri {
        ncmCovers[albumId]?.let { return it }
        val internalFile = coverDir?.let { File(it, "$albumId.jpg") }
        if (internalFile != null && internalFile.exists() && internalFile.length() > 1000) {
            return Uri.fromFile(internalFile)
        }

        val externalFile = externalAppCoverDir?.let { File(it, "$albumId.jpg") }
        if (externalFile != null && externalFile.exists() && externalFile.length() > 1000) {
            return Uri.fromFile(externalFile)
        }

        return "content://media/external/audio/albumart/$albumId".toUri()
    }

    /**
     * 本地歌曲封面：优先用下载时保存的原封面，其次才走 MediaStore 专辑图。
     */
    fun resolveSong(mediaId: Long, albumId: Long): Uri {
        downloadCovers[mediaId]?.takeIf { it.exists() && it.length() > 1000 }?.let {
            return Uri.fromFile(it)
        }
        return resolve(albumId)
    }
}
