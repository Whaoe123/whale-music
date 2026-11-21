package com.whale.music.player.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * PCM 采样转换（16 / 24 打包 / 32 位整型与 32 位浮点），含 16 位量化用的
 * TPDF 抖动；另附 64 位 Double RBJ biquad 设计辅助，供 EQ / 取样器共用，
 * 避免各处理器重复编码与滤波数学。
 *
 * 移植自 gxclsl/Sonance（MIT License）playback/PcmCodec.kt，为 whale-music 调整包名。
 */
internal object PcmCodec {

    private const val SCALE_16 = 32768.0
    private const val SCALE_24 = 8388608.0
    private const val SCALE_32 = 2147483648.0
    private const val INV_16 = 1f / 32768f
    private const val INV_24 = 1f / 8388608f
    private const val INV_32 = 1f / 2147483648f

    /** TPDF 抖动幅度：xorshift32 均匀差分映射到 ±1 LSB 三角概率密度。 */
    private const val TPDF_SCALE_16 = 1f / (32768f * 4294967296f)

    fun isSupportedEncoding(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_FLOAT

    /** 从 [inputBuffer] 当前 position 读 [samples] 个交错采样到 [-1, 1] 浮点。 */
    fun readToFloat(
        inputBuffer: ByteBuffer,
        dst: FloatArray,
        samples: Int,
        encoding: Int
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = 0
                while (i < samples) {
                    dst[i] = inputBuffer.short * INV_16
                    i++
                }
            }
            C.ENCODING_PCM_24BIT -> {
                val littleEndian = inputBuffer.order() == ByteOrder.LITTLE_ENDIAN
                var i = 0
                while (i < samples) {
                    val b0 = inputBuffer.get()
                    val b1 = inputBuffer.get()
                    val b2 = inputBuffer.get()
                    val value = if (littleEndian) {
                        (b0.toInt() and 0xFF) or
                            ((b1.toInt() and 0xFF) shl 8) or
                            (b2.toInt() shl 16)
                    } else {
                        (b0.toInt() shl 16) or
                            ((b1.toInt() and 0xFF) shl 8) or
                            (b2.toInt() and 0xFF)
                    }
                    dst[i] = value * INV_24
                    i++
                }
            }
            C.ENCODING_PCM_32BIT -> {
                var i = 0
                while (i < samples) {
                    dst[i] = inputBuffer.int * INV_32
                    i++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = 0
                while (i < samples) {
                    dst[i] = inputBuffer.float
                    i++
                }
            }
        }
    }

