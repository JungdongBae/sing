package com.jungdong.sing.core

data class PracticeStep(val title: String, val seconds: Int, val instruction: String, val tool: Int)
data class Week(val number: Int, val title: String, val goal: String, val steps: List<PracticeStep>)

object Training {
    val weeks = listOf(
        Week(1, "음의 높낮이 알아듣기", "귀가 먼저 익숙해지는 시간", listOf(
            PracticeStep("편안하게 몸과 호흡 풀기", 120, "어깨를 풀고, 숨을 길게 내쉬세요. 목을 조이지 마세요.", 0),
            PracticeStep("두 음 높낮이 비교", 480, "두 음을 듣고 두 번째 음이 높은지 낮은지 선택하세요.", 1),
            PracticeStep("C3 기준음 익히기", 360, "C3를 듣고 작게 허밍하세요. 불편하면 더 낮은 목표음을 선택하세요.", 0),
            PracticeStep("4박자와 마무리", 240, "60 BPM에 맞춰 하나, 둘, 셋, 넷을 말하고 손뼉을 치세요.", 2),
        )),
        Week(2, "들은 음을 내 목소리로", "낮은 음부터 한 음씩 정확하게", listOf(
            PracticeStep("호흡과 낮은 허밍", 120, "작고 편안한 소리로 시작하세요.", 0),
            PracticeStep("목표음 따라 부르기", 480, "C3 → D3 → E3를 연습하세요. 기준음을 끈 뒤 마이크를 켜세요.", 0),
            PracticeStep("음정 유지하기", 360, "한 음을 3초 유지하고 cents 막대를 확인하세요. ±25 cents가 목표예요.", 0),
            PracticeStep("느린 4박자", 240, "60~70 BPM에서 첫 박에 소리를 시작하고 네 번째 박까지 유지하세요.", 2),
        )),
        Week(3, "멜로디에 박자 더하기", "음정과 리듬을 함께 연결하기", listOf(
            PracticeStep("호흡과 기준음", 120, "C3를 듣고 편안한 목소리로 준비하세요.", 0),
            PracticeStep("짧은 멜로디 익히기", 360, "C3 D3 E3 D3 패턴을 듣고 한 음씩 따라 부르세요.", 3),
            PracticeStep("박자에 맞춰 연결하기", 480, "멜로디 연습의 따라 부르기를 켜세요. 화면 목표음이 박자에 맞춰 바뀝니다.", 3),
            PracticeStep("리듬 점검", 240, "70~90 BPM에 맞춰 박자 버튼을 눌러 빠름·늦음을 확인하세요.", 2),
        )),
        Week(4, "한 곡 끝까지 부르기", "작은별 한 곡으로 완성하는 4주", listOf(
            PracticeStep("준비와 기준음", 120, "편안한 호흡으로 준비하세요. 높은 음에서 힘을 주지 마세요.", 0),
            PracticeStep("작은별 멜로디 듣기", 360, "낮은 조의 작은별을 듣고 두 마디씩 익히세요.", 3),
            PracticeStep("한 곡 따라 부르기", 480, "작은별을 선택하고 따라 부르세요. 클릭과 목표음 안내에 맞춰 완창하세요.", 3),
            PracticeStep("어려운 부분과 마무리", 240, "어려웠던 음을 다시 연습하고 편안하게 허밍으로 마무리하세요.", 0),
        )),
    )
    fun weekForDay(day: Int): Week = weeks[((day.coerceIn(1, 28) - 1) / 7)]
    fun personalizedWeek(number: Int, profile: VocalRangeProfile?, tolerance: Int = 25): Week {
        val week = weeks[number.coerceIn(1, 4) - 1]
        if (profile == null) return week.copy(steps = week.steps.map { it.copy(instruction = it.instruction.replace("±25 cents", "±$tolerance cents")) })
        val center = Music.name(profile.center)
        val notes = profile.practiceNotes.joinToString(" → ") { Music.name(it) }
        val melody = profile.melody().take(4).joinToString(" ") { Music.name(it.midi!!) }
        val instructions = when (number) {
            1 -> listOf(week.steps[0].instruction, "추천 음역 안의 두 음을 듣고 높낮이를 비교하세요.",
                "$center 기준음을 듣고 편안하게 따라 부르세요. 편안한 음역 중앙 부근의 한 음입니다.", week.steps[3].instruction)
            2 -> listOf(week.steps[0].instruction, "$notes 음을 하나씩 선택해 따라 부르세요. 기준음 재생이 끝난 후 마이크를 켜세요.",
                "한 음을 3초 유지하세요. 목표음 대비 ±$tolerance cents가 목표예요.", week.steps[3].instruction)
            3 -> listOf("$center 기준음과 편안한 호흡으로 준비하세요.", "$melody 패턴을 듣고 따라 부르세요. 모든 음은 추천 범위 안에 있습니다.",
                week.steps[2].instruction, week.steps[3].instruction)
            else -> if (profile.songRoots != null) listOf("$center 기준음으로 준비하세요. 목에 힘을 주지 마세요.",
                "개인 음역에 맞춰 키를 조정한 작은별을 듣고 두 마디씩 익히세요.",
                "작은별을 선택하고 따라 부르세요. 모든 음이 확인한 편안한 범위 안에 있도록 키를 조정합니다.", week.steps[3].instruction)
            else listOf("$center 기준음으로 준비하세요.", "현재 확인한 범위는 작은별의 음 간격보다 좁아요. 짧은 패턴을 반복해 익히세요.",
                "추천 음역 안의 짧은 패턴을 끝까지 연결하세요. 편안할 때 재측정한 뒤 한 곡으로 이어갈 수 있어요.", week.steps[3].instruction)
        }
        return week.copy(title = if (number == 4 && profile.songRoots == null) "편안한 패턴 끝까지 부르기" else week.title,
            goal = if (number == 4 && profile.songRoots == null) "지금 확인한 음역부터 연결하기" else week.goal,
            steps = week.steps.mapIndexed { index, step -> step.copy(
            title = if (number == 1 && index == 2) "$center 기준음 익히기" else if (number == 4 && profile.songRoots == null && index in 1..2) "짧은 패턴 연결하기" else step.title,
            instruction = instructions[index]) })
    }
    fun stepIndex(elapsedSeconds: Int, week: Week): Int {
        var total = 0
        week.steps.forEachIndexed { index, step ->
            total += step.seconds
            if (elapsedSeconds < total) return index
        }
        return week.steps.lastIndex
    }
    const val DAILY_SECONDS = 1200
}
