package com.agent.arena.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PlusIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.dp.toPx()
    drawLine(color, Offset(size.width * 0.15f, size.height / 2), Offset(size.width * 0.85f, size.height / 2), sw, StrokeCap.Round)
    drawLine(color, Offset(size.width / 2, size.height * 0.15f), Offset(size.width / 2, size.height * 0.85f), sw, StrokeCap.Round)
}

@Composable
fun SlidersIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.1f, h * 0.32f), Offset(w * 0.9f, h * 0.32f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.1f, h * 0.68f), Offset(w * 0.9f, h * 0.68f), sw, StrokeCap.Round)
    drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(w * 0.35f, h * 0.32f))
    drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(w * 0.68f, h * 0.68f))
}

@Composable
fun ArrowUpIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.2f.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w / 2, h * 0.88f), Offset(w / 2, h * 0.14f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.16f, h * 0.46f), Offset(w / 2, h * 0.12f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.84f, h * 0.46f), Offset(w / 2, h * 0.12f), sw, StrokeCap.Round)
}

@Composable
fun StopIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    drawRoundRect(
        color, topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
        size = Size(size.width * 0.64f, size.height * 0.64f), cornerRadius = CornerRadius(3.dp.toPx()),
    )
}

@Composable
fun CheckIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 1.8f.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.2f, h * 0.54f), Offset(w * 0.42f, h * 0.74f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.42f, h * 0.74f), Offset(w * 0.8f, h * 0.28f), sw, StrokeCap.Round)
}

/** Claude 의 반짝이(스파크) 느낌의 8갈래 별. */
@Composable
fun SparkIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension / 2f * 0.94f
    val sw = size.minDimension * 0.105f
    for (i in 0 until 4) {
        val a = Math.toRadians(i * 45.0)
        val dx = (cos(a) * r).toFloat()
        val dy = (sin(a) * r).toFloat()
        drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), sw, StrokeCap.Round)
    }
}
