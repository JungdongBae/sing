package com.jungdong.sing.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jungdong.sing.SingState
import com.jungdong.sing.SingViewModel
import com.jungdong.sing.core.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal val Ink = Color(0xFF101C19)
internal val Panel = Color(0xFF1B2B25)
internal val Lime = Color(0xFFD4F582)
internal val Cream = Color(0xFFF4F5EA)
internal val Muted = Color(0xFFB7C4B5)
internal val Coral = Color(0xFFFFB4A1)
private val scheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink,
    surface = Panel, onSurface = Cream, onBackground = Cream, secondary = Muted,
    surfaceVariant = Color(0xFF293B31), onSurfaceVariant = Muted, error = Coral)
internal fun decimal(value: Double, places: Int = 1) = String.format(Locale.KOREA, "% .${places}f", value).trim()
private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun SingApp(model: SingViewModel, onMicrophone: (() -> Unit) -> Unit, onSettings: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("오늘", "음정", "듣기", "박자", "곡", "기록")
    BackHandler(enabled = state.diagnosisOpen || tab >= 6) {
        if (state.diagnosisOpen) model.closeDiagnosis() else { model.stopAudio(); tab = 0 }
    }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(containerColor = Ink, bottomBar = {
            if (!state.diagnosisOpen) {
            NavigationBar(containerColor = Panel) {
                val symbols = listOf("◷", "♪", "↕", "●", "♫", "✓")
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { model.stopAudio(); tab = index },
                        icon = { Text(symbols[index], fontSize = 22.sp) }, label = { Text(title, fontSize = 11.sp) })
                }
            }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sing", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Lime)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("음치탈출 4주  /  OFFLINE", fontSize = 11.sp, color = Muted)
                        TextButton(onClick = { model.stopAudio(); tab = 6 }, enabled = !state.diagnosisOpen && !state.rangeLoading) { Text("음역 · 설정") }
                        TextButton(onClick = { model.firstSong.open(); tab = 7 }, enabled = !state.diagnosisOpen && !state.rangeLoading) { Text("나의 첫 완성곡") }
                    }
                }
                state.error?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF442C26))) {
                        Column(Modifier.padding(16.dp)) { Text(it, color = Coral); TextButton(onClick = onSettings) { Text("앱 권한 설정 열기") } }
                    }
                }
                if (state.rangeLoading) {
                    CircularProgressIndicator()
                    Text("저장된 음역을 불러오고 있어요.", color = Muted)
                } else if (state.diagnosisOpen) {
                    DiagnosisPage(state, model, onMicrophone)
                } else when (tab) {
                    0 -> Today(state, model) { tab = it }
                    1 -> PitchPage(state, model, onMicrophone)
                    2 -> EarPage(state, model)
                    3 -> RhythmPage(state, model)
                    4 -> SongPage(state, model, onMicrophone)
                    5 -> HistoryPage(state)
                    6 -> RangeSettingsPage(state, model)
                    7 -> CompletionPage(state, model, onMicrophone)
                }
                Text("목이 아프면 쉬어 가세요. 작고 편안한 소리로도 충분합니다.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun Heading(kicker: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kicker, color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
        Text(body, color = Muted, lineHeight = 23.sp)
    }
}

