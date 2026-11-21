package com.whale.music.player.audio

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 真实音频频谱数据源：数据来自 [PcmTapProcessor]，自己做 FFT。
 *
 * 移植自 gxclsl/Sonance（MIT License）playback/AudioVisualizer.kt。
 *
 * 性能约定：
 * - 本类**不做线程切换**，调用方负责在后台线程调 [poll]（UI 侧用 Dispatchers.Default）。
 * - [poll] 内部有「新数据门控」：PCM 缓冲没更新就直接返回，不跑 FFT。
 * - 所有工作区（FFT 数组、窗函数、频段表）构造时分配一次，[poll] 内零分配。
 */
class AudioVisualizer(barCount: Int) {

    val bars = barCount.coerceIn(16, 128)

    /** 已平滑的柱高 0..1，绘制线程直接读；Float 写入是原子的，无需加锁 */
    val levels = FloatArray(bars)

    /** 归一化前的原始柱高，[applyAutoGain] 的输入 */
    private val rawBands = FloatArray(bars)

    /**
     * EMA 平滑后的频段能量，放在 AGC **之前**：先滤噪声再归一化，
     * 峰值追踪才不会被单个毛刺顶高。
     */
    private val emaBands = FloatArray(bars)

    /** EMA 系数 0..1：越小越平滑、越迟钝。0.55 只滤逐帧抖动，不拖慢打击感 */
    var emaAlpha: Float = 0.55f

    /**
     * 每根柱子自己的历史峰值，缓慢衰减。
     * 音乐能量天然集中在低频，左几根总是跳得凶；逐频段归一化把动态范围拉平。
     */
    private val bandPeak = FloatArray(bars)

    /** 是否有真实数据源（PCM 取样器已拿到格式并收到数据） */
    val available: Boolean
        get() = PcmBus.ready

    // ===== FFT 工作区：构造时分配一次 =====
    private val fftN = 1024
    private val re = FloatArray(fftN)
    private val im = FloatArray(fftN)
    private val hann = FloatArray(fftN)
    private val twRe = FloatArray(fftN / 2)
    private val twIm = FloatArray(fftN / 2)

    /** 参与映射的 bin 数：高频区基本空，砍掉 1/4 */
    private val usable = (fftN / 2 * 3) / 4

    /** 对数压缩的归一化分母 */
    private val maxLog = ln(1f + usable.toFloat())

    /**
     * 每根柱子对应的 bin 区间起点（长度 bars+1，最后一个是哨兵）。
     * 预计算避免逐帧调 logIndex()（内含 exp/ln，每帧上百次超越函数）。
     */
    private val bandEdges = IntArray(bars + 1)

    private var lastSeenBuffers = -1L
    private var lastDataAtMs = 0L

