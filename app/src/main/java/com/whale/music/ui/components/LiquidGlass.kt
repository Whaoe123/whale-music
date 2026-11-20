package com.whale.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.whale.music.util.GlassPrefs

/**
 * 液态玻璃统一材质 —— 全 app 唯一配方。
 *
 * 移植自 AuraPlayer（https://github.com/ColdP/AuraPlayer，AGPL-3.0-or-later）
 * MiniPlayerMaterial.kt 玻璃分支：vibrancy + blur + lens(depthEffect) + 高光/投影，
 * 高光/投影随 [expansion]（卡片长成播放页）淡出。
 *
 * 五个全局参数（折射 / 深度厚度 / 色散 / 背景模糊 / 高光反射）由 [GlassPrefs] 持久化，
 * 设置页「液态玻璃」实时调节，所有玻璃控件同步生效。
 */

/** 换算好的玻璃配方（dp / alpha），由 [rememberGlassTuning] 读全局偏好得到。 */
@Stable
data class GlassTuning(
    /** 采样模糊半径（基准 4.3dp @100%） */
    val blur: Dp,
    /** lens 厚度（基准 16dp @100%） */
    val thickness: Dp,
    /** lens 折射（基准 32dp @100%） */
    val refraction: Dp,
    /** 边缘高光 alpha（基准 0.78 @100%） */
    val highlight: Float,
    /** 色散（chromatic aberration） */
    val dispersion: Boolean
)

@Composable
fun rememberGlassTuning(): GlassTuning {
    val blur by GlassPrefs.blur.collectAsState()
    val thickness by GlassPrefs.thickness.collectAsState()
    val refraction by GlassPrefs.refraction.collectAsState()
    val highlight by GlassPrefs.highlight.collectAsState()
    val dispersion by GlassPrefs.dispersion.collectAsState()
    return GlassTuning(
        blur = 4.3f.dp * blur / 100f,
        thickness = 16f.dp * thickness / 100f,
        refraction = 32f.dp * refraction / 100f,
        highlight = 0.78f * highlight / 100f,
        dispersion = dispersion
    )
}

/**
 * 液态玻璃材质。
 *
 * @param expansion 0 = 卡片态（高光/投影全开），1 = 展开成播放页（玻璃壳退场）
 * @param press 按压增强 0..1（按下时折射/高光增强，松手回落）
 * @param tintColor 表面底色（卡片/播放页过渡用黑，按钮用白）
 * @param tintAlpha 表面底色浓度
 * @param shadowAlpha 投影浓度（基准 0.35）
 * @param withInnerShadow 是否带内阴影（按钮类控件加一点立体感）
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    expansion: Float = 0f,
    press: Float = 0f,
    tintColor: Color = Color.Black,
    tintAlpha: Float = 0.30f,
    shadowAlpha: Float = 0.35f,
    withInnerShadow: Boolean = false,
    layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null
): Modifier {
    val tuning = rememberGlassTuning()
    val fade = (1f - expansion).coerceIn(0f, 1f)
    val boost = press.coerceIn(0f, 1f)
    // 按压时折射/厚度增强；松手回到基准
    val lensScale = 0.45f + 0.55f * boost
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(tuning.blur.toPx())
            lens(
                tuning.thickness.toPx() * lensScale,
                tuning.refraction.toPx() * lensScale,
                chromaticAberration = tuning.dispersion,
                depthEffect = true
            )
        },
        highlight = {
            Highlight.Default.copy(
                alpha = tuning.highlight * (0.6f + 0.4f * boost) * fade
            )
        },
        shadow = {
            Shadow.Default.copy(
                radius = 6.dp,
                alpha = shadowAlpha * (0.6f + 0.6f * boost) * fade
            )
        },
        innerShadow = if (withInnerShadow) {
            {
                InnerShadow(
                    radius = (4f + 4f * boost).dp,
                    alpha = (0.14f + 0.22f * boost) * fade
                )
            }
        } else {
            // 库参数非空：不用内阴影时给零强度空实现（alpha=0 不可见）
            { InnerShadow(radius = 0f.dp, alpha = 0f) }
        },
        layerBlock = layerBlock ?: {},
        onDrawSurface = {
            drawRect(tintColor.copy(alpha = tintAlpha * (0.85f + 0.35f * boost)))
        }
    )
}
