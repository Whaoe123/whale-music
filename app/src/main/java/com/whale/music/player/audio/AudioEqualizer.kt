package com.whale.music.player.audio

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * 均衡器：**纯软件实现**，挂在 ExoPlayer 音频链路上做 biquad 滤波。
 *
 * ## 为什么不用 `android.media.audiofx.Equalizer`
 *
 * 系统均衡器与 `Visualizer` 走同一套 `AudioEffect` 机制；部分机型的 AudioFlinger
 * 明确拒绝给应用会话创建效果（`status: -1`），UI 只能显示「本机不支持」。
 * 软件实现走自家 `AudioProcessor` 链路，不依赖任何系统音效服务，**全机型可用**，
 * 频段也由自己定，换机不变形。
 *
 * ## 结构
 *
 * - [EqProcessor]：透传 + 级联 biquad，挂在 [PcmTapProcessor] **之前**，
 *   于是可视化读到的是「用户实际听到的声音」（调了低音增强，柱子也跟着动）。
 * - [EqBus]：音频线程 ↔ UI 线程的共享设置，UI 只写这里，处理器在音频线程读。
 * - [EqPreset]：预设曲线（单位 dB，段数 = [EQ_BAND_COUNT]）。
 *
 * 移植自 gxclsl/Sonance（MIT License）playback/AudioEqualizer.kt。
 */

/** 10 段中心频率（Hz），31Hz–16kHz，与常见硬件均衡器分段一致 */
val EQ_CENTER_FREQS: IntArray = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/** 频段数（滑杆条数） */
val EQ_BAND_COUNT: Int get() = EQ_CENTER_FREQS.size

/** 频段标签：1000 以上折成 "k" 短写法，否则一行放不下 */
fun eqBandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

/** 单段默认 Q 值 */
private const val EQ_Q = 1.3f

/**
 * 每段 Q 值：两端放宽到 0.75~0.85（接近模拟母带均衡的宽搁架，听感温润），
 * 中频保持 1.25~1.35 精确雕刻浑浊与人声质感，相邻段衔接顺滑。
 */
private val EQ_BAND_Q: FloatArray =
    floatArrayOf(0.80f, 1.00f, 1.20f, 1.35f, 1.30f, 1.30f, 1.35f, 1.20f, 1.00f, 0.78f)

private fun eqBandQ(index: Int): Float = EQ_BAND_Q.getOrElse(index) { EQ_Q }

/**
 * 自动前置增益（dB，≤0）。
 *
 * 日常调音曲线（≤5.2 dB 提升）只留 18% 轻量净空，提升量 82% 直接呈现，
 * 切换一耳朵明显；偶发瞬态由链路末端软兜底。超过 5.2 dB 的手拖大增益平滑过渡
 * 到全额补偿，确保拉满 +12 dB 时 16 位 PCM 依然零削波。
 */
internal fun eqAutoPreampDb(maxBoostDb: Float): Float {
    if (!maxBoostDb.isFinite() || maxBoostDb <= 0.05f) return 0f
    val t = ((maxBoostDb - 5.2f) / 5.2f).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    return -(maxBoostDb * (0.18f + 0.82f * smooth))
}

/** 增益上下限（dB），滑杆范围和预设曲线都受它约束 */
const val EQ_MIN_DB = -12f
const val EQ_MAX_DB = 12f

/**
 * 音频线程 ↔ UI 线程之间的均衡器设置总线。
 * 用 [revision] 单调递增传递「设置变了」：音频线程只在版本号变化时重算一次系数。
 */
object EqBus {

    /** 总开关。「关闭」预设就是 enabled = false，整条滤波链直接旁路，不浪费 CPU */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 各段增益（dB），长度恒为 [EQ_BAND_COUNT] */
    @Volatile
    var gainsDb: FloatArray = FloatArray(EQ_BAND_COUNT)
        private set

    @Volatile
    var revision: Long = 0L
        private set

    fun update(enabled: Boolean, gainsDb: FloatArray) {
        this.enabled = enabled
        this.gainsDb = FloatArray(EQ_BAND_COUNT) {
            val value = gainsDb.getOrElse(it) { 0f }
            if (value.isFinite()) value.coerceIn(EQ_MIN_DB, EQ_MAX_DB) else 0f
        }
        revision++
    }

    /** 单测用：复位总线，避免用例互相污染 */
    @VisibleForTesting
    internal fun reset() {
        enabled = false
        gainsDb = FloatArray(EQ_BAND_COUNT)
        revision = 0L
    }
}

/**
 * 级联 peaking biquad 滤波器组。
 * 单独抽出以可测：纯 DSP 喂正弦波能量输出即可验证「1kHz 抬 6dB 后能量确实变大」。
 * 无锁：系数与状态只被音频线程读写。
 */
internal class EqCascade(val bandCount: Int, val channels: Int) {

    private val b0 = DoubleArray(bandCount)
    private val b1 = DoubleArray(bandCount)
    private val b2 = DoubleArray(bandCount)
    private val a1 = DoubleArray(bandCount)
    private val a2 = DoubleArray(bandCount)

