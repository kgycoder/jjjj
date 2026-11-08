package com.agent.arena.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ 간단 마크다운 파서

private sealed class Block {
    data class Para(val text: String) : Block()
    data class Code(val lang: String, val body: String) : Block()
}

private fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val parts = src.split("```")
    for ((i, part) in parts.withIndex()) {
        if (i % 2 == 0) {
            part.split(Regex("\\n{2,}")).map { it.trim('\n') }.filter { it.isNotBlank() }.forEach { out.add(Block.Para(it)) }
        } else {
            val nl = part.indexOf('\n')
            val lang = if (nl >= 0) part.substring(0, nl).trim() else part.trim()
            val body = if (nl >= 0) part.substring(nl + 1).trimEnd('\n') else ""
            out.add(Block.Code(lang, body))
        }
    }
    return out
}

private fun inlineMd(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val buf = StringBuilder()
    var bold = false
    var mono = false
    fun flush() {
        if (buf.isEmpty()) return
        val st = when {
            mono -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)
            bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
            else -> null
        }
        if (st != null) withStyle(st) { append(buf.toString()) } else append(buf.toString())
        buf.setLength(0)
    }
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) { flush(); bold = !bold; i += 2; continue }
        if (s[i] == '`') { flush(); mono = !mono; i++; continue }
        buf.append(s[i]); i++
    }
    flush()
}

private val headingRe = Regex("^#{1,4}\\s+(.*)$")
private val bulletRe = Regex("^\\s*[-*]\\s+(.*)$")

private fun prettyLines(p: String): String = p.split("\n").joinToString("\n") { line ->
    val m = bulletRe.find(line)
    if (m != null) "•  " + m.groupValues[1] else line
}

// ------------------------------------------------------------------ 스트리밍 텍스트

/**
 * 답변 스트리밍 텍스트.
 * - 도착한 글자를 한 번에 붙이지 않고 프레임마다 부드럽게 풀어낸다(밀리면 자동으로 속도가 올라감).
 * - 가장 최근 글자들은 투명→불투명 그라데이션으로 번지듯 나타난다.
 * - 새 문단/코드블록은 블러가 풀리며 등장한다.
 */
@Composable
fun StreamingText(
    text: String,
    streaming: Boolean,
    color: Color = Pal.text,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableFloatStateOf(if (streaming) 0f else text.length.toFloat()) }
    val target by rememberUpdatedState(text.length)

    LaunchedEffect(streaming) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = now
            val gap = target - shown
            if (gap > 0f) {
                // 남은 양에 비례해 가속: 평소엔 또박또박, 밀리면 빠르게 따라잡는다.
                val speed = max(55f, gap * 5.5f)
                shown = min(target.toFloat(), shown + speed * dt)
            } else if (gap < 0f) {
                shown = target.toFloat()
            } else if (!streaming) {
                break
            }
        }
    }

    val n = shown.toInt().coerceIn(0, text.length)
    val visible = text.substring(0, n)
    val revealing = streaming || shown < text.length - 0.5f
    val frac = shown - floor(shown)
    val blocks = parseBlocks(visible)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { i, b ->
            key(i) {
                val isLast = i == blocks.lastIndex
                Box(Modifier.reveal(durationMs = 420, rise = 6.dp, blurRadius = 8.dp)) {
                    when (b) {
                        is Block.Para -> ParaView(b.text, color, if (isLast && revealing) frac else -1f)
                        is Block.Code -> CodeView(b)
                    }
                }
            }
        }
    }
}

private const val TAIL = 18

@Composable
private fun ParaView(p: String, color: Color, tailFrac: Float) {
    val heading = headingRe.find(p.trim())
    val isHeading = heading != null && !p.trim().contains('\n')
    val raw = if (isHeading) heading!!.groupValues[1] else prettyLines(p)
    val base = inlineMd(raw, Pal.surfaceHigh)
    val annotated = if (tailFrac < 0f) base else buildAnnotatedString {
        append(base)
        val len = base.length
        for (k in 0 until min(TAIL, len)) {
            val a = ((k + tailFrac) / TAIL).coerceIn(0.06f, 1f)
            val idx = len - 1 - k
            addStyle(SpanStyle(color = color.copy(alpha = a)), idx, idx + 1)
        }
    }
    BasicText(
        text = annotated,
        style = if (isHeading) TextStyle(color = color, fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
        else TextStyle(color = color, fontSize = 16.sp, lineHeight = 25.sp),
    )
}

@Composable
private fun CodeView(b: Block.Code) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.surface),
    ) {
        if (b.lang.isNotBlank()) {
            BasicText(
                b.lang,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp),
                style = TextStyle(color = Pal.sub, fontSize = 12.sp),
            )
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) {
            BasicText(
                b.body,
                style = TextStyle(color = Pal.textSoft, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace),
            )
        }
    }
}

// ------------------------------------------------------------------ 상태 표시 조각

/** 흰빛이 좌→우로 흐르는 "생각 중" 텍스트. */
@Composable
fun ShimmerText(text: String, size: TextUnit = 15.sp) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -260f, targetValue = 700f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val brush = Brush.linearGradient(
        colors = listOf(Pal.sub, Color.White, Pal.sub),
        start = Offset(x, 0f), end = Offset(x + 240f, 0f),
    )
    BasicText(text, style = TextStyle(brush = brush, fontSize = size))
}

@Composable
fun ThinkingRow(label: String) {
    val t = rememberInfiniteTransition(label = "thinkDot")
    val a by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
        label = "thinkAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(Pal.accent))
        Spacer(Modifier.width(10.dp))
        ShimmerText(label)
    }
}

@Composable
fun StepRow(text: String) {
    Row(Modifier.reveal(durationMs = 380, rise = 6.dp, blurRadius = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckIcon(Pal.accent, Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        BasicText(text, style = TextStyle(color = Pal.sub, fontSize = 14.sp, lineHeight = 20.sp))
    }
}
