package com.agent.arena

import org.json.JSONObject

/**
 * "볼륨 50%", "손전등 켜줘", "유튜브 열어줘" 같은 짧고 명확한 단일 명령은
 * LMArena(원격 AI) 왕복 없이 기기에서 바로 실행한다. 확실하지 않으면 null → 평소처럼 AI 에이전트가 처리.
 */
object FastPath {

    data class Result(val summary: String, val reply: String)

    private val multiStep = Regex("(하고|그리고|한 다음|한 뒤|then|\\band\\b|,|\\n)")
    private val numberRe = Regex("(\\d{1,3})\\s*(%|퍼센트|프로|으로|로)?")

    private fun has(s: String, vararg words: String) = words.any { s.contains(it) }

    private suspend fun api(vararg kv: Pair<String, Any>): String {
        val o = JSONObject()
        for ((k, v) in kv) o.put(k, v)
        return SystemApi.execute(o)
    }

    private fun pctOf(r: String): String = Regex("\\((\\d+)%\\)").find(r)?.groupValues?.get(1)
        ?: Regex("(\\d+)%").find(r)?.groupValues?.get(1) ?: "?"

    suspend fun tryRun(raw: String): Result? {
        val original = raw.trim()
        if (original.isEmpty() || original.length > 36) return null
        if (multiStep.containsMatchIn(original.lowercase())) return null
        val s = original.lowercase().replace(Regex("[?!.~]+$"), "").trim()

        // --- 음소거 / 무음 / 진동 / 소리모드 ---------------------------------------------
        if (has(s, "음소거 해제", "언뮤트", "unmute")) {
            val r = api("action" to "volume", "stream" to "music", "mute" to false)
            return if (r.startsWith("ok")) Result("음소거 해제", "음소거를 해제했어요. (${pctOf(r)}%)") else null
        }
        if (has(s, "음소거", "뮤트", "mute") && !has(s, "알람", "알림")) {
            val r = api("action" to "volume", "stream" to "music", "mute" to true)
            return if (r.startsWith("ok")) Result("음소거", "미디어 소리를 음소거했어요.") else null
        }
        if (has(s, "무음") && s.length <= 12) {
            val r = api("action" to "ringer", "mode" to "silent")
            return if (r.startsWith("ok")) Result("무음 모드", "무음 모드로 바꿨어요.") else null
        }
        if (has(s, "진동") && has(s, "모드", "으로", "해줘", "해 줘") && s.length <= 12) {
            val r = api("action" to "ringer", "mode" to "vibrate")
            return if (r.startsWith("ok")) Result("진동 모드", "진동 모드로 바꿨어요.") else null
        }
        if (has(s, "소리 모드", "벨소리 모드", "일반 모드", "소리모드") && s.length <= 14) {
            val r = api("action" to "ringer", "mode" to "normal")
            return if (r.startsWith("ok")) Result("소리 모드", "소리 모드로 바꿨어요.") else null
        }

        // --- 볼륨 ----------------------------------------------------------------------
        if (has(s, "볼륨", "음량", "소리", "volume") && !has(s, "분", "시간", "초", "알람 ", "타이머")) {
            val stream = when {
                has(s, "벨소리", "ring") -> "ring"
                has(s, "알림") -> "notification"
                else -> "music"
            }
            val num = numberRe.find(s)?.groupValues?.get(1)?.toIntOrNull()
            val up = has(s, "올려", "높여", "키워", "크게", "올리", "높게", "up", "louder")
            val down = has(s, "내려", "낮춰", "줄여", "작게", "낮게", "down", "quieter")
            val amount = when {
                has(s, "조금", "살짝", "약간") -> 1
                has(s, "많이", "확") -> 4
                else -> 2
            }
            val r: String
            val msg: String
            when {
                num != null && num in 0..100 -> {
                    r = api("action" to "volume", "stream" to stream, "percent" to num)
                    msg = "볼륨을 ${num}%로 맞췄어요."
                }
                has(s, "최대", "최고", "풀", "max") -> {
                    r = api("action" to "volume", "stream" to stream, "percent" to 100)
                    msg = "볼륨을 최대로 올렸어요."
                }
                has(s, "최소", "min") -> {
                    r = api("action" to "volume", "stream" to stream, "percent" to 0)
                    msg = "볼륨을 최소로 낮췄어요."
                }
                up && !down -> {
                    r = api("action" to "volume", "stream" to stream, "delta" to amount)
                    msg = "볼륨을 올렸어요."
                }
                down && !up -> {
                    r = api("action" to "volume", "stream" to stream, "delta" to -amount)
                    msg = "볼륨을 낮췄어요."
                }
                else -> return null
            }
            return if (r.startsWith("ok")) Result("볼륨 ${pctOf(r)}%", "$msg (현재 ${pctOf(r)}%)") else null
        }

        // --- 밝기 ----------------------------------------------------------------------
        if (has(s, "밝기", "brightness")) {
            val num = numberRe.find(s)?.groupValues?.get(1)?.toIntOrNull()
            val r: String
            when {
                num != null && num in 0..100 -> r = api("action" to "brightness", "percent" to num)
                has(s, "최대", "max") -> r = api("action" to "brightness", "percent" to 100)
                has(s, "올려", "높여", "키워", "밝게", "up") -> r = api("action" to "brightness", "delta_percent" to 20)
                has(s, "내려", "낮춰", "줄여", "어둡게", "down") -> r = api("action" to "brightness", "delta_percent" to -20)
                else -> return null
            }
            return if (r.startsWith("ok")) Result("밝기 ${pctOf(r)}%", "화면 밝기를 ${pctOf(r)}%로 맞췄어요.") else null
        }

        // --- 손전등 --------------------------------------------------------------------
        if (has(s, "손전등", "플래시", "후레시", "랜턴", "flashlight", "torch")) {
            val on = when {
                has(s, "꺼", "끄", "off", "종료") -> false
                has(s, "켜", "켬", "on", "밝혀") -> true
                else -> return null
            }
            val r = api("action" to "flashlight", "on" to on)
            return if (r.startsWith("ok")) Result("손전등 ${if (on) "켜짐" else "꺼짐"}", "손전등을 ${if (on) "켰어요" else "껐어요"}.") else null
        }

        // --- 미디어 키 -----------------------------------------------------------------
        val media: Pair<String, String>? = when {
            has(s, "다음 곡", "다음곡", "다음 노래", "next song", "skip song") -> "next" to "다음 곡으로 넘겼어요."
            has(s, "이전 곡", "이전곡", "이전 노래", "previous song") -> "previous" to "이전 곡으로 돌아갔어요."
            has(s, "일시정지", "일시 정지", "음악 멈춰", "노래 멈춰", "음악 꺼", "노래 꺼", "pause music") -> "pause" to "재생을 일시정지했어요."
            has(s, "음악 재생", "노래 재생", "음악 틀어", "노래 틀어", "play music", "resume music") -> "play" to "재생을 시작했어요."
            else -> null
        }
        if (media != null) {
            val r = api("action" to "media", "command" to media.first)
            val ok = r.startsWith("ok") && (media.first != "play" || r.contains("music_active=true"))
            return if (ok) Result("미디어: ${media.first}", media.second) else null
        }

        // --- 타이머 --------------------------------------------------------------------
        if (has(s, "타이머", "timer")) {
            var h = 0; var m = 0; var sec = 0
            Regex("(\\d+)\\s*(시간|hours?|hr|h)").find(s)?.let { h = it.groupValues[1].toInt() }
            Regex("(\\d+)\\s*(분|minutes?|min|m)").find(s)?.let { m = it.groupValues[1].toInt() }
            Regex("(\\d+)\\s*(초|seconds?|sec|s)").find(s)?.let { sec = it.groupValues[1].toInt() }
            if (h + m + sec <= 0) return null
            val r = api("action" to "timer", "hours" to h, "minutes" to m, "seconds" to sec)
            val label = buildString {
                if (h > 0) append("${h}시간 "); if (m > 0) append("${m}분 "); if (sec > 0) append("${sec}초 ")
            }.trim()
            return if (r.startsWith("ok")) Result("타이머 $label", "$label 타이머를 시작했어요.") else null
        }

        // --- 알람 (오전/오후가 명확할 때만) ------------------------------------------------
        if (has(s, "알람") && has(s, "맞춰", "설정", "해줘", "해 줘", "추가", "등록")) {
            val m = Regex("(오전|오후|아침|저녁|밤|새벽)?\\s*(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분|반)?").find(s) ?: return null
            val mer = m.groupValues[1]
            var hour = m.groupValues[2].toInt()
            val minute = when {
                m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
                m.value.contains("반") -> 30
                else -> 0
            }
            if (mer.isEmpty() && hour <= 12) return null // 오전/오후 모호 → AI 에게 맡김
            if (hour > 23 || minute > 59) return null
            when (mer) {
                "오후", "저녁", "밤" -> if (hour < 12) hour += 12
                "오전", "아침", "새벽" -> if (hour == 12) hour = 0
            }
            val r = api("action" to "alarm", "hour" to hour, "minute" to minute)
            val t = "%02d:%02d".format(hour, minute)
            return if (r.startsWith("ok")) Result("알람 $t", "$t 알람을 맞췄어요.") else null
        }

        // --- 앱 열기 -------------------------------------------------------------------
        val open = Regex("^(.+?)\\s*(앱|어플|애플리케이션)?\\s*(좀\\s*)?(열어|열기|켜|실행|띄워|켜봐|open|launch|start)\\s*(줘|주세요|봐|라)?$")
            .find(s)
        if (open != null) {
            val name = open.groupValues[1].trim()
            if (name.isEmpty() || name.length > 20) return null
            val pkg = SystemApi.resolvePackage(name) ?: return null
            val r = SystemApi.launchApp(JSONObject().put("package", pkg))
            return if (r.startsWith("ok")) Result("앱 실행: $name", "${name}을(를) 열었어요.") else null
        }
        return null
    }
}
