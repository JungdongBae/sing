package com.jungdong.sing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jungdong.sing.SingState
import com.jungdong.sing.SingViewModel
import com.jungdong.sing.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun DiagnosisPage(state: SingState, model: SingViewModel, mic: (() -> Unit) -> Unit) {
    val diagnosis = state.diagnosis
    val step = diagnosis.stage.ordinal + 1
    Text("개인 음역 진단  $step / 5", color = Lime, fontWeight = FontWeight.Bold)
    LinearProgressIndicator(progress = { diagnosis.stage.ordinal / 4f }, modifier = Modifier.fillMaxWidth())
    val headings = listOf("내 편안한 음역 찾기", "평소 말하듯 ‘아~’", "조금씩 낮춰보세요", "조금씩 높여보세요", "나에게 맞는 연습 범위")
    val bodies = listOf(
        "음 이름을 몰라도 괜찮아요. 앱의 안내에 따라 편안하게 소리를 내 주세요.",
        "한 번에 3초씩, 두 번 측정해요. 참고음에 억지로 맞추지 말고 평소의 편안한 목소리를 내 주세요.",
        "시작음에서 반음씩 내려가요. 같은 음을 두 번 확인한 뒤 다음 음으로 이동해요.",
        "시작음에서 반음씩 올라가요. 목에 힘이 들거나 불편하면 바로 중단하세요.",
        "편안하다고 두 번 확인한 음만 담았어요. 결과를 저장하면 기준음과 4주 훈련에 적용돼요.",
    )
    Heading("COMFORTABLE VOICE", headings[diagnosis.stage.ordinal], bodies[diagnosis.stage.ordinal])
    diagnosis.message?.let { Text(it, color = if (diagnosis.measurement?.failure != null) Coral else Muted) }
    when (diagnosis.stage) {
        RangeStage.INTRO -> {
            BoxCard {
                Text("① 편안한 시작음 → ② 낮은 음 → ③ 높은 음", fontWeight = FontWeight.Bold)
                Text("조용한 곳에서 앉거나 서서 어깨 힘을 빼세요. 기준음을 듣고 재생이 끝난 뒤 측정을 시작해요.", color = Muted)
                Text("한 번 낼 수 있는 극단적인 음보다, 힘을 주지 않고 반복할 수 있는 음을 찾습니다. 어려워지면 그 방향의 측정을 마쳐도 좋아요.", color = Muted)
                Text("목이 아프거나 불편하면 즉시 중단하고 쉬어 주세요. 현재 측정 범위는 C2~E5이며 기계적으로 끝까지 부를 필요는 없습니다.", color = Coral)
                Text("노래 연습을 위한 편안한 범위 확인입니다. 마이크 소리는 기기 안에서 처리하고 녹음 파일을 저장하지 않아요.", color = Muted, fontSize = 13.sp)
                Button(onClick = model::startDiagnosis, modifier = Modifier.fillMaxWidth()) { Text("안내에 따라 시작하기") }
                OutlinedButton(onClick = model::diagnosisReference, enabled = !state.playing, modifier = Modifier.fillMaxWidth()) { Text("참고음 듣기 · ${Music.name(state.range?.center ?: 48)}") }
            }
            TextButton(onClick = model::closeDiagnosis) { Text("나중에 하기 · 기존 기능 사용") }
        }
        RangeStage.START, RangeStage.LOW, RangeStage.HIGH -> {
            val target = diagnosis.targetMidi
            BoxCard {
                Text(if (target == null) "내 자연스러운 시작음을 찾고 있어요" else "따라 부를 목표음", color = Muted)
                target?.let {
                    Text(Music.name(it), fontSize = 44.sp, color = Lime, fontWeight = FontWeight.Bold)
                    Text("${decimal(Music.frequency(it))} Hz", color = Muted)
                }
                val reference = target ?: diagnosis.confirmations.firstOrNull()?.let(Music::midi) ?: 48
                OutlinedButton(onClick = model::diagnosisReference, enabled = !diagnosis.running && !state.playing,
                    modifier = Modifier.fillMaxWidth()) { Text("♪ ${if (target == null) "참고음" else "기준음"} 듣기 · ${Music.name(reference)}") }
                Text("편안함 확인 ${diagnosis.confirmations.size} / 2 · 한 번 더 확인해야 범위에 포함돼요.", color = Muted, fontSize = 13.sp)
            }
            DiagnosisPitch(state)
            BoxCard {
                LinearProgressIndicator(progress = { diagnosis.progressMs / PitchWindow.DURATION_MS.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${decimal(diagnosis.progressMs / 1000.0)} / 3.0초", color = Muted)
                Button(onClick = { if (diagnosis.running || state.listening) model.stopAudio() else mic(model::measureRange) },
                    enabled = !state.playing || state.listening, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (diagnosis.running || state.listening) "■ 측정 중지" else "● 3초 측정 시작 / 다시 측정")
                }
                Text("기준음과 마이크는 동시에 켜지지 않아요. 소리가 완전히 끝난 뒤 측정해 주세요.", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = model::comfortable, enabled = diagnosis.measurement?.accepted == true && !diagnosis.running && !state.playing && !state.listening,
                        modifier = Modifier.weight(1f)) { Text("편안함") }
                    OutlinedButton(onClick = model::difficult, modifier = Modifier.weight(1f)) { Text("어려움") }
                }
                Text(if (diagnosis.stage == RangeStage.LOW) "‘어려움’을 누르면 마지막으로 두 번 확인한 낮은 음을 남기고 높은 음 측정으로 넘어갑니다."
                    else if (diagnosis.stage == RangeStage.HIGH) "‘어려움’을 누르면 마지막으로 두 번 확인한 높은 음을 남기고 결과를 봅니다."
                    else "‘편안함’을 두 번 확인하면 시작음이 정해집니다.", color = Muted, fontSize = 12.sp)
            }
            Button(onClick = model::stopDiagnosis, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink)) { Text("중단 · 불편하거나 아프면 즉시 멈추기") }
            TextButton(onClick = model::closeDiagnosis) { Text("저장하지 않고 닫기") }
        }
        RangeStage.RESULT -> {
            val profile = diagnosis.profile("preview", 1)
            if (profile != null) RangeResult(profile)
            OutlinedButton(onClick = model::diagnosisReference, enabled = profile != null && !state.playing && !state.rangeSaving,
                modifier = Modifier.fillMaxWidth()) { Text("♪ 확인한 시작음 듣기") }
            Button(onClick = model::saveDiagnosis, enabled = profile != null && !state.rangeSaving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.rangeSaving) "저장 중…" else "저장하고 4주 훈련에 적용")
            }
            OutlinedButton(onClick = model::openDiagnosis, enabled = !state.rangeSaving, modifier = Modifier.fillMaxWidth()) { Text("처음부터 다시 측정") }
            TextButton(onClick = model::closeDiagnosis, enabled = !state.rangeSaving) { Text("저장하지 않고 닫기") }
        }
    }
}

