package com.agent.arena.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color

/** 순수 검정 / 포인트 블루 / 블랙그레이 / 화이트. */
object Pal {
    val bg = Color(0xFF000000)
    val surface = Color(0xFF121212)
    val surfaceHigh = Color(0xFF1C1C1E)
    val border = Color(0xFF2A2A2D)
    val text = Color(0xFFFFFFFF)
    val textSoft = Color(0xFFE4E4E7)
    val sub = Color(0xFF8E8E93)
    val accent = Color(0xFF3B82F6)
    val error = Color(0xFFF2555A)
}

/** 빠르게 시작해서 길게 감속하는 부드러운 이징. */
val SmoothEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