    init {
        var k = 0
        while (k < fftN / 2) {
            val a = -2.0 * PI * k / fftN
            twRe[k] = cos(a).toFloat()
            twIm[k] = sin(a).toFloat()
            k++
        }
        var i = 0
        while (i < fftN) {
            hann[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (fftN - 1))).toFloat()
            i++
        }
        // 频段边界：对数分布后量化成**严格递增**的整数边界 —— 相邻柱子若落在同一
        // bin 上高度完全相同，视觉上会糊成一块。保证每根柱子至少分到 1 个独立 bin。
        var prev = 0
        i = 0
        while (i <= bars) {
            val raw = logIndex(i, bars, usable)
            val v = maxOf(raw, prev + 1).coerceAtMost(usable)
            bandEdges[i] = v
            prev = v
            i++
        }
        bandEdges[bars] = usable
    }

    fun stop() {
        levels.fill(0f)
    }

    /**
     * 拉取一帧并更新 [levels]。**必须在后台线程调用。**
     *
     * @param level 律动幅度 0.5..1.0（整套映射之后等比缩放柱高）
     * @param active 播放中才输出能量；暂停时目标值归零
     * @return true 表示当前有有效数据，false 表示应走 [decayToZero]
     */
    fun poll(level: Float, active: Boolean): Boolean {
        if (!PcmBus.ready) return false

        val seen = PcmBus.buffersSeen
        val now = SystemClock.elapsedRealtime()

        if (seen == lastSeenBuffers) {
            // 没有新 PCM：不跑 FFT，只要还在「活跃窗口」内就沿用上一帧结果
            return now - lastDataAtMs <= 300L
        }
        lastSeenBuffers = seen
        lastDataAtMs = now

        if (PcmBus.ring.readLatest(re, fftN) < fftN) return false

        var i = 0
        while (i < fftN) {
            re[i] *= hann[i]
            im[i] = 0f
            i++
        }
        fft(re, im)
        computeBands(level, active)
        return true
    }

    /**
     * 没有真实数据时把**目标值**拉向 0；柱子实际怎么落由调用方按帧插值决定。
     */
    fun decayToZero() {
        var i = 0
        while (i < levels.size) {
            levels[i] *= 0.86f
            emaBands[i] *= 0.86f
            i++
        }
    }

    /**
     * FFT → 柱高（原始目标值，不做帧间平滑）。
     * FFT 只有 20~40Hz（跟音频缓冲走），平滑交给绘制侧按帧插值。
     */
    private fun computeBands(level: Float, active: Boolean) {
        val bins = fftN / 2
        val n = levels.size

        var b = 0
        while (b < n) {
            val lo = bandEdges[b]
            val hi = bandEdges[b + 1]
            var sumSq = 0f
            var count = 0
            var k = lo
            while (k < hi && k < bins) {
                val rr = re[k]
                val ii = im[k]
                sumSq += rr * rr + ii * ii
                count++
                k++
            }
            val avg = if (count > 0) sqrt(sumSq / count) * FFT_SCALE else 0f
            // 不在 AGC 前截断：真实幅度要留给 AGC 区分「刚好到顶」和「远超满格」
            var v = ln(1f + avg) / maxLog * BAND_GAIN
            if (!active) v = 0f

            // EMA 平滑消掉逐帧抖动噪点
            val a = emaAlpha
            emaBands[b] += (v - emaBands[b]) * a
            rawBands[b] = emaBands[b]
            b++
        }
        applyAutoGain(rawBands, bandPeak, levels, level)
    }

    /**
     * 逐频段自动增益 + 顶部余量：
     * 1. 混合参考 `0.65 × 本柱峰值 + 0.35 × 全频段峰值`，顶端重新变成「事件」；
     * 2. 幂次 1.30 只压中段、不动峰值；
     * 3. 软顶：低于 0.52 线性，高于按 tanh 收敛到 0.82，避免一排平头柱。
     * 峰值快起慢落（0.992/帧），太小时直接用原始值避免除零放大噪声。
     */
    internal fun applyAutoGain(raw: FloatArray, peaks: FloatArray, out: FloatArray, level: Float = 1f) {
        val n = minOf(raw.size, peaks.size, out.size)
        if (n == 0) return

        var globalPeak = 0f
        var i = 0
        while (i < n) {
            val v = raw[i]
            val p = peaks[i]
            val peak = if (v > p) v else p * PEAK_DECAY
            peaks[i] = peak
            if (peak > globalPeak) globalPeak = peak
            i++
        }
        val globalValid = globalPeak > MIN_PEAK
        val gain = level.coerceIn(0f, MAX_LEVEL)

        i = 0
        while (i < n) {
            val v = raw[i].coerceAtLeast(0f)
            val peak = peaks[i]
            val normLocal = if (peak > MIN_PEAK) (v / peak).coerceIn(0f, 1f) else v.coerceAtMost(1f)
            val normGlobal = if (globalValid) (v / globalPeak).coerceIn(0f, 1f) else v.coerceAtMost(1f)
            val norm = LOCAL_PEAK_WEIGHT * normLocal + (1f - LOCAL_PEAK_WEIGHT) * normGlobal
            val shaped = norm.pow(NORM_GAMMA)
            val mixed = NORM_WEIGHT * shaped + (1f - NORM_WEIGHT) * v.coerceAtMost(1f)
            out[i] = displayHeight(mixed) * gain
            i++
        }
    }

    /** 软顶：低于拐点线性，高于拐点 tanh 收敛到 [CEILING]，保留区分度而不是硬切平 */
    private fun displayHeight(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        val soft = if (v <= SOFT_KNEE) {
            v
        } else {
            val t = (v - SOFT_KNEE) / (1f - SOFT_KNEE)
            SOFT_KNEE + (1f - SOFT_KNEE) * tanh(t * KNEE_CURVE) / tanh(KNEE_CURVE)
        }
        return (CEILING * soft).coerceIn(0f, CEILING)
    }

    /** 第 i 根柱子在频率 bin 上的对数起点（只在构造时调用） */
    private fun logIndex(i: Int, total: Int, usable: Int): Int {
        val t = i.toFloat() / total.toFloat()
        val v = 1f + (usable - 1f) * (exp(ln(usable.toFloat()) * t) - 1f) /
            (usable.toFloat() - 1f)
        return v.toInt().coerceIn(1, usable - 1)
    }

    /** 迭代式 radix-2 Cooley-Tukey FFT（原位），旋转因子查表避免逐级累乘误差 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size

        var j = 0
        var i = 1
        while (i < n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            i++
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                while (k < half) {
                    val w = k * step
                    val even = start + k
                    val odd = even + half
                    val tRe = re[odd] * twRe[w] - im[odd] * twIm[w]
                    val tIm = re[odd] * twIm[w] + im[odd] * twRe[w]
                    re[odd] = re[even] - tRe
                    im[odd] = im[even] - tIm
                    re[even] += tRe
                    im[even] += tIm
                    k++
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** 满幅正弦峰值 bin ≈ N/2，乘它后量级稳定在 ~256，与旧字典式 FFT 手感一致 */
        private const val FFT_SCALE = 0.5f

        /** 历史峰值每帧衰减；0.992 在 30Hz 更新下约 3 秒半衰 */
        private const val PEAK_DECAY = 0.992f

        /** 低于这个峰值不做归一化，避免除零放大噪声 */
        private const val MIN_PEAK = 0.06f

        /** 对数高度固定增益：1.6 把饱和点推到约 -11 dBFS，只有强拍接近顶部 */
        private const val BAND_GAIN = 1.6f

        /** 归一化里本柱峰值的权重，其余 0.35 交给全频段峰值 */
        private const val LOCAL_PEAK_WEIGHT = 0.65f

        /** 归一化项在最终混合里的权重；其余留给原始幅度，保留「这段整体很轻」的层次 */
        private const val NORM_WEIGHT = 0.70f

        /** 归一化幂次：压中段、不动峰值 */
        private const val NORM_GAMMA = 1.30f

        /** 软拐点：低于保持线性，高于 tanh 收敛 */
        private const val SOFT_KNEE = 0.52f

        /** 满格映射到的最终高度，柱顶与顶栏之间留可见余量 */
        private const val CEILING = 0.82f

        /** 软顶弯曲程度 */
        private const val KNEE_CURVE = 2f

        /** 律动幅度上限兜底 */
        private const val MAX_LEVEL = 1.5f
    }
}