    /** 每声道、每段的 DF2T 状态（64 位双精度消除高采样率低频极点漂移） */
    private val z1 = Array(channels) { DoubleArray(bandCount) }
    private val z2 = Array(channels) { DoubleArray(bandCount) }

    /** 按当前采样率把 [gainsDb] 设计成系数（[freqs] 与 [gainsDb] 一一对应） */
    fun design(freqs: IntArray, gainsDb: FloatArray, sampleRate: Int) {
        var b = 0
        while (b < bandCount) {
            val c = BiquadDesign.peaking(
                f0 = freqs.getOrElse(b) { 0 }.toDouble(),
                gainDb = gainsDb.getOrElse(b) { 0f }.toDouble(),
                sampleRate = sampleRate,
                q = eqBandQ(b).toDouble()
            )
            b0[b] = c[0]; b1[b] = c[1]; b2[b] = c[2]; a1[b] = c[3]; a2[b] = c[4]
            b++
        }
    }

    /** 清滤波器状态（seek / 暂停后必须做，否则会带出一声残留爆音） */
    fun reset() {
        var ch = 0
        while (ch < channels) {
            z1[ch].fill(0.0)
            z2[ch].fill(0.0)
            ch++
        }
    }

    /**
     * 就地处理交错（interleaved）float PCM。
     * @param buf 交错采样，长度 = frames * channels
     */
    fun process(buf: FloatArray, frames: Int) {
        var ch = 0
        while (ch < channels) {
            val s1 = z1[ch]
            val s2 = z2[ch]
            var i = 0
            while (i < frames) {
                val idx = i * channels + ch
                var x = buf[idx].toDouble()
                var b = 0
                while (b < bandCount) {
                    // DF2T：状态更新必须用**输入** x，先算 y 再更新，不能覆盖 x
                    val y = b0[b] * x + s1[b]
                    s1[b] = b1[b] * x - a1[b] * y + s2[b]
                    s2[b] = b2[b] * x - a2[b] * y
                    x = y
                    b++
                }
                buf[idx] = x.toFloat()
                i++
            }
            ch++
        }
    }
}

/**
 * 均衡处理器：输入原样交给下游，中间串一串 peaking biquad。
 * 支持 16 / 24 / 32 位整型及 32 位浮点 PCM（16 位输出开启 TPDF 抖动）。
 */
