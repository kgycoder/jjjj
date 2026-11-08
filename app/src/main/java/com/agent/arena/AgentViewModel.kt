package com.agent.arena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Role { USER, AGENT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String = "",
    val steps: List<String> = emptyList(),
    val streaming: Boolean = false,
    val planning: Boolean = false,
    val isError: Boolean = false,
)

class AgentViewModel : ViewModel() {

    companion object { private const val MAX_STEPS = 40 }

    private val app get() = AgentApp.instance
    private val ctl get() = AgentApp.instance.controller

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set

    /** 입력창 초안(화면 전환/회전에도 유지). */
    var draft by mutableStateOf("")

    /** 빈 화면에 보여줄 랜덤 인사말. 새 채팅마다 바뀐다. */
    var greeting by mutableStateOf(Greetings.pick(null))
        private set

    private var job: Job? = null
    private var sessionStarted = false
    private var nextId = 1L

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || running) return
        messages.add(ChatMessage(nextId++, Role.USER, t))
        val idx = messages.size
        messages.add(ChatMessage(nextId++, Role.AGENT, streaming = true))
        running = true
        job = viewModelScope.launch {
            try {
                // 단순 명령(볼륨/손전등/앱 열기 등)은 원격 AI 왕복 없이 즉시 실행
                val fast = try {
                    FastPath.tryRun(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (fast != null) {
                    upd(idx) { it.copy(steps = listOf(fast.summary), text = fast.reply, streaming = false, planning = false) }
                    return@launch
                }
                runAgent(t, idx)
            } finally {
                running = false
                AgentAccessibilityService.instance?.cursorHide(1300)
            }
        }
    }

    fun stop() {
        if (!running) return
        job?.cancel()
        viewModelScope.launch { ctl.stopGeneration() }
    }

    /** 새 채팅 = LMArena URL 을 다시 열어 새 세션 시작. */
    fun newChat() {
        job?.cancel()
        running = false
        messages.clear()
        sessionStarted = false
        greeting = Greetings.pick(greeting)
        viewModelScope.launch {
            ctl.stopGeneration()
            ctl.newChat()
        }
    }

    // ---------------------------------------------------------------- agent loop

    private fun upd(i: Int, f: (ChatMessage) -> ChatMessage) {
        if (i in 0 until messages.size) messages[i] = f(messages[i])
    }

    private fun finish(i: Int, text: String, error: Boolean) {
        upd(i) { it.copy(text = text, streaming = false, planning = false, isError = error) }
    }

    private suspend fun runAgent(userText: String, idx: Int) {
        var outgoing = if (!sessionStarted) AgentPrompt.first(userText, app) else AgentPrompt.followUp(userText)
        var step = 0
        var formatRetries = 0
        try {
            while (true) {
                step++
                if (step > MAX_STEPS) {
                    finish(idx, "최대 단계($MAX_STEPS)에 도달해 작업을 중단했습니다.", true); return
                }
                val ticket = ctl.send(outgoing)
                if (ticket == null) {
                    finish(idx, "LMArena로 메시지를 보내지 못했습니다. 상단 'Agent' 버튼으로 LMArena 페이지 상태(로그인/캡차)를 확인하세요.", true)
                    return
                }
                sessionStarted = true

                val raw = ctl.awaitResponse(ticket) { partial ->
                    upd(idx) {
                        it.copy(
                            text = WorkParser.visibleStreaming(partial),
                            planning = WorkParser.isPlanning(partial),
                        )
                    }
                }
                if (raw.isBlank()) {
                    finish(idx, "LMArena 응답을 받지 못했습니다. (시간 초과 또는 사이트 오류)\n진단: ${ctl.debug(ticket)}", true); return
                }

                val parsed = WorkParser.parse(raw)
                val block = parsed.block
                if (block == null) {
                    if (parsed.looksBroken && formatRetries < 2) {
                        formatRetries++
                        outgoing = AgentPrompt.formatError()
                        continue
                    }
                    // work 블록이 없으면 일반 대화 답변으로 간주
                    finish(idx, parsed.visible.ifBlank { raw }, false)
                    return
                }
                formatRetries = 0
                val hasCmds = block.commands.isNotEmpty()
                if (hasCmds) {
                    upd(idx) {
                        it.copy(
                            steps = it.steps + block.summary.ifBlank { block.status },
                            text = "",
                            planning = false,
                        )
                    }
                }

                val svc = AgentAccessibilityService.instance
                val usesUi = block.commands.any { it.optString("action").lowercase().trim() !in SystemApi.PURE }
                if (hasCmds && usesUi) svc?.cursorShow(block.summary.ifBlank { "작업 중" })
                val exec = if (hasCmds) executeAll(block.commands) else Exec("[]", false, true)

                when (block.status) {
                    "done" -> if (!exec.error) {
                        if (usesUi) svc?.cursorLabel("작업 완료", CursorState.DONE)
                        finish(idx, parsed.visible.ifBlank { block.summary }, false); return
                    }
                    "failed" -> { svc?.cursorLabel("작업 실패", CursorState.FAILED); finish(idx, parsed.visible.ifBlank { block.summary }, true); return }
                    "need_user" -> { svc?.cursorLabel("확인이 필요해요", CursorState.ASK); finish(idx, parsed.visible.ifBlank { block.summary }, false); return }
                }
                // done 인데 명령이 실패했거나 continue → 결과를 AI 에게 돌려주고 계속
                if (!hasCmds) upd(idx) { it.copy(steps = it.steps + block.summary.ifBlank { block.status }, text = "", planning = false) }
                else upd(idx) { it.copy(text = "") }
                if (usesUi) svc?.cursorLabel("다음 작업 생각 중…", CursorState.THINKING)
                outgoing = AgentPrompt.result(step, exec.json, exec.needScreen)
            }
        } catch (e: CancellationException) {
            AgentAccessibilityService.instance?.cursorLabel("중단됨", CursorState.STOPPED)
            upd(idx) { it.copy(streaming = false, planning = false, text = it.text.ifBlank { "(중단됨)" }) }
            throw e
        } catch (e: Exception) {
            finish(idx, "오류가 발생했습니다: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    private class Exec(val json: String, val error: Boolean, val needScreen: Boolean)

    private suspend fun executeAll(cmds: List<JSONObject>): Exec {
        val svc = AgentAccessibilityService.instance
        val arr = JSONArray()
        var error = false
        var screen = false
        for (c in cmds.take(10)) {
            val a = c.optString("action").lowercase().trim()
            if (a !in SystemApi.PURE) screen = true
            val r = when {
                a == "read_screen" -> "ok: screen is included in DEVICE_STATE"
                SystemApi.handles(a) -> SystemApi.execute(c)
                else -> svc?.execute(c)
                    ?: "error: accessibility service is not enabled (UI actions unavailable; use API commands or need_user)"
            }
            arr.put(JSONObject().put("action", a).put("result", r))
            if (r.startsWith("error")) { error = true; break }
        }
        return Exec(arr.toString(), error, screen || error)
    }
}
