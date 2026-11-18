package com.whale.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 卡片类控件的按压缩放反馈（列表项、歌单封面等）。
 * 配合 [MutableInteractionSource] 使用：手势里 emit PressInteraction，这里读状态做缩放。
 */
@Composable
fun Modifier.pressScale(
    source: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "cardPressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
