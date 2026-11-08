package com.agent.arena

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** 화면 읽기(UI 트리 덤프) + 탭/스와이프/입력/전역 키/앱 실행 등 기기 조작 실행기. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Volatile var instance: AgentAccessibilityService? = null
            private set

        private const val MAX_NODES = 160
    }

    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    // Agent Action Cursor (접근성 오버레이)
    private var cursorInst: AgentCursorOverlay? = null
    private val cursor: AgentCursorOverlay
        get() = cursorInst ?: AgentCursorOverlay(this).also { cursorInst = it }

    fun cursorShow(label: String) { cursor.show(); cursor.setLabel(label, CursorState.WORKING) }
    fun cursorLabel(label: String, state: CursorState = CursorState.WORKING) { cursorInst?.setLabel(label, state) }
    fun cursorHide(delayMs: Long = 0) { cursorInst?.hide(delayMs) }

    private suspend fun cursorMoveTo(x: Float, y: Float) {
        cursor.show()
        cursor.moveTo(x, y)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cursorInst?.destroy()
        cursorInst = null
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    @Volatile private var lastEvent = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastEvent = SystemClock.uptimeMillis() }
    override fun onInterrupt() {}

    /**
     * 고정 delay 대신 "화면 변화가 잠잠해질 때까지"만 기다린다.
     * waitForChange=true 이면 호출 이후 첫 이벤트(창 전환 등)가 올 때까지(최대 700ms) 먼저 기다린다.
     */
    suspend fun settle(maxMs: Long = 800, quietMs: Long = 150, waitForChange: Boolean = false) {
        val t0 = SystemClock.uptimeMillis()
        val end = t0 + maxMs
        if (waitForChange) {
            while (lastEvent < t0 && SystemClock.uptimeMillis() < minOf(end, t0 + 700)) delay(25)
        }
        delay(40)
        while (SystemClock.uptimeMillis() < end && SystemClock.uptimeMillis() - lastEvent < quietMs) delay(25)
    }

    // ------------------------------------------------------------ screen dump

    fun dumpScreen(): String {
        val root = rootInActiveWindow
            ?: return "(화면 정보를 읽을 수 없음: 잠금/보안 화면이거나 전환 중일 수 있음. wait 후 다시 확인)"
        val pkg = root.packageName?.toString().orEmpty()
        nodes.clear()
        if (pkg == packageName) {
            return "foreground_package: $pkg\n(현재 앞에 있는 화면은 에이전트 앱 자신입니다. 기기 조작이 필요하면 launch_app 또는 key home 으로 이동하세요.)"
        }
        val sb = StringBuilder()
        sb.appendLine("foreground_package: $pkg")
        var id = 0

        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.replace("\n", " ")?.take(100)
            val desc = n.contentDescription?.toString()?.replace("\n", " ")?.take(100)
            val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() ||
                n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (interesting && id < MAX_NODES) {
                id++
                nodes[id] = n
                val r = Rect()
                n.getBoundsInScreen(r)
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("click")
                if (n.isLongClickable) flags.add("long")
                if (n.isEditable) flags.add("edit")
                if (n.isScrollable) flags.add("scroll")
                if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
                if (n.isFocused) flags.add("focused")
                if (!n.isEnabled) flags.add("disabled")
                sb.append("[$id] ${n.className?.toString()?.substringAfterLast('.')}")
                if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
                if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
                sb.append(" center=(${r.centerX()},${r.centerY()})")
                if (flags.isNotEmpty()) sb.append(" ${flags.joinToString(",")}")
                sb.appendLine()
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        if (id == 0) sb.appendLine("(상호작용 가능한 요소가 보이지 않음)")
        if (id >= MAX_NODES) sb.appendLine("(요소가 많아 일부만 표시됨. scroll 로 더 확인)")
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------ command execution

    /** 명령 1개를 실행하고 사람이 읽을 수 있는 결과 문자열("ok: ..." / "error: ...")을 반환. */
    suspend fun execute(c: JSONObject): String {
        val a = c.optString("action").lowercase().trim()
        return try {
            when (a) {
                "tap", "click" -> tap(c, longPress = false)
                "long_press", "longpress" -> tap(c, longPress = true)
                "swipe" -> swipe(c)
                "scroll" -> scroll(c.optString("direction", "down"))
                "type", "input", "set_text" -> typeText(c)
                "key" -> key(c.optString("key"))
                "back", "home", "recents", "notifications", "quick_settings", "lock_screen" -> key(a)
                "click_text", "tap_text" -> clickText(c)
                "wait_for", "wait_text" -> waitFor(c)
                "wait" -> {
                    val ms = c.optLong("ms", 1000).coerceIn(100, 10_000)
                    delay(ms); "ok: waited ${ms}ms"
                }
                else -> "error: unknown action '$a'"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun tap(c: JSONObject, longPress: Boolean): String {
        val id = c.optInt("id", -1)
        var node: AccessibilityNodeInfo? = null
        val x: Float
        val y: Float
        if (id > 0) {
            val nd = nodes[id] ?: return "error: id $id 는 최신 DEVICE_STATE 에 없음(만료)"
            node = nd
            val r = Rect(); nd.getBoundsInScreen(r)
            x = r.centerX().toFloat(); y = r.centerY().toFloat()
        } else {
            if (!c.has("x") || !c.has("y")) return "error: tap requires id or x,y"
            x = c.getDouble("x").toFloat(); y = c.getDouble("y").toFloat()
        }
        cursorMoveTo(x, y)
        cursor.press()
        if (node != null && !longPress) {
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                settle(700, 150); return "ok: clicked id $id"
            }
        }
        val ok = gesture(point(x, y), if (longPress) 700 else 50)
        settle(700, 150)
        val what = if (longPress) "long-pressed" else "tapped"
        return if (ok) "ok: $what (${x.toInt()},${y.toInt()})" else "error: gesture failed"
    }

    private suspend fun swipe(c: JSONObject): String {
        val x1 = c.getDouble("x1").toFloat(); val y1 = c.getDouble("y1").toFloat()
        val x2 = c.getDouble("x2").toFloat(); val y2 = c.getDouble("y2").toFloat()
        return swipeInternal(x1, y1, x2, y2, c.optLong("duration_ms", 350).coerceIn(50, 3000), "swiped")
    }

    private suspend fun swipeInternal(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long, label: String): String {
        cursorMoveTo(x1, y1)
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        cursor.glideTo(x2, y2) // 제스처가 진행되는 동안 커서도 같이 이동
        val ok = gesture(p, dur)
        settle(800, 160)
        return if (ok) "ok: $label" else "error: gesture failed"
    }

    /** direction = 콘텐츠를 이동하고 싶은 방향 (down: 페이지 아래쪽으로 = 손가락은 위로). */
    private suspend fun scroll(direction: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down" -> swipeInternal(w / 2, h * 0.72f, w / 2, h * 0.28f, 350, "scrolled down")
            "up" -> swipeInternal(w / 2, h * 0.28f, w / 2, h * 0.72f, 350, "scrolled up")
            "right" -> swipeInternal(w * 0.8f, h / 2, w * 0.2f, h / 2, 350, "scrolled right")
            "left" -> swipeInternal(w * 0.2f, h / 2, w * 0.8f, h / 2, 350, "scrolled left")
            else -> "error: direction must be up/down/left/right"
        }
    }

    private suspend fun typeText(c: JSONObject): String {
        val text = c.optString("text")
        val id = c.optInt("id", -1)
        val node: AccessibilityNodeInfo? = when {
            id > 0 -> nodes[id]
            else -> rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: nodes.values.firstOrNull { it.isEditable }
        }
        if (node == null) return "error: 입력 가능한 필드를 찾지 못함 (먼저 필드를 tap 하세요)"
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            cursorMoveTo(r.centerX().toFloat(), r.centerY().toFloat())
            cursor.press()
        }
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val base = if (c.optBoolean("append", false) && !node.isShowingHintText) node.text?.toString().orEmpty() else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "error: ACTION_SET_TEXT 실패"
        if (c.optBoolean("submit", false) && Build.VERSION.SDK_INT >= 30) {
            delay(120)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        settle(400, 120)
        return "ok: typed ${text.length} chars"
    }

    private suspend fun key(k: String): String {
        val action = when (k.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "error: lock_screen requires Android 9+"
            else -> return "error: unknown key '$k'"
        }
        val ok = performGlobalAction(action)
        settle(900, 200, waitForChange = true)
        return if (ok) "ok: key $k" else "error: key $k failed"
    }

    // ------------------------------------------------------------ text based helpers

    private fun labelOf(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    private fun findByText(q: String, exact: Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val list = root.findAccessibilityNodeInfosByText(q) ?: return null
        val vis = list.filter { it.isVisibleToUser }
        return vis.firstOrNull { labelOf(it).equals(q, true) } ?: if (exact) null else vis.firstOrNull()
    }

    /** 화면 덤프 없이 보이는 글자(text/desc)로 바로 클릭. */
    private suspend fun clickText(c: JSONObject): String {
        val q = c.optString("text").trim()
        if (q.isEmpty()) return "error: click_text requires text"
        val exact = c.optBoolean("exact", false)
        // 화면 전환 직후일 수 있으므로 최대 2초까지 나타나기를 기다린다.
        var node: AccessibilityNodeInfo? = null
        val end = SystemClock.uptimeMillis() + c.optLong("timeout_ms", 2000).coerceIn(0, 8000)
        while (true) {
            node = findByText(q, exact)
            if (node != null || SystemClock.uptimeMillis() >= end) break
            delay(120)
        }
        val nd = node ?: return "error: 화면에서 '$q' 를 찾지 못함"
        val r = Rect(); nd.getBoundsInScreen(r)
        val x = r.centerX().toFloat(); val y = r.centerY().toFloat()
        cursorMoveTo(x, y)
        cursor.press()
        var n: AccessibilityNodeInfo? = nd
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            settle(700, 150); return "ok: clicked '$q'"
        }
        val ok = gesture(point(x, y), 50)
        settle(700, 150)
        return if (ok) "ok: tapped '$q' (${x.toInt()},${y.toInt()})" else "error: gesture failed"
    }

    /** 글자가 화면에 나타날 때까지(또는 사라질 때까지) 폴링. 고정 wait 보다 빠르고 정확. */
    private suspend fun waitFor(c: JSONObject): String {
        val q = c.optString("text").trim()
        if (q.isEmpty()) return "error: wait_for requires text"
        val gone = c.optBoolean("gone", false)
        val limit = c.optLong("timeout_ms", 4000).coerceIn(200, 15_000)
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < limit) {
            val found = findByText(q, false) != null
            if (found != gone) { settle(300, 100); return "ok: ${if (gone) "gone" else "found"} '$q' (${SystemClock.uptimeMillis() - t0}ms)" }
            delay(120)
        }
        return "error: timeout waiting for '$q'"
    }

    // ------------------------------------------------------------ gestures

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
                .build()
            val dispatched = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
}