@Composable
internal fun BoxCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Today(state: SingState, model: SingViewModel, open: (Int) -> Unit) {
    val week = Training.personalizedWeek(state.selectedWeek, state.range, state.tolerance)
    val stepIndex = Training.stepIndex(state.seconds, week)
    val step = week.steps[stepIndex]
    Heading("YOUR DAILY PRACTICE", "하루 20분,\n내 목소리에 익숙해지기", "낮은 음부터 천천히. 귀로 듣고, 따라 부르고, 박자에 연결해요.")
    BoxCard {
        Text(if (state.range == null) "내 편안한 음역 찾기" else "내 음역에 맞춘 연습", fontWeight = FontWeight.Bold)
        state.range?.let { Text("편안한 음역 ${Music.name(it.lowMidi)}~${Music.name(it.highMidi)} · 추천 ${Music.name(it.recommended.first)}~${Music.name(it.recommended.last)}", color = Muted) }
            ?: Text("아직 음역을 몰라도 괜찮아요. 안내에 따라 3초씩 소리를 내며 확인해요.", color = Muted)
        OutlinedButton(onClick = model::openDiagnosis) { Text(if (state.range == null) "음역 진단 시작" else "음역 다시 측정") }
    }
    BoxCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${state.today} · ${minOf(state.completedDays + 1, 28)}일차", color = Muted)
            Text("${state.completedDays} / 28 완료", color = Lime)
        }
        Text(clock(Training.DAILY_SECONDS - state.seconds), fontSize = 48.sp, fontWeight = FontWeight.Light)
        LinearProgressIndicator(progress = { state.seconds / Training.DAILY_SECONDS.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Lime)
        Text(if (state.seconds >= 1200) "오늘 연습 완료! 내일 다시 만나요." else "남은 연습 시간 · 앱이 배경으로 이동하면 자동으로 멈춰요", color = Muted, fontSize = 12.sp)
        Button(onClick = model::toggleSession, enabled = state.seconds < 1200, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.sessionRunning) "연습 타이머 일시정지" else if (state.seconds > 0) "20분 연습 이어 하기" else "20분 연습 시작")
        }
        if (state.seconds < 1200) {
            Text("지금 할 연습 · ${step.title}", fontWeight = FontWeight.Bold)
            Text(step.instruction, color = Muted)
            OutlinedButton(onClick = { model.prepareWeeklyTool(step.tool); open(if (step.tool == 3) 4 else step.tool + 1) }) { Text("연습 도구 열기") }
        }
    }
    Text("4주 훈련 프로그램", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Training.weeks.forEach { item ->
            FilterChip(selected = item.number == week.number, enabled = !state.sessionRunning,
                onClick = { model.selectWeek(item.number) }, label = { Text("${item.number}주") })
        }
    }
    Text(week.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(week.goal, color = Muted)
    if (state.range != null) {
        Text("이번 주 기준음 · 눌러서 선택하세요", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.weeklyNotes().forEach { note ->
                FilterChip(selected = state.target == note, onClick = { model.target(note); open(1) }, label = { Text(Music.name(note)) })
            }
        }
    }
    week.steps.forEachIndexed { index, item ->
        BoxCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0${index + 1}  ${item.title}", color = if (index == stepIndex) Lime else Cream, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("${item.seconds / 60}분", color = Muted)
            }
            Text(item.instruction, color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun TargetControl(state: SingState, model: SingViewModel) {
    BoxCard {
        Text("내가 따라 부를 목표음", color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { model.target(state.target - 1) }, enabled = state.target > state.targetBounds.first) { Text("− 반음") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Music.name(state.target), fontSize = 32.sp, color = Lime, fontWeight = FontWeight.Bold)
                Text("${decimal(Music.frequency(state.target))} Hz", color = Muted)
            }
            OutlinedButton(onClick = { model.target(state.target + 1) }, enabled = state.target < state.targetBounds.last) { Text("+ 반음") }
        }
        Text(state.range?.let { "${Music.name(it.lowMidi)}~${Music.name(it.highMidi)} · 확인한 편안한 음역 안에서 선택해요." }
            ?: "E2–C4 · 처음에는 C3 또는 더 편안한 낮은 음을 선택하세요.", color = Muted, fontSize = 12.sp)
        Button(onClick = model::reference, modifier = Modifier.fillMaxWidth()) { Text("♪  기준음 듣기") }
    }
}

