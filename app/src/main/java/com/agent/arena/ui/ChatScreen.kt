package com.agent.arena.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.arena.AgentAccessibilityService
import com.agent.arena.AgentApp
import com.agent.arena.AgentViewModel
import com.agent.arena.ChatMessage
import com.agent.arena.Role
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: AgentViewModel, onOpenAccessibility: () -> Unit) {
    val ctl = AgentApp.instance.controller
    val a11y by AgentAccessibilityService.connected.collectAsState()
    val listState = rememberLazyListState()
    val empty = vm.messages.isEmpty()

    Column(
        Modifier.fillMaxSize().background(Pal.bg)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        TopBar(
            a11yOn = a11y,
            onSettings = onOpenAccessibility,
            onTitle = { ctl.toggleWeb() },
            onNew = { vm.newChat() },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!empty) {
                MessageList(vm, listState)
            }

            // 인사말: 입력창 위의 남는 영역 가운데에 배치 → 입력창/키보드에 가려지지 않는다.
            val greetState = remember { MutableTransitionState(false) }
            greetState.targetState = empty
            AnimatedVisibility(
                visibleState = greetState,
                enter = fadeIn(tween(720, 90, SmoothEasing)) + slideInVertically(tween(720, 90, SmoothEasing)) { it / 12 },
                exit = fadeOut(tween(240)) + slideOutVertically(tween(320, easing = SmoothEasing)) { -it / 14 },
            ) {
                Box(Modifier.fillMaxSize().then(transitionBlur(max = 14.dp, durationMs = 640))) {
                    EmptyState(vm.greeting, a11y, onOpenAccessibility)
                }
            }
        }

        InputBar(
            text = vm.draft,
            onTextChange = { vm.draft = it },
            running = vm.running,
            onSend = { val t = vm.draft; vm.draft = ""; vm.send(t) },
            onStop = { vm.stop() },
        )
    }
}

// ------------------------------------------------------------------ 상단 바

@Composable
private fun TopBar(a11yOn: Boolean, onSettings: () -> Unit, onTitle: () -> Unit, onNew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).pressable(onClick = onSettings), contentAlignment = Alignment.Center) {
            SlidersIcon(Pal.textSoft, Modifier.size(21.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val dot by animateColorAsState(if (a11yOn) Pal.accent else Pal.sub, tween(350), label = "statusDot")
            Row(
                Modifier.clip(RoundedCornerShape(50)).pressable(pressedScale = 0.95f, onClick = onTitle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(9.dp))
                BasicText("Arena Agent", style = TextStyle(color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            }
        }
        Box(Modifier.size(48.dp).pressable(onClick = onNew), contentAlignment = Alignment.Center) {
            PlusIcon(Pal.textSoft, Modifier.size(22.dp))
        }
    }
}

// ------------------------------------------------------------------ 인사말(빈 화면)

@Composable
private fun EmptyState(greeting: String, a11yOn: Boolean, onOpenAccessibility: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 키보드가 올라와 공간이 좁아지면 부드럽게 사라져서 입력창과 겹치지 않는다.
        val roomy by animateFloatAsState(if (maxHeight > 190.dp) 1f else 0f, tween(260), label = "greetRoom")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp).graphicsLayer { alpha = roomy },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val spin = rememberInfiniteTransition(label = "spark")
            val rot by spin.animateFloat(
                0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "sparkRot",
            )
            SparkIcon(Pal.accent, Modifier.size(34.dp).graphicsLayer { rotationZ = rot })
            Spacer(Modifier.height(18.dp))
            AnimatedContent(
                targetState = greeting,
                transitionSpec = {
                    (fadeIn(tween(600, 140, SmoothEasing)) + slideInVertically(tween(600, 140, SmoothEasing)) { it / 3 }) togetherWith
                        (fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it / 4 })
                },
                label = "greeting",
            ) { g ->
                BasicText(
                    g,
                    modifier = Modifier.fillMaxWidth().then(transitionBlur(max = 12.dp, durationMs = 520)),
                    style = TextStyle(
                        color = Pal.text, fontSize = 29.sp, lineHeight = 38.sp,
                        fontFamily = FontFamily.Serif, textAlign = TextAlign.Center,
                    ),
                )
            }
            AnimatedVisibility(
                visible = !a11yOn,
                enter = fadeIn(tween(500, 300)) + expandVertically(tween(400)),
                exit = fadeOut(tween(250)) + shrinkVertically(tween(300)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(26.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, Pal.border, RoundedCornerShape(50))
                            .pressable(pressedScale = 0.95f, onClick = onOpenAccessibility)
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                    ) {
                        BasicText("접근성 서비스 켜기", style = TextStyle(color = Pal.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 메시지 목록

@Composable
private fun MessageList(vm: AgentViewModel, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    var stick by remember { mutableStateOf(true) }

    // 사용자가 직접 스크롤을 멈춘 위치가 맨 아래일 때만 자동 따라가기
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) stick = !listState.canScrollForward
        }
    }
    LaunchedEffect(vm.messages.size) {
        stick = true
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(vm.messages, key = { _, m -> m.id }) { i, m ->
                val isLast = i == vm.messages.lastIndex
                Box(
                    Modifier.fillMaxWidth().then(
                        if (isLast) Modifier.onSizeChanged {
                            if (stick) scope.launch { listState.scrollBy(100_000f) }
                        } else Modifier
                    )
                ) { MessageItem(m) }
            }
        }
        // 위/아래 가장자리 페이드
        Box(
            Modifier.fillMaxWidth().height(16.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Pal.bg, Color.Transparent)))
        )
    }
}