@Composable
private fun DiagnosisPitch(state: SingState) {
    val diagnosis = state.diagnosis
    val hz = state.pitch?.hz ?: diagnosis.measurement?.hz
    val target = diagnosis.targetMidi
    val cents = if (hz != null && target != null) Music.cents(hz, target) else null
    BoxCard {
        Text(if (diagnosis.running) "지금 내 목소리" else "이번 측정", color = Muted)
        Text(hz?.let { Music.name(Music.midi(it)) } ?: "—", fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text(hz?.let { "${decimal(it)} Hz" } ?: "편안하게 ‘아~’ 소리를 내 주세요.", color = Muted)
        if (cents != null) {
            val matches = abs(cents) <= state.tolerance && (diagnosis.running || diagnosis.measurement?.accepted == true)
            Text("${if (cents > 0) "+" else ""}${decimal(cents, 0)} cents · 목표 ${Music.name(target!!)} 대비",
                color = if (matches) Lime else Coral)
            if (diagnosis.running || diagnosis.measurement?.failure == null || diagnosis.measurement?.failure == MeasurementFailure.OFF_TARGET)
                Text(Music.guidance(cents, state.tolerance), color = if (matches) Lime else Coral)
            if (abs(cents) >= 1000) Text("기준음과 옥타브가 다를 수 있어요. 억지로 힘을 주지 말고 다시 들어보세요.", color = Coral, fontSize = 12.sp)
        }
        Text(if (target == null) "시작음은 목표에 맞추는 시험이 아니에요. 두 번의 편안한 목소리가 비슷한지 확인합니다."
            else "목표음과 비교하며 ±${state.tolerance} cents 안의 안정적인 입력을 확인해요.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun RangeResult(profile: VocalRangeProfile) {
    BoxCard {
        Text(if (profile.source == RangeSource.MANUAL && profile.previousId == null) "직접 설정한 시작 기준음" else "측정 당시 시작음", color = Muted)
        Text("${Music.name(profile.startMidi)} · ${decimal(profile.startHz)} Hz", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(profile.startConfidence?.let { "시작음 검출 신뢰도 ${decimal(it * 100, 0)}% · 음색 평가가 아니에요" }
            ?: "검출 신뢰도 수치는 이 기록에 없어요. 새 진단에서 저장됩니다.", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) { Text("최저 편안음", color = Muted); Text(Music.name(profile.lowMidi), fontSize = 30.sp, color = Lime) }
            Column(Modifier.weight(1f)) { Text("최고 편안음", color = Muted); Text(Music.name(profile.highMidi), fontSize = 30.sp, color = Lime) }
        }
        Text("전체 편안한 음역 ${Music.name(profile.lowMidi)}~${Music.name(profile.highMidi)} · ${profile.semitones}반음", color = Muted)
        Text("추천 연습 음역 ${Music.name(profile.recommended.first)}~${Music.name(profile.recommended.last)}", fontWeight = FontWeight.Bold)
        Text("넓은 범위에서는 양끝을 조금 피하고 중앙 부근의 짧은 범위를 추천해요. 좁은 범위에서는 확인한 음만 사용합니다.", color = Muted, fontSize = 12.sp)
        if (profile.partial) Text("중단 후 확인한 범위입니다. 측정하지 않은 음은 포함하지 않았어요.", color = Coral, fontSize = 12.sp)
        if (profile.source == RangeSource.MANUAL) Text("사용자가 직접 수정한 범위입니다. 실제로 편안한 음만 선택해 주세요.", color = Muted, fontSize = 12.sp)
    }
    PianoRange(profile)
}

@Composable
private fun PianoRange(profile: VocalRangeProfile) {
    BoxCard {
        Text("건반으로 보는 내 음역", fontWeight = FontWeight.Bold)
        Text("연두: 추천 연습 음역 · 옅은 초록: 편안한 음역\n좌우로 밀어 전체 범위를 확인하세요.", color = Muted, fontSize = 12.sp)
        val low = (profile.lowMidi / 12 * 12).coerceAtLeast(36)
        val high = ((profile.highMidi / 12 + 1) * 12 - 1).coerceAtMost(76)
        val black = setOf(1, 3, 6, 8, 10)
        val whites = (low..high).filter { it % 12 !in black }
        val comfortableColor = Color(0xFFACCAB0)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width((whites.size * 35).dp).height(138.dp)) {
                Row {
                    whites.forEach { note ->
                        val color = if (note in profile.recommended) Lime else if (note in profile.comfortable) comfortableColor else Cream
                        Box(Modifier.width(35.dp).fillMaxHeight().padding(end = 1.dp).background(color, RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))) {
                            Text(Music.name(note), color = Ink, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp))
                        }
                    }
                }
                (low..high).filter { it % 12 in black }.forEach { note ->
                    val x = whites.count { it < note } * 35 - 12
                    val color = if (note in profile.recommended) Lime else if (note in profile.comfortable) comfortableColor else Ink
                    Box(Modifier.offset(x = x.dp).width(24.dp).height(80.dp).background(color, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))) {
                        Text(Music.name(note), color = if (note in profile.comfortable) Ink else Cream, fontSize = 8.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RangeSettingsPage(state: SingState, model: SingViewModel) {
    val profile = state.range
    Heading("MY VOICE / SETTINGS", "내 음역과 설정", "기록은 이 기기에만 저장돼요. 재측정하거나 직접 수정해도 과거 기록은 남습니다.")
    if (profile != null) RangeResult(profile)
    else Text("아직 저장된 음역이 없어요. 진단을 시작하거나 아래에서 편안한 음역을 직접 설정할 수 있어요.", color = Muted)
    Button(onClick = model::openDiagnosis, enabled = !state.rangeSaving, modifier = Modifier.fillMaxWidth()) { Text("음역 진단 / 다시 측정") }
    BoxCard {
        Text("목표음 판정 허용 오차", fontWeight = FontWeight.Bold)
        var tolerance by remember(state.tolerance) { mutableFloatStateOf(state.tolerance.toFloat()) }
        Text("±${tolerance.roundToInt()} cents", color = Lime, fontSize = 24.sp)
        Slider(value = tolerance, onValueChange = { tolerance = it }, onValueChangeFinished = { model.tolerance(tolerance.roundToInt()) },
            valueRange = 10f..100f, steps = 17)
        Text("기본 ±25 cents · 100 cents는 반음이에요. 넓히면 더 느슨하게 판정하지만 편안함은 직접 확인해야 합니다.", color = Muted, fontSize = 12.sp)
    }
    BoxCard {
        Text("최저음과 최고음 직접 수정", fontWeight = FontWeight.Bold)
        var low by remember(profile?.id) { mutableIntStateOf(profile?.lowMidi ?: 45) }
        var high by remember(profile?.id) { mutableIntStateOf(profile?.highMidi ?: 57) }
        NotePicker("최저 편안음", low) { low = it }
        NotePicker("최고 편안음", high) { high = it }
        if (low > high) Text("최저음은 최고음보다 높을 수 없어요.", color = Coral)
        Text("확인 없이 음역을 넓히지 마세요. 수정하면 새 기록으로 저장하고 훈련 범위를 다시 계산합니다.", color = Muted, fontSize = 12.sp)
        Button(onClick = { model.saveManualRange(low, high) }, enabled = low <= high && !state.rangeSaving,
            modifier = Modifier.fillMaxWidth()) { Text(if (state.rangeSaving) "저장 중…" else "수정한 범위 저장") }
    }
    Text("음역 기록과 비교", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    if (state.rangeHistory.isEmpty()) Text("첫 진단을 저장하면 여기에 기록됩니다.", color = Muted)
    state.rangeHistory.asReversed().forEachIndexed { index, record ->
        BoxCard {
            Text("${if (index == 0) "현재" else "이전"} · ${formatter.format(Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault()))}", fontWeight = FontWeight.Bold)
            Text("${Music.name(record.lowMidi)}~${Music.name(record.highMidi)} · ${if (record.source == RangeSource.MANUAL) "직접 수정" else "측정"}${if (record.partial) " · 일부 확인" else ""}", color = Lime)
            Text("시작음 ${Music.name(record.startMidi)} (${decimal(record.startHz)} Hz) · 추천 ${Music.name(record.recommended.first)}~${Music.name(record.recommended.last)}", color = Muted, fontSize = 13.sp)
            if (index > 0 && profile != null) {
                Text(compare("최저음", profile.lowMidi - record.lowMidi) + " / " + compare("최고음", profile.highMidi - record.highMidi), color = Muted, fontSize = 13.sp)
            }
        }
    }
    Text("음역은 컨디션에 따라 달라질 수 있어요. 성부를 자동으로 분류하지 않으며 편안하게 노래하는 연습에만 사용합니다.", color = Muted, fontSize = 12.sp)
}

private fun compare(label: String, delta: Int) = "$label: " + when {
    delta == 0 -> "현재와 같음"
    delta < 0 -> "현재가 ${-delta}반음 낮음"
    else -> "현재가 ${delta}반음 높음"
}

@Composable
private fun NotePicker(label: String, midi: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("${Music.name(midi)} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                VocalRangeProfile.SUPPORTED.forEach { note ->
                    DropdownMenuItem(text = { Text("${Music.name(note)} · ${decimal(Music.frequency(note))} Hz") }, onClick = { onChange(note); expanded = false })
                }
            }
        }
    }
}