class EqProcessor : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    private var cascade: EqCascade? = null

    private var appliedRevision = -1L
    private var appliedEnabled = false

    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)
    private var intScratch = IntArray(0)
    private var ditherSeed = 0x13579BDF

    /** 当前是否真的在滤波（enabled == false 时整条链旁路） */
    private var filtering: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (!PcmCodec.isSupportedEncoding(enc)) {
            sampleRate = 0
            channels = 0
            encoding = C.ENCODING_INVALID
            cascade = null
            Log.w(TAG, "均衡器：暂不支持的编码 $enc，将直接旁路")
            return inputAudioFormat
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        cascade = EqCascade(EQ_BAND_COUNT, channels)
        appliedRevision = -1L // 采样率变了，系数必须重算
        Log.i(TAG, "均衡器已就绪：${sampleRate}Hz ${channels}ch enc=$enc")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        syncSettings()
        val out = replaceOutputBuffer(remaining)
        val cas = cascade

        if (!filtering || cas == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = if (frameBytes > 0) remaining / frameBytes else 0
        val samples = frames * channels
        if (frames <= 0 || samples <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (floatScratch.size < samples) floatScratch = FloatArray(samples)
        if (encoding == C.ENCODING_PCM_32BIT && intScratch.size < samples) intScratch = IntArray(samples)

        PcmCodec.readToFloat(inputBuffer, floatScratch, samples, encoding)
        cas.process(floatScratch, frames)

        val (nextSeed, updatedShorts) = PcmCodec.writeFromFloat(
            src = floatScratch,
            outBuffer = out,
            remainingBytes = remaining,
            samples = samples,
            encoding = encoding,
            shortScratch = shortScratch,
            intScratch = intScratch,
            applyDither = true,
            ditherSeed = ditherSeed
        )
        ditherSeed = nextSeed
        shortScratch = updatedShorts
    }

    override fun onFlush() {
        cascade?.reset()
        ditherSeed = 0x13579BDF
    }

    override fun onReset() {
        cascade?.reset()
        cascade = null
        sampleRate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
        appliedRevision = -1L
    }

    /**
     * 把 [EqBus] 的设置同步到本地：只在版本号或开关变化时重算系数。
     * enabled == false 时旁路是零成本的，不会为了「全 0dB」还跑 10 段乘加。
     */
    private fun syncSettings() {
        val rev = EqBus.revision
        val en = EqBus.enabled
        if (rev == appliedRevision && en == appliedEnabled) return
        appliedRevision = rev
        appliedEnabled = en

        val cas = cascade
        filtering = cas != null && en && sampleRate > 0
        if (!filtering || cas == null) return
        cas.design(EQ_CENTER_FREQS, EqBus.gainsDb, sampleRate)
    }

    private companion object {
        const val TAG = "WhaleEq"
    }
}

/**
 * 均衡器预设：直接给 10 段增益（dB），与 [EQ_CENTER_FREQS] 一一对应。
 * 增益普遍压在 ±8dB 以内 —— 峰值再高，16bit 输出一削顶就是失真。
 */
enum class EqPreset(val label: String, val curve: FloatArray) {

    /** 关闭：整条滤波链旁路 */
    OFF("关闭", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),

    /** 流行：中频抬一点、两端略降，人声和旋律更靠前 */
    POP("流行", floatArrayOf(-1f, -0.5f, 1f, 3f, 4f, 3.5f, 2f, 1f, 1.5f, 2f)),

    /** 人声：中高频突出，低频让路，听清咬字 */
    VOCAL("人声", floatArrayOf(-4f, -2.5f, -1f, 0.5f, 2.5f, 4f, 4.5f, 3f, 1f, -1f)),

    /** 摇滚：典型 V 型，低频和镲片两头翘 */
    ROCK("摇滚", floatArrayOf(5f, 3.5f, 2f, -1f, -1.5f, 0f, 1.5f, 3f, 4f, 4.5f)),

    /** 爵士：低频温暖、中频略退、高频通透 */
    JAZZ("爵士", floatArrayOf(3.5f, 2.5f, 1f, 1.5f, -1f, -1f, 0f, 1.5f, 2.5f, 3f)),

    /** 古典：整体平直，两端轻微抬起补厅堂感 */
    CLASSICAL("古典", floatArrayOf(3.5f, 2.5f, 1.5f, 0f, -1f, -1f, 0f, 1.5f, 3f, 3.5f)),

    /** 舞曲：低频有力 + 中高频明亮，节奏感强 */
    DANCE("舞曲", floatArrayOf(6f, 5f, 2.5f, 0f, 1f, 2.5f, 3.5f, 4f, 3f, 1f)),

    /** 低音增强：低频拉满，其余递减以免轰头 */
    BASS("低音增强", floatArrayOf(8f, 7f, 5.5f, 3f, 1f, -0.5f, -1.5f, -2f, -2f, -2f)),

    /** 高音增强：齿音与细节变清楚，低频收掉 */
    TREBLE("高音增强", floatArrayOf(-4f, -3f, -2f, -1f, 0f, 1f, 2.5f, 4.5f, 6f, 6.5f)),

    /** 柔和：整体压两端，睡前/长时间听不累 */
    SOFT("柔和", floatArrayOf(1.5f, 1.5f, 1f, 0.5f, 0f, -0.5f, -1f, -2f, -3f, -3.5f));

    companion object {
        /** 按名字取预设（持久化用），找不到回落到 [OFF] */
        fun fromName(name: String?): EqPreset =
            entries.firstOrNull { it.name == name } ?: OFF

        /** 预设曲线，长度等于 [EQ_BAND_COUNT]，夹在 [EQ_MIN_DB]..[EQ_MAX_DB] 内 */
        fun curveOf(preset: EqPreset): FloatArray =
            FloatArray(EQ_BAND_COUNT) {
                preset.curve.getOrElse(it) { 0f }.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
            }
    }
}

/** 与实际 64 位 biquad 同一组系数的频响估计，给 UI 画曲线用 */
internal class EqResponse(gainsDb: FloatArray, private val sampleRate: Int) {
    private val coefficients = Array(EQ_BAND_COUNT) { i ->
        val gain = gainsDb.getOrElse(i) { 0f }.let { if (it.isFinite()) it else 0f }
        BiquadDesign.peaking(
            f0 = EQ_CENTER_FREQS[i].toDouble(),
            gainDb = gain.toDouble(),
            sampleRate = sampleRate,
            q = eqBandQ(i).toDouble()
        )
    }

    fun dbAt(frequencyHz: Double): Float {
        if (sampleRate <= 0) return 0f
        val frequency = frequencyHz.coerceIn(1.0, sampleRate * 0.499)
        val w = 2.0 * PI * frequency / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        var db = 0.0
        for (c in coefficients) {
            val nr = c[0] + c[1] * c1 + c[2] * c2
            val ni = -c[1] * s1 - c[2] * s2
            val dr = 1.0 + c[3] * c1 + c[4] * c2
            val di = -c[3] * s1 - c[4] * s2
            val numerator = nr * nr + ni * ni
            val denominator = dr * dr + di * di
            if (numerator > 1e-20 && denominator > 1e-20) {
                db += 10.0 * log10(numerator / denominator)
            }
        }
        return db.toFloat()
    }

    /** 对数频率扫描，计入相邻频段叠加，不能只取单根滑杆的最大值 */
    fun maxBoostDb(): Float {
        if (sampleRate <= 0) return 0f
        val top = minOf(20_000.0, sampleRate * 0.49)
        if (top <= 20.0) return 0f
        var peak = 0f
        for (i in 0..128) {
            val frequency = 20.0 * (top / 20.0).pow(i / 128.0)
            peak = max(peak, dbAt(frequency))
        }
        return peak
    }
}