@Composable
private fun PitchReadout(state: SingState) {
    val pitch = state.pitch
    val cents = pitch?.let { Music.cents(it.hz, state.target) }
    val color = if (cents != null && abs(cents) <= state.tolerance) Lime else Coral
    BoxCard {
        Text("목표 ${Music.name(state.target)} 대비 현재 목소리", color = Muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(pitch?.let { Music.name(Music.midi(it.hz)) } ?: "—", fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(pitch?.let { "${decimal(it.hz)} Hz" } ?: "목소리를 기다려요", color = Muted, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(cents?.let { "${if (it > 0) "+" else ""}${decimal(it, 0)} cents" } ?: "작게 ‘음~’ 하고 소리 내 보세요", color = color, fontSize = 22.sp)
        Canvas(Modifier.fillMaxWidth().height(42.dp)) {
            val center = size.width / 2
            drawLine(Muted.copy(alpha = 0.4f), Offset(0f, 21.dp.toPx()), Offset(size.width, 21.dp.toPx()), strokeWidth = 4.dp.toPx())
            val band = center * state.tolerance / 100f
            drawLine(Lime.copy(alpha = 0.55f), Offset(center - band, 21.dp.toPx()), Offset(center + band, 21.dp.toPx()), strokeWidth = 8.dp.toPx())
            drawLine(Cream, Offset(center, 4.dp.toPx()), Offset(center, 38.dp.toPx()), strokeWidth = 2.dp.toPx())
            if (cents != null) drawCircle(color, 8.dp.toPx(), Offset(center + (cents.coerceIn(-100.0, 100.0) / 100 * center).toFloat(), 21.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("낮음 −100", color = Muted, fontSize = 12.sp); Text("목표 0", color = Lime, fontSize = 12.sp); Text("높음 +100", color = Muted, fontSize = 12.sp)
        }
        Text(cents?.let { Music.guidance(it, state.tolerance) } ?: if (state.listening) "조용한 곳에서 한 음을 길게 유지하세요." else "마이크를 켜면 음정이 표시돼요.")
        if (cents != null && abs(cents) >= 1000) Text("목표음과 옥타브 차이가 날 수 있어요. 기준음을 다시 들어 보세요.", color = Coral, fontSize = 13.sp)
        Text("초록 구간: ±${state.tolerance} cents · 100 cents = 반음\n범위를 벗어나면 표시점은 끝에 머물고 실제 오차는 숫자로 보여요.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PitchPage(state: SingState, model: SingViewModel, mic: (() -> Unit) -> Unit) {
    Heading("01 / PITCH", "내 음정은 어디쯤?", "기준음을 듣고, 재생이 끝나면 마이크를 켜 따라 불러 보세요.")
    TargetControl(state, model)
    PitchReadout(state)
    Button(onClick = { if (state.listening) model.stopAudio() else mic(model::listen) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (state.listening) "■  마이크 끄기" else "●  마이크로 음정 측정")
    }
    Text("마이크 소리는 메모리에서만 분석해요. 녹음 파일을 저장하거나 외부로 보내지 않습니다. 소음이 크면 측정을 잠시 숨겨요.", color = Muted, fontSize = 13.sp)
}

@Composable
private fun EarPage(state: SingState, model: SingViewModel) {
    Heading("02 / LISTEN", "두 번째 음은\n더 높을까요, 낮을까요?", "먼저 귀로 음의 방향을 느껴 보세요. 정답을 보기 전에 여러 번 들어도 좋아요.")
    BoxCard {
        Text("♪     →     ?", fontSize = 52.sp, color = Lime, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Button(onClick = model::hearQuestion, enabled = !state.playing && state.earAvailable, modifier = Modifier.fillMaxWidth()) { Text("두 음 듣기 / 다시 듣기") }
        if (!state.earAvailable) Text("현재 확인한 음역은 한 음이에요. 편안할 때 재측정한 뒤 높낮이 비교를 시작해 주세요.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { model.answer(true) }, enabled = state.questionHeard && state.earAnswer == null && !state.playing, modifier = Modifier.weight(1f)) { Text("↑ 더 높아요") }
            OutlinedButton(onClick = { model.answer(false) }, enabled = state.questionHeard && state.earAnswer == null && !state.playing, modifier = Modifier.weight(1f)) { Text("↓ 더 낮아요") }
        }
        state.earAnswer?.let { correct ->
            Text(if (correct) "맞았어요!" else "다시 귀를 기울여 볼까요?", color = if (correct) Lime else Coral, fontSize = 22.sp)
            Text("${Music.name(state.question.first)} → ${Music.name(state.question.second)} · 두 번째 음이 ${if (state.question.isHigher) "높아요" else "낮아요"}.", color = Muted)
            Button(onClick = model::newQuestion) { Text("다음 문제") }
        }
        Text("오늘 앱 실행 중 정답 ${state.earCorrect} / ${state.earTotal}", color = Muted)
    }
    Text(if (state.range == null) "두 음은 낮은 남성 음역에서 무작위로 출제됩니다. 마이크 권한 없이 연습할 수 있어요."
        else "두 음은 개인 추천 음역 안에서 출제됩니다. 마이크 권한 없이 연습할 수 있어요.", color = Muted)
}

@Composable
private fun Tempo(state: SingState, model: SingViewModel) {
    Text("${state.bpm} BPM", fontSize = 34.sp, color = Lime, fontWeight = FontWeight.Bold)
    Slider(value = state.bpm.toFloat(), onValueChange = { model.bpm(it.roundToInt()) }, valueRange = 50f..120f, steps = 69)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("느리게 50", color = Muted); Text("120 빠르게", color = Muted) }
}

@Composable
private fun Beats(beat: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { index ->
            Surface(Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(16.dp), color = if (beat == index) Lime else Color(0xFF293B31)) {
                Box(contentAlignment = Alignment.Center) { Text("${index + 1}", color = if (beat == index) Ink else Muted, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun RhythmPage(state: SingState, model: SingViewModel) {
    Heading("03 / RHYTHM", "하나, 둘, 셋, 넷", "첫 박의 높은 클릭을 듣고 4박자를 반복해요. 처음에는 60 BPM으로 시작하세요.")
    BoxCard {
        Tempo(state, model)
        Beats(state.beat)
        Button(onClick = model::metronome, modifier = Modifier.fillMaxWidth()) { Text(if (state.metronome) "■  메트로놈 멈추기" else "▶  메트로놈 시작") }
        Button(onClick = model::tap, enabled = state.metronome && state.beat >= 0, modifier = Modifier.fillMaxWidth().height(80.dp), colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Ink)) { Text("박자에 맞춰 누르기", fontSize = 18.sp) }
        val error = state.tapError
        Text(error?.let { if (abs(it) <= 80) "좋아요! 박자에 가까워요" else if (it < 0) "조금 빨라요" else "조금 늦어요" } ?: "클릭과 동시에 버튼을 눌러 보세요.", color = if (error != null && abs(error) <= 80) Lime else Cream)
        error?.let { Text("${if (it > 0) "+" else ""}${decimal(it, 0)} ms · 가까운 박 기준", color = Muted) }
        Text("재생 위치 기준의 연습용 피드백입니다. 기기·블루투스 출력 지연이 포함될 수 있어요. 정확히 연습하려면 기기 스피커나 유선 이어폰을 사용하세요.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SongPage(state: SingState, model: SingViewModel, mic: (() -> Unit) -> Unit) {
    val melody = state.range?.melody() ?: Songs.shortMelody
    val song = state.range?.song(state.songRoot) ?: if (state.range == null) Songs.littleStar else null
    Heading("04 / MELODY", "한 음에서 한 곡으로", "멜로디를 먼저 듣고 따라 부르세요. 따라 부르기에서는 클릭만 재생하고 목표음이 박자에 맞춰 바뀝니다.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(selected = !state.song, onClick = { model.selectSong(false) }, label = { Text("짧은 패턴") })
        FilterChip(selected = state.song, onClick = { model.selectSong(true) }, label = { Text("작은별 · 한 곡") })
    }
    BoxCard {
        Text(if (state.song) song?.let { "작은별 · ${Music.name(it.minOf { e -> e.midi!! })}~${Music.name(it.maxOf { e -> e.midi!! })} · 48박" }
            ?: "현재 음역 안에서는 작은별의 모든 음을 담을 수 없어요." else melody.joinToString(" ") { Music.name(it.midi!!) } + " · 8박", color = Muted)
        if (state.song && state.range != null) {
            val roots = state.range!!.songRoots
            if (roots == null) Text("짧은 패턴으로 연습해 주세요. 무리하게 음역을 넓히지 마세요.", color = Coral)
            else {
                Text("내 음역에 맞춘 키 · 첫 음 ${Music.name(state.songRoot)}", color = Lime)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.songRoot(state.songRoot - 1) }, enabled = state.songRoot > roots.first) { Text("키 − 반음") }
                    OutlinedButton(onClick = { model.songRoot(state.songRoot + 1) }, enabled = state.songRoot < roots.last) { Text("키 + 반음") }
                }
            }
        }
        Tempo(state, model)
        Beats(state.beat)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { model.melody(false) }, enabled = !state.song || state.songAvailable, modifier = Modifier.weight(1f)) { Text("♪ 멜로디 듣기") }
            OutlinedButton(onClick = { mic { model.melody(true) } }, enabled = !state.song || state.songAvailable, modifier = Modifier.weight(1f)) { Text("따라 부르기") }
        }
        if (state.listening || state.playing) TextButton(onClick = model::stopAudio) { Text("■ 연습 멈추기") }
        Text("클릭이 마이크에 들어가지 않도록 이어폰을 권장해요. 화면의 음 이름을 보며 한 박씩 이어 보세요.", color = Muted, fontSize = 12.sp)
    }
    PitchReadout(state)
    BoxCard {
        Text("멜로디 길잡이", fontWeight = FontWeight.Bold)
        if (state.song && song == null) {
            Text("작은별은 최저음과 최고음 사이에 9반음이 필요해요. 지금은 짧은 패턴으로 연습해 주세요.", color = Muted)
        } else if (state.song) {
            Text("도 도 솔 솔 | 라 라 솔—\n파 파 미 미 | 레 레 도—\n솔 솔 파 파 | 미 미 레—\n솔 솔 파 파 | 미 미 레—\n도 도 솔 솔 | 라 라 솔—\n파 파 미 미 | 레 레 도—", lineHeight = 28.sp)
            val root = if (state.range == null) 48 else state.songRoot
            Text("도=${Music.name(root)} · 레=${Music.name(root + 2)} · 미=${Music.name(root + 4)} · 파=${Music.name(root + 5)} · 솔=${Music.name(root + 7)} · 라=${Music.name(root + 9)}\n‘—’는 한 박 더 유지해요. 전통 멜로디 / 퍼블릭 도메인.", color = Muted, fontSize = 12.sp)
        } else Text(melody.joinToString(" ") { Music.name(it.midi!!) } + "\n한 음에 한 박씩, 편안하게 이어 보세요.", lineHeight = 28.sp)
    }
}

@Composable
private fun HistoryPage(state: SingState) {
    Heading("MY PRACTICE", "쌓여 가는 20분", "하루 20분을 채우면 완료로 기록됩니다. 기록은 이 기기에만 저장돼요.")
    BoxCard {
        Text("${state.completedDays}일 완료", fontSize = 36.sp, color = Lime, fontWeight = FontWeight.Bold)
        Text("4주 목표 · 28일", color = Muted)
        repeat(4) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { col ->
                    val day = row * 7 + col + 1
                    Surface(Modifier.weight(1f).height(34.dp), shape = RoundedCornerShape(8.dp), color = if (day <= state.completedDays) Lime else Color(0xFF293B31)) {
                        Box(contentAlignment = Alignment.Center) { Text("$day", color = if (day <= state.completedDays) Ink else Muted, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    Text("날짜별 연습", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (state.history.isEmpty()) Text("아직 기록이 없어요. 오늘의 연습 타이머를 시작해 보세요.", color = Muted)
    state.history.forEach { (date, seconds) ->
        BoxCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(date.toString(), fontWeight = FontWeight.Bold)
                Text(if (seconds >= 1200) "✓ 완료" else "진행 중", color = if (seconds >= 1200) Lime else Muted)
            }
            Text("${clock(seconds)} / 20:00", color = Muted)
            LinearProgressIndicator(progress = { seconds / 1200f }, modifier = Modifier.fillMaxWidth())
        }
    }
    Text("앱 데이터 삭제 또는 앱 제거 시 기록이 삭제됩니다. 녹음 파일·클라우드 동기화는 사용하지 않습니다.", color = Muted, fontSize = 12.sp)
}
