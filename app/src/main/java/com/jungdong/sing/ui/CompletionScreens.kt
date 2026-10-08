package com.jungdong.sing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jungdong.sing.SingState
import com.jungdong.sing.SingViewModel
import com.jungdong.sing.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private fun percent(value: Double?) = value?.let { "${(it * 100).roundToInt()}%" } ?: "평가 자료 부족"
private fun keyLabel(shift: Int) = if (shift == 0) "파일 기준 그대로" else "파일 기준 ${if (shift > 0) "+" else ""}$shift 반음"
private fun time(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
internal fun CompletionPage(basic: SingState, model: SingViewModel, mic: (() -> Unit) -> Unit) {
    val controller = model.firstSong
    val ui by controller.state.collectAsStateWithLifecycle()
    val records = ui.records
    val song = records.song
    val busy = ui.recording || ui.playing || ui.importing
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::importMelody) }
    var backingShift by rememberSaveable { mutableStateOf("0") }
    var backingConsent by rememberSaveable { mutableStateOf(false) }
    val backingImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importBacking(it, backingShift.toIntOrNull() ?: 0, backingConsent) }
    }
    LaunchedEffect(basic.range?.id, ui.loading) { if (!ui.loading) controller.onProfileChanged(basic.range) }
    Heading("MY FIRST SONG / 5–8 WEEKS", "나의 첫 완성곡", "목표곡: 옛사랑 – 이문세\n내 편안한 키로 구간을 나누고, 녹음을 비교하며 한 곡을 연결해요.")
    if (ui.loading) { CircularProgressIndicator(); Text("완성곡 기록을 불러오는 중…"); return }
    if (ui.loadFailed) { Text("저장된 기록을 읽지 못했어요. 앱을 다시 실행해 주세요.", color = Coral); return }
    if (busy || basic.playing || basic.listening) {
        Button(onClick = model::stopAudio, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink)) { Text("■ 중지 · 녹음 중이면 부분 녹음 저장") }
    }
    if (ui.countdown > 0) Text("${ui.countdown}초 후 시작 · 편안하게 준비해 주세요", fontSize = 24.sp, color = Lime)
    if (ui.recording && ui.countdown == 0) {
        BoxCard {
            Text("● 기기에 녹음 중 · ${time((ui.positionMs / 1000).toInt())}", color = Coral)
            Text(ui.target?.let { "현재 목표 ${Music.name(it)} · ${decimal(Music.frequency(it))} Hz" } ?: "자유 발성 / 쉼 · 무리하지 마세요", color = Lime, fontSize = 22.sp)
            val pitch = ui.pitch.takeIf { MelodyAnalyzer.valid(it) }
            Text(pitch?.let { "내 목소리 ${Music.name(Music.midi(it.hz))} · ${decimal(it.hz)} Hz" } ?: "유효한 목소리를 기다리고 있어요.", color = Muted)
            if (pitch != null && ui.target != null) {
                val error = Music.cents(pitch.hz, ui.target!!)
                Text("${if (error > 0) "+" else ""}${decimal(error, 0)} cents · 목표음 대비", fontSize = 25.sp,
                    color = if (abs(error) <= basic.tolerance) Lime else Coral)
                Text(Music.guidance(error, basic.tolerance), color = if (abs(error) <= basic.tolerance) Lime else Coral)
            }
            if (song == null) Text("기준 멜로디가 없어 객관적인 음정·박자 점수를 만들지 않아요.", color = Muted)
        }
    }
    BoxCard {
        Text("내 음역 · 기존 기초 과정과 공유", fontWeight = FontWeight.Bold)
        basic.range?.let { p ->
            Text("편안한 음역 ${Music.name(p.lowMidi)}~${Music.name(p.highMidi)}", fontSize = 22.sp, color = Lime)
            Text("추천 중앙 범위 ${Music.name(p.recommended.first)}~${Music.name(p.recommended.last)} · 계산한 연습 범위", color = Muted)
            Text("시작음 ${Music.name(p.startMidi)} · ${decimal(p.startHz)} Hz", color = Muted)
            Text(p.startConfidence?.let { "시작음 검출 신뢰도 ${decimal(it * 100, 0)}%" } ?: "이전 기록에는 검출 신뢰도 수치가 없어요. 재진단에서 저장됩니다.", color = Muted)
        } ?: Text("키 추천에는 내 편안한 음역이 필요해요. 먼저 음역을 확인해 주세요.", color = Muted)
        OutlinedButton(onClick = model::openDiagnosis, enabled = !busy) { Text("음역 진단 / 다시 측정") }
    }
    BoxCard {
        Text("곡 자료", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (song == null) {
            Text("옛사랑의 원곡 음원·반주·가사·악보는 포함하지 않았어요.", color = Muted)
            Text("파일 없이 가능: 발성 안내 · 메트로놈 · 자유 녹음 · 재생 · 자가 평가\n멜로디 자료 필요: 키 추천 · 구간 목표음 · 음정/박자 분석", color = Muted)
        } else {
            Text("${song.title} · 사용자 확인 기준자료", color = Lime)
            Text("${song.sourceName}\n출처: ${song.sourceCitation}", color = Muted)
            Text("기준 키: ${song.referenceKey ?: "자료에 없음 · 원키 추정 안 함"}\n파일 멜로디 ${Music.name(song.low)}~${Music.name(song.high)} · ${song.notes.size}음 · ${song.durationMs / 1000}초", color = Muted)
        }
        Button(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.importing) "가져오는 중…" else "적법하게 보유한 MIDI / MusicXML 가져오기")
        }
        Text("MIDI 형식 0/1 · UTF-8 단선율 MusicXML · 2MB 이하. 앱이 원곡과 일치하는지 자동 검증하지 않아요. 멜로디 파트와 출처를 직접 확인해 주세요.", color = Muted, fontSize = 12.sp)
    }
    ui.draft?.let { draft ->
        var index by remember(draft) { mutableIntStateOf(0) }
        var citation by remember(draft) { mutableStateOf("") }
        var licensed by remember(draft) { mutableStateOf(false) }
        var verified by remember(draft) { mutableStateOf(false) }
        BoxCard {
            Text("멜로디 파트 선택과 확인", fontWeight = FontWeight.Bold)
            draft.melodies.forEachIndexed { i, m ->
                FilterChip(selected = index == i, onClick = { index = i }, label = { Text(m.label) })
            }
            val part = draft.melodies[index]
            Text("${Music.name(part.notes.minOf { it.midi })}~${Music.name(part.notes.maxOf { it.midi })} · ${part.notes.size}음 · ${part.notes.last().endMs / 1000}초", color = Lime)
            draft.warnings.forEach { Text(it, color = Coral, fontSize = 12.sp) }
            OutlinedTextField(value = citation, onValueChange = { citation = it }, label = { Text("자료 출처 / 구매·보유 정보") }, modifier = Modifier.fillMaxWidth())
            CheckRow(licensed, { licensed = it }, "이 자료를 적법하게 보유하고 있어요")
            CheckRow(verified, { verified = it }, "옛사랑의 노래 멜로디이며 마디·템포·길이를 확인했어요")
            Button(onClick = { controller.acceptMelody(index, citation, licensed && verified) }, enabled = licensed && verified && citation.isNotBlank()) { Text("확인한 자료 적용") }
            TextButton(onClick = controller::cancelDraft) { Text("취소 · 기존 자료 유지") }
        }
    }
    val candidates = controller.recommendations()
    if (song != null) {
        BoxCard {
            Text("개인 맞춤 키 후보", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (basic.range == null) Text("음역 진단 후에 추천할 수 있어요.", color = Muted)
            else if (candidates.isEmpty()) Text("현재 편안한 음역에 곡 전체를 담는 적합한 키가 없어요. 음을 억지로 내지 말고 자유 발성·짧은 연습을 이어가세요.", color = Coral)
            candidates.forEach { candidate ->
                FilterChip(selected = records.selectedKey == candidate.semitones, enabled = !busy,
                    onClick = { controller.selectKey(candidate.semitones) }, label = { Text(keyLabel(candidate.semitones)) })
                Text("${Music.name(candidate.low)}~${Music.name(candidate.high)} · 먼저 확인할 마디 ${candidate.challengingBars.joinToString()}", color = Muted, fontSize = 13.sp)
            }
            Text("선택 키: ${records.selectedKey?.let(::keyLabel) ?: "아직 선택하지 않았어요"}\n${if (records.keyFinalized) "구간 음정과 편안함을 종합해 최종 선택했어요." else "후보를 골라 구간 녹음과 편안함 평가를 해 주세요."}", color = Lime)
            Button(onClick = controller::finalizeKey, enabled = !busy && candidates.isNotEmpty()) { Text("녹음·편안함 결과로 최종 키 결정") }
            Text("오래 유지하는 음과 자주 나오는 음에 가중치를 줘요. 편안함 4점 이상, 유효 음성 70% 이상인 녹음으로 비교합니다. 반주 파일의 피치는 바꾸지 않아요.", color = Muted, fontSize = 12.sp)
        }
    }
    val plan = CompletionTraining.plan(records.week, records.day)
    val current = records.current(LocalDate.now().toString())
    BoxCard {
        Text("${records.week}주차 · ${records.day}일차 · ${records.attempt}회차", color = Lime, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (5..8).forEach { w -> FilterChip(selected = records.week == w, enabled = !busy && !ui.timerRunning,
                onClick = { controller.selectDay(w, 1) }, label = { Text("${w}주") }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { day -> FilterChip(selected = records.day == day, enabled = !busy && !ui.timerRunning,
                onClick = { controller.selectDay(records.week, day) }, label = { Text("${day}일") }) }
        }
        Text(plan.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(time(1200 - ui.seconds), fontSize = 42.sp, color = Lime)
        LinearProgressIndicator(progress = { ui.seconds / 1200f }, modifier = Modifier.fillMaxWidth())
        val elapsed = ui.seconds
        var boundary = 0
        val activePhase = plan.phases.indexOfFirst { boundary += it.seconds; elapsed < boundary }.let { if (it < 0) 3 else it }
        Text("지금 할 일: ${plan.phases[activePhase].title}", fontWeight = FontWeight.Bold)
        Text(plan.phases[activePhase].instruction, color = Muted)
        plan.phases.forEach { Text("${it.seconds / 60}분 · ${it.title}", color = Muted, fontSize = 13.sp) }
        Button(onClick = controller::toggleTimer, enabled = ui.seconds < 1200 && !ui.importing, modifier = Modifier.fillMaxWidth()) { Text(if (ui.timerRunning) "타이머 일시정지" else "20분 연습 시작 / 이어 하기") }
        Text("완료 여부: ${if (current.completed) "20분 완료" else "연습 중"} · 숙련도는 별도로 확인해요.", color = Muted)
        CheckRow(current.mastered, controller::mastery, "이 내용은 편안하고 안정적으로 할 수 있어요 (숙련 자가 확인)", enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::repeatWeek, enabled = !busy && !ui.timerRunning) { Text("현재 주차 반복") }
            Button(onClick = controller::nextDay, enabled = current.completed && current.mastered && !busy && CompletionTraining.next(records.week, records.day) != null) { Text("다음 연습일") }
        }
    }
    if (song != null) {
        Text("연습 구간 선택", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.sectionId == null, enabled = !busy, onClick = { controller.chooseSection(null) }, label = { Text("전체 곡") })
            song.sections.forEach { section -> FilterChip(selected = ui.sectionId == section.id, enabled = !busy,
                onClick = { controller.chooseSection(section.id) }, label = { Text("${section.label} · ${section.firstBar}–${section.lastBar}마디") }) }
        }
        song.section(ui.sectionId)?.let { section ->
            var label by remember(section.id, section.label) { mutableStateOf(section.label) }
            BoxCard {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("구간 이름 · 예: 첫 구간 / 후렴") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { controller.renameSection(section.id, label) }, enabled = !busy && label.isNotBlank()) { Text("구간 이름 저장") }
                Text("자료에서 읽은 4마디 묶음이에요. 실제 곡 구성을 확인하며 이름을 정해 주세요.", color = Muted, fontSize = 12.sp)
            }
        }
    }
    var practiceStage by rememberSaveable(ui.sectionId, song?.id) { mutableIntStateOf(0) }
    val stageNames = listOf("기준음", "멜로디", "허밍", "아 발성", "가사", "녹음", "결과", "반복")
    BoxCard {
        Text("구간 연습 ${practiceStage + 1} / 8 · ${stageNames[practiceStage]}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(when (practiceStage) {
            0 -> "기준음을 먼저 듣고 어깨와 목의 힘을 빼세요."
            1 -> "멜로디를 듣고 목표음의 순서를 확인해요."
            2 -> "입을 편하게 다문 채 작게 허밍해요."
            3 -> "‘아’로 부드럽게 시작하고 한 음을 편안하게 유지해요."
            4 -> "적법하게 보유한 가사를 보며 말하듯 읽고 불러요. 가사는 앱에 포함되지 않아요."
            5 -> "녹음을 눌러 기기에 저장해요. 불편하거나 아프면 바로 중지하세요."
            6 -> "녹음을 듣고 목표음 결과와 자가 평가를 함께 확인해요."
            else -> "반복 오류 마디를 짧게 나누어 다시 연습해요."
        }, color = Muted)
        if (song == null) Text("아래 멜로디는 앱 자체 생성 발성 연습입니다. 옛사랑의 멜로디가 아니에요.", color = Coral, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::reference, enabled = !busy && (song == null || records.selectedKey != null)) { Text("기준음 듣기") }
            OutlinedButton(onClick = controller::melody, enabled = !busy && (song == null || records.selectedKey != null)) { Text("멜로디 확인") }
        }
        OutlinedButton(onClick = model::metronome, enabled = !ui.recording && !ui.playing) { Text(if (basic.metronome) "메트로놈 중지" else "4박자 메트로놈 · ${basic.bpm} BPM") }
        Slider(value = basic.bpm.toFloat(), onValueChange = { model.bpm(it.roundToInt()) }, valueRange = 50f..120f, enabled = !busy)
        Button(onClick = { mic { controller.record() } }, enabled = !busy && (song == null || records.selectedKey != null), modifier = Modifier.fillMaxWidth()) {
            Text(if (song == null) "자유 발성 녹음 (최대 10분)" else if (ui.sectionId == null) "전체 노래 녹음" else "짧은 구간 녹음")
        }
        Text("눌렀을 때만 녹음 파일을 기기에 저장해요. 외부 전송 기능은 제공하지 않습니다. 이어폰을 쓰면 반주 유입을 줄일 수 있어요.", color = Muted, fontSize = 12.sp)
        TextButton(onClick = { practiceStage = (practiceStage + 1) % 8 }, enabled = !busy) { Text(if (practiceStage == 7) "기준음부터 다시 연습" else "다음 단계 · ${stageNames[(practiceStage + 1) % 8]}") }
    }
    BoxCard {
        Text("편안하고 듣기 좋은 목소리", fontWeight = FontWeight.Bold)
        Text("작은 허밍 → 부드러운 시작 → 힘 빼고 유지\n자연스러운 호흡 → 또렷한 발음 → 편안한 강약 → 짧은 구절 연결", color = Muted)
        Text("더 낮게 내려고 목을 누르지 마세요. 음색·표현력은 자동 채점하지 않고 녹음을 들으며 직접 평가해요.", color = Muted, fontSize = 12.sp)
    }
    BoxCard {
        Text("반주 파일 · 별도 자료 필요", fontWeight = FontWeight.Bold)
        Text("앱은 반주의 피치를 변경하지 않아요. 선택한 연습 키로 만들어진 반주를 가져오세요. 앞부분 대기 시간도 멜로디 자료와 같아야 해요.", color = Muted)
        OutlinedTextField(value = backingShift, onValueChange = { backingShift = it }, label = { Text("반주 키: 멜로디 파일 대비 반음 이동량 (-24~24)") }, modifier = Modifier.fillMaxWidth())
        CheckRow(backingConsent, { backingConsent = it }, "반주를 적법하게 보유하고 키·시작 시각을 확인했어요")
        OutlinedButton(onClick = { backingImporter.launch(arrayOf("audio/*")) }, enabled = !busy && backingConsent && backingShift.toIntOrNull() in -24..24) { Text("반주 파일 가져오기 (100MB / 10분 이하)") }
        records.backing?.let { backing ->
            Text("${backing.name} · ${keyLabel(backing.semitones)}", color = Lime)
            OutlinedButton(onClick = controller::playBacking, enabled = !busy) { Text("반주 원래 키로 재생") }
            Button(onClick = { mic { controller.record(withBacking = true) } }, enabled = !busy && song != null && ui.sectionId == null && records.selectedKey == backing.semitones) { Text("선택 키 반주와 전체 녹음") }
            TextButton(onClick = controller::removeBacking, enabled = !busy) { Text("반주 파일 삭제") }
        }
    }
    BoxCard {
        Text("마이크 · 출력 지연 보정", fontWeight = FontWeight.Bold)
        Text(records.calibrationMs?.let { "현재 보정값 $it ms" } ?: "미설정 · 박자 점수를 표시하지 않아요", color = Lime)
        Text("조용한 곳에서 스피커 클릭을 마이크가 듣도록 해 주세요. 지연 추정은 기기별 확인이 필요하며 출력 장치가 바뀌면 무효화합니다.", color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = { mic(controller::calibrate) }, enabled = !busy) { Text("클릭 4번으로 지연 추정") }
        ui.calibrationMessage?.let { Text(it, color = Muted) }
        var delayText by remember(records.calibrationMs) { mutableStateOf(records.calibrationMs?.toString() ?: "") }
        OutlinedTextField(value = delayText, onValueChange = { delayText = it }, label = { Text("확인한 보정값 직접 입력 · ms") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { delayText.toIntOrNull()?.let(controller::calibration) }, enabled = !busy && delayText.toIntOrNull() in -500..500) { Text("적용") }
            TextButton(onClick = { controller.calibration(null) }, enabled = !busy) { Text("보정 해제") }
        }
    }
    Text("완성 현황 · 진행과 숙련을 따로 확인", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    val songRecords = records.recordings.filter { it.songId == song?.id }
    val sectionsPracticed = songRecords.mapNotNull { it.sectionId }.distinct()
    BoxCard {
        Text("20일 연습 완료율 ${percent(records.completionRate)}", color = Lime, fontSize = 23.sp)
        Text("연습한 구간 ${sectionsPracticed.size} / ${song?.sections?.size ?: 0} · 녹음 ${songRecords.size}개", color = Muted)
        val latest = songRecords.lastOrNull()
        if (latest != null) {
            Text("최근 녹음 ${latest.sectionLabel} · ${latest.semitones?.let(::keyLabel) ?: "자유 발성"}", fontWeight = FontWeight.Bold)
            ReportSummary(latest.report, latest.calibrationMs)
            val previous = songRecords.dropLast(1).lastOrNull {
                it.sectionId == latest.sectionId && it.semitones == latest.semitones && it.profileId == latest.profileId &&
                    it.tolerance == latest.tolerance && it.calibrationMs == latest.calibrationMs
            }
            previous?.let {
                Text("같은 구간·키·판정 조건의 이전 녹음과 비교", color = Muted)
                CompareMetric("음정", it.report?.pitchAccuracy, latest.report?.pitchAccuracy)
                CompareMetric("박자", it.report?.rhythmAccuracy, latest.report?.rhythmAccuracy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.playRecording(it.id) }, enabled = !busy) { Text("이전 듣기") }
                    OutlinedButton(onClick = { controller.playRecording(latest.id) }, enabled = !busy) { Text("최근 듣기") }
                }
            }
            val weak = latest.report?.weakBars.orEmpty()
            if (weak.isNotEmpty()) Text("다시 연습할 마디: ${weak.joinToString()}", color = Coral)
            val repeated = songRecords.takeLast(3).mapNotNull { it.report }.flatMap { it.weakBars }.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
            if (repeated.isNotEmpty()) Text("최근 녹음에서 반복된 오류 마디: ${repeated.joinToString()}", color = Coral)
        } else Text("첫 녹음을 저장하고 이전과 비교해 보세요.", color = Muted)
        if (records.week == 8 && records.day == 5) Text("최종 녹음을 들으며 처음과 비교해요. 20분 완료가 완곡 숙련을 뜻하지는 않습니다.", color = Muted)
    }
    Text("로컬 녹음과 자가 평가", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    var showCount by remember { mutableIntStateOf(5) }
    records.recordings.asReversed().take(showCount).forEach { take ->
        RecordingCard(take, busy, { controller.playRecording(take.id) }, { controller.deleteRecording(take.id) }, { controller.assess(take.id, it) })
    }
    if (records.recordings.size > showCount) TextButton(onClick = { showCount += 10 }) { Text("이전 녹음 더 보기") }
}

