package com.agent.arena.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 나타날 때: 투명→불투명 + 아래에서 살짝 올라오며 + 블러가 풀리는 입장 효과.
 * (블러는 Android 12+ 에서만 보이고, 그 이하에서는 페이드/이동만 적용된다.)
 */
@Composable
fun Modifier.reveal(
    durationMs: Int = 520,
    rise: Dp = 14.dp,
    blurRadius: Dp = 10.dp,
    delayMs: Int = 0,
): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs, delayMs, SmoothEasing)) }
    val v = p.value
    return this
        .graphicsLayer {
            alpha = v
            translationY = (1f - v) * rise.toPx()
        }
        .then(if (v < 0.99f) Modifier.blur(blurRadius * (1f - v), BlurredEdgeTreatment.Unbounded) else Modifier)
}

/** AnimatedVisibility / AnimatedContent 안에서 등장·퇴장 전환 중에만 블러를 거는 Modifier. */
@Composable
fun AnimatedVisibilityScope.transitionBlur(max: Dp = 14.dp, durationMs: Int = 560): Modifier {
    val r by transition.animateDp(
        transitionSpec = { tween(durationMs, easing = SmoothEasing) },
        label = "transitionBlur",
    ) { state -> if (state == EnterExitState.Visible) 0.dp else max }
    return if (r > 0.2.dp) Modifier.blur(r, BlurredEdgeTreatment.Unbounded) else Modifier
}

/** 눌림에 반응하는 탄성 스케일 + 클릭. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.9f,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}
