package com.whale.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * 统一的圆形图标按钮：按下时整体缩小 + 白色高光扩散 + 边缘亮起，松手弹回。
 *
 * - 传 [backdrop] 走液态玻璃（drawBackdrop）外观，适合叠在内容之上的控件；
 * - 不传则用半透明白色圆底，适合在 layerBackdrop 采样层内部使用（避免自采样循环）。
 *
 * 点击区域等于 [buttonSize]，默认 48dp，满足最小触控尺寸。
 */
@Composable
fun PressableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    baseAlpha: Float = 0.10f,
    content: @Composable () -> Unit
) {
    val press = remember { Animatable(0f) }
    val p = press.value
    val currentOnClick by rememberUpdatedState(onClick)

    val appearance = if (backdrop != null) {
        Modifier.liquidGlass(
            backdrop,
            CircleShape,
            press = p,
            tintColor = Color.White,
            tintAlpha = 0.14f,
            withInnerShadow = true
        )
    } else {
        Modifier.drawBehind {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            if (p > 0.001f) {
                // 高光从中心向外扩散
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.32f * p),
                            Color.White.copy(alpha = 0.05f * p)
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.38f * p),
                    radius = radius - 1f,
                    center = center,
                    style = Stroke(width = 1.5f)
                )
            } else {
                drawCircle(
                    color = Color.White.copy(alpha = baseAlpha),
                    radius = radius,
                    center = center
                )
            }
        }
    }

    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                val scale = 1f - 0.11f * p
                scaleX = scale
                scaleY = scale
            }
            .then(appearance)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        press.animateTo(1f, tween(durationMillis = 70))
                        tryAwaitRelease()
                        press.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 700f))
                    },
                    onTap = { currentOnClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