@Composable
private fun CheckRow(checked: Boolean, change: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Row {
        Checkbox(checked = checked, onCheckedChange = change, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp).weight(1f), color = Muted, fontSize = 13.sp)
    }
}
@Composable
private fun ReportSummary(report: PerformanceReport?, calibration: Int?) {
    if (report == null) { Text("기준 멜로디 없음 · 음정/박자 점수 없음", color = Muted); return }
    Text("음정 안정도 ${percent(report.pitchAccuracy)} · 박자 안정도 ${percent(report.rhythmAccuracy)}", color = Lime)
    Text("목표 허용범위 비율 · 유효 음성 ${percent(report.coverage)}\n${if (calibration == null) "박자: 지연 보정 미설정" else "박자: 보정 ${calibration}ms, 시작 시점 ±120ms 기준"}", color = Muted, fontSize = 12.sp)
    report.meanConfidence?.let { Text("유효 입력 검출 신뢰도 ${percent(it)} · 음색 평가가 아니에요", color = Muted, fontSize = 12.sp) }
}
@Composable
private fun CompareMetric(label: String, before: Double?, after: Double?) {
    Text(if (before == null || after == null) "$label 비교: 유효 평가 자료 부족"
        else "$label: ${percent(before)} → ${percent(after)} (${if (after >= before) "+" else ""}${((after - before) * 100).roundToInt()}%p)", color = Muted)
}
@Composable
private fun RecordingCard(record: RecordingSession, busy: Boolean, play: () -> Unit, delete: () -> Unit, assess: (VocalSelfAssessment) -> Unit) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    BoxCard {
        Text("${formatter.format(Instant.ofEpochMilli(record.createdAt).atZone(ZoneId.systemDefault()))} · ${record.sectionLabel}", fontWeight = FontWeight.Bold)
        Text("${record.durationMs / 1000}초 · ${record.semitones?.let(::keyLabel) ?: "자유 발성"}${if (record.interrupted) " · 중단한 부분 녹음" else ""}", color = Muted)
        ReportSummary(record.report, record.calibrationMs)
        record.selfAssessment?.let { Text("내 평가: 편안함 ${it.comfort} · 호흡 ${it.breath} · 발음 ${it.diction} · 연결 ${it.connection} · 만족 ${it.satisfaction}", color = Muted, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = play, enabled = !busy) { Text("녹음 듣기") }
            TextButton(onClick = { expanded = !expanded }, enabled = !busy) { Text("자가 평가") }
            TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("삭제") }
        }
        if (expanded) {
            val old = record.selfAssessment
            var comfort by remember(old) { mutableFloatStateOf((old?.comfort ?: 3).toFloat()) }
            var breath by remember(old) { mutableFloatStateOf((old?.breath ?: 3).toFloat()) }
            var diction by remember(old) { mutableFloatStateOf((old?.diction ?: 3).toFloat()) }
            var connection by remember(old) { mutableFloatStateOf((old?.connection ?: 3).toFloat()) }
            var satisfaction by remember(old) { mutableFloatStateOf((old?.satisfaction ?: 3).toFloat()) }
            Rating("목이 편안했나요?", comfort) { comfort = it }
            Rating("호흡이 안정적이었나요?", breath) { breath = it }
            Rating("발음이 명확했나요?", diction) { diction = it }
            Rating("목소리가 자연스럽게 이어졌나요?", connection) { connection = it }
            Rating("듣기에 만족스러웠나요?", satisfaction) { satisfaction = it }
            Button(onClick = { assess(VocalSelfAssessment(comfort.roundToInt(), breath.roundToInt(), diction.roundToInt(), connection.roundToInt(), satisfaction.roundToInt())); expanded = false }) { Text("1~5점 평가 저장") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("녹음을 삭제할까요?") },
        text = { Text("이 기기의 녹음 파일과 자가 평가가 삭제됩니다.") },
        confirmButton = { TextButton(onClick = { delete(); confirmDelete = false }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } })
}
@Composable
private fun Rating(label: String, value: Float, change: (Float) -> Unit) {
    Text("$label  ${value.roundToInt()} / 5", color = Muted)
    Slider(value = value, onValueChange = change, valueRange = 1f..5f, steps = 3)
}