@Composable
private fun MessageItem(m: ChatMessage) {
    if (m.role == Role.USER) {
        Row(Modifier.fillMaxWidth().reveal(durationMs = 460, rise = 14.dp, blurRadius = 8.dp), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp))
                    .background(Pal.surfaceHigh).padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                BasicText(m.text, style = TextStyle(color = Pal.text, fontSize = 16.sp, lineHeight = 23.sp))
            }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = 420f)),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            m.steps.forEachIndexed { i, s -> androidx.compose.runtime.key(i) { StepRow(s) } }
            if (m.text.isNotBlank()) {
                StreamingText(
                    text = m.text,
                    streaming = m.streaming,
                    color = if (m.isError) Pal.error else Pal.text,
                )
            }
            AnimatedVisibility(
                visible = m.streaming && (m.text.isBlank() || m.planning),
                enter = fadeIn(tween(280)) + expandVertically(tween(320, easing = SmoothEasing)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(260)),
            ) {
                ThinkingRow(
                    when {
                        m.planning -> "작업 계획 중"
                        m.steps.isEmpty() -> "생각하는 중"
                        else -> "다음 작업 생각 중"
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 입력창

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = text.isNotBlank()
    val shape = RoundedCornerShape(28.dp)

    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Pal.surface).border(1.dp, Pal.border, shape)
                // 줄 수가 늘어날 때 컨테이너가 스프링처럼 부드럽게 팽창
                .animateContentSize(spring(dampingRatio = 0.82f, stiffness = 380f)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    AnimatedVisibility(
                        visible = text.isEmpty(),
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(120)),
                    ) {
                        BasicText("무엇이든 부탁하세요", style = TextStyle(color = Pal.sub, fontSize = 16.sp))
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(color = Pal.text, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(Pal.accent),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SendStopButton(running = running, canSend = canSend, onClick = { if (running) onStop() else onSend() })
            }
        }
    }
}

@Composable
private fun SendStopButton(running: Boolean, canSend: Boolean, onClick: () -> Unit) {
    val active = running || canSend
    val bg by animateColorAsState(if (active) Pal.accent else Pal.surfaceHigh, tween(260), label = "sendBg")
    val enable by animateFloatAsState(if (active) 1f else 0.86f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "sendScale")
    Box(
        Modifier.size(38.dp).graphicsLayer { scaleX = enable; scaleY = enable }
            .clip(CircleShape).background(bg)
            .pressable(enabled = active, pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = running,
            transitionSpec = {
                (fadeIn(tween(200, 70)) + scaleIn(tween(240, 70, SmoothEasing), initialScale = 0.5f)) togetherWith
                    (fadeOut(tween(110)) + scaleOut(tween(130), targetScale = 0.5f))
            },
            label = "sendStop",
        ) { isRunning ->
            if (isRunning) StopIcon(Color.White, Modifier.size(17.dp))
            else ArrowUpIcon(if (active) Color.White else Pal.sub, Modifier.size(18.dp))
        }
    }
}
