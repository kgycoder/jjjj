package com.agent.arena

import java.util.Calendar

/** 빈 화면에 랜덤으로 나오는 인사말. */
object Greetings {

    private val general = listOf(
        "무엇을 도와드릴까요?",
        "오늘은 어떤 일을 해볼까요?",
        "폰은 저에게 맡겨 주세요.",
        "어떤 작업이 필요하세요?",
        "필요한 일을 말씀만 하세요.",
        "다음엔 뭘 해볼까요?",
        "준비됐어요. 시작해 볼까요?",
        "손이 필요할 땐 언제든지요.",
        "무엇이든 부탁하세요.",
    )

    private val morning = listOf("좋은 아침이에요. 무엇을 할까요?", "상쾌한 하루의 시작이네요. 뭘 도와드릴까요?")
    private val afternoon = listOf("좋은 오후예요. 뭘 도와드릴까요?", "점심은 드셨나요? 필요한 게 있으면 말씀하세요.")
    private val evening = listOf("오늘 하루도 수고했어요. 남은 일을 도와드릴게요.", "좋은 저녁이에요. 무엇을 할까요?")
    private val night = listOf("늦은 시간이네요. 필요한 게 있나요?", "아직 깨어 계시네요. 무엇을 도와드릴까요?")

    fun pick(previous: String?): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timed = when (hour) {
            in 5..10 -> morning
            in 11..16 -> afternoon
            in 17..21 -> evening
            else -> night
        }
        val pool = (general + timed + timed).filter { it != previous }
        return pool.random()
    }
}