    /**
     * 读 [frames] 帧 × [channels] 声道，逐帧平均成单声道 float 写入 [dstMono]。
     * 返回累计平方和，供零额外遍历的 RMS 计算。
     */
    fun readChunkToMonoAndSumSq(
        inputBuffer: ByteBuffer,
        dstMono: FloatArray,
        frames: Int,
        channels: Int,
        encoding: Int
    ): Float {
        val invCh = 1f / channels
        val littleEndian = inputBuffer.order() == ByteOrder.LITTLE_ENDIAN
        var sumSq = 0f
        var i = 0
        while (i < frames) {
            var chSum = 0f
            var c = 0
            while (c < channels) {
                val sample = when (encoding) {
                    C.ENCODING_PCM_16BIT -> inputBuffer.short * INV_16
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = inputBuffer.get()
                        val b1 = inputBuffer.get()
                        val b2 = inputBuffer.get()
                        val v = if (littleEndian) {
                            (b0.toInt() and 0xFF) or
                                ((b1.toInt() and 0xFF) shl 8) or
                                (b2.toInt() shl 16)
                        } else {
                            (b0.toInt() shl 16) or
                                ((b1.toInt() and 0xFF) shl 8) or
                                (b2.toInt() and 0xFF)
                        }
                        v * INV_24
                    }
                    C.ENCODING_PCM_32BIT -> inputBuffer.int * INV_32
                    C.ENCODING_PCM_FLOAT -> inputBuffer.float
                    else -> 0f
                }
                chSum += sample
                c++
            }
            val mono = chSum * invCh
            dstMono[i] = mono
            sumSq += mono * mono
            i++
        }
        return sumSq
    }

    /**
     * 把 [src] 浮点采样量化写入 [outBuffer]，position 置为 [remainingBytes] 后 flip。
     * 16 位整型输出在 [applyDither] 时叠加 TPDF 抖动，抑制低电平量化谐波失真。
     */
    fun writeFromFloat(
        src: FloatArray,
        outBuffer: ByteBuffer,
        remainingBytes: Int,
        samples: Int,
        encoding: Int,
        shortScratch: ShortArray,
        intScratch: IntArray,
        applyDither: Boolean,
        ditherSeed: Int
    ): Pair<Int, ShortArray> {
        var seed = if (ditherSeed == 0) 0x6D2B79F5 else ditherSeed
        var shorts = shortScratch
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                if (shorts.size < samples) shorts = ShortArray(samples)
                var i = 0
                if (applyDither) {
                    while (i < samples) {
                        var s = src[i]
                        if (!s.isFinite()) s = 0f
                        seed = xorshift32(seed)
                        val r1 = seed
                        seed = xorshift32(seed)
                        val r2 = seed
                        val dither = (r1.toLong() - r2.toLong()) * TPDF_SCALE_16
                        val dithered = (s + dither).coerceIn(-1f, 1f)
                        shorts[i] = (dithered * SCALE_16).roundToInt().coerceIn(-32768, 32767).toShort()
                        i++
                    }
                } else {
                    while (i < samples) {
                        val v = src[i]
                        shorts[i] = when {
                            !v.isFinite() -> 0
                            v >= 1f -> Short.MAX_VALUE
                            v <= -1f -> Short.MIN_VALUE
                            else -> (v * SCALE_16).roundToInt().coerceIn(-32768, 32767).toShort()
                        }
                        i++
                    }
                }
                outBuffer.asShortBuffer().put(shorts, 0, samples)
                outBuffer.position(remainingBytes)
            }
            C.ENCODING_PCM_24BIT -> {
                val littleEndian = outBuffer.order() == ByteOrder.LITTLE_ENDIAN
                var i = 0
                while (i < samples) {
                    val v = src[i]
                    val scaled = when {
                        !v.isFinite() -> 0
                        v >= 1f -> 8388607
                        v <= -1f -> -8388608
                        else -> (v * SCALE_24).roundToInt().coerceIn(-8388608, 8388607)
                    }
                    if (littleEndian) {
                        outBuffer.put((scaled and 0xFF).toByte())
                        outBuffer.put(((scaled ushr 8) and 0xFF).toByte())
                        outBuffer.put(((scaled ushr 16) and 0xFF).toByte())
                    } else {
                        outBuffer.put(((scaled ushr 16) and 0xFF).toByte())
                        outBuffer.put(((scaled ushr 8) and 0xFF).toByte())
                        outBuffer.put((scaled and 0xFF).toByte())
                    }
                    i++
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val ints = if (intScratch.size >= samples) intScratch else IntArray(samples)
                var i = 0
                while (i < samples) {
                    val v = src[i]
                    ints[i] = when {
                        !v.isFinite() -> 0
                        v >= 1f -> Int.MAX_VALUE
                        v <= -1f -> Int.MIN_VALUE
                        else -> (v * SCALE_32).roundToLong()
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    }
                    i++
                }
                outBuffer.asIntBuffer().put(ints, 0, samples)
                outBuffer.position(remainingBytes)
            }
            else -> {
                outBuffer.asFloatBuffer().put(src, 0, samples)
                outBuffer.position(remainingBytes)
            }
        }
        outBuffer.flip()
        return seed to shorts
    }

    private fun xorshift32(state: Int): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }
}

/**
 * 64 位 RBJ biquad 设计与 DF2T 状态处理器。
 * Double 精度避免 96/192 kHz 下 31/62 Hz 低频极点的量化漂移。
 */
internal object BiquadDesign {
    val UNITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)

    fun peaking(f0: Double, gainDb: Double, sampleRate: Int, q: Double): DoubleArray {
        val fs = sampleRate.toDouble()
        if (!gainDb.isFinite() || gainDb == 0.0 || f0 <= 0.0 || fs <= 0.0 || f0 >= fs * 0.5 || q <= 0.0) {
            return UNITY.copyOf()
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha / a
        return doubleArrayOf(
            (1.0 + alpha * a) / a0,
            (-2.0 * cosW) / a0,
            (1.0 - alpha * a) / a0,
            (-2.0 * cosW) / a0,
            (1.0 - alpha / a) / a0
        )
    }
}

internal class DoubleBiquad(channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun setCoeffs(c: DoubleArray) {
        b0 = c[0]
        b1 = c[1]
        b2 = c[2]
        a1 = c[3]
        a2 = c[4]
    }

    fun processSample(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }
}
