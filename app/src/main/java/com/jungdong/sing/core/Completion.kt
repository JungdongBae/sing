package com.jungdong.sing.core

import kotlin.math.*

data class MelodyNote(val midi: Int, val startMs: Long, val durationMs: Long, val bar: Int) {
    init { require(midi in 0..127 && startMs >= 0 && durationMs > 0 && bar >= 1) }
    val endMs get() = startMs + durationMs
}
data class SongSection(val id: String, val label: String, val firstBar: Int, val lastBar: Int,
                       val startMs: Long, val endMs: Long)
data class SongMetadata(
    val id: String, val title: String, val sourceName: String, val sourceCitation: String,
    val referenceKey: String?, val notes: List<MelodyNote>, val sections: List<SongSection>,
    val verifiedByUser: Boolean = false,
) {
    init {
        require(id.isNotBlank() && notes.isNotEmpty() && notes.size <= 20000)
        require(notes.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) { "멜로디 한 파트를 선택해 주세요. 동시에 울리는 음은 지원하지 않습니다." }
        require(notes.last().endMs <= 600_000) { "10분 이하의 멜로디를 사용해 주세요." }
    }
    val low get() = notes.minOf { it.midi }
    val high get() = notes.maxOf { it.midi }
    val durationMs get() = notes.last().endMs
    fun section(id: String?) = sections.find { it.id == id }
    fun notesFor(id: String?): List<MelodyNote> {
        val section = section(id) ?: return notes
        return notes.filter { it.startMs >= section.startMs && it.startMs < section.endMs }
            .map { it.copy(startMs = it.startMs - section.startMs) }
    }
    companion object {
        fun sections(notes: List<MelodyNote>): List<SongSection> = notes.groupBy { (it.bar - 1) / 4 }
            .entries.sortedBy { it.key }.mapIndexed { i, (_, ns) ->
                SongSection("s$i", "구간 ${i + 1}", ns.first().bar, ns.last().bar, ns.first().startMs, ns.last().endMs)
            }
    }
}
data class KeyRecommendation(val semitones: Int, val cost: Double, val low: Int, val high: Int,
                             val challengingBars: List<Int>)
data class KeyTrial(val songId: String, val profileId: String, val sectionId: String?, val semitones: Int,
                    val pitchAccuracy: Double?, val coverage: Double?, val comfort: Int, val recordingId: String)

/** Recommendation is MIDI transposition only; it never changes an imported backing track. */
object KeyRecommender {
    fun recommend(song: SongMetadata?, profile: VocalRangeProfile?): List<KeyRecommendation> {
        if (song == null || profile == null || !song.verifiedByUser) return emptyList()
        val counts = song.notes.groupingBy { it.midi }.eachCount()
        fun weight(n: MelodyNote) = n.durationMs / 1000.0 * (1 + ln(1.0 + counts.getValue(n.midi)))
        val center = (profile.recommended.first + profile.recommended.last) / 2.0
        return (-24..24).mapNotNull { shift ->
            if (song.notes.any { it.midi + shift !in profile.comfortable }) return@mapNotNull null
            val cost = song.notes.sumOf { n ->
                val p = n.midi + shift
                val edge = max(0.0, 2.0 - min(p - profile.lowMidi, profile.highMidi - p))
                weight(n) * (abs(p - center) + edge * 3)
            } / song.notes.sumOf(::weight)
            val bars = song.notes.groupBy { it.bar }.entries.sortedByDescending { (_, ns) ->
                ns.sumOf { n -> weight(n) * (abs(n.midi + shift - center) + 1) }
            }.take(3).map { it.key }
            KeyRecommendation(shift, cost, song.low + shift, song.high + shift, bars)
        }.sortedWith(compareBy<KeyRecommendation> { it.cost }.thenBy { abs(it.semitones) }).take(3)
    }
    fun finalize(candidates: List<KeyRecommendation>, trials: List<KeyTrial>, songId: String,
                 profileId: String): Int? = trials.filter {
        it.songId == songId && it.profileId == profileId && it.comfort >= 4 &&
            (it.coverage ?: 0.0) >= .7 && it.pitchAccuracy != null &&
            candidates.any { c -> c.semitones == it.semitones }
    }.groupBy { it.semitones }.entries.maxByOrNull { (_, values) ->
        values.map { .7 * it.pitchAccuracy!! + .3 * (it.comfort - 1) / 4.0 }.average()
    }?.key
}

data class VocalSelfAssessment(val comfort: Int, val breath: Int, val diction: Int, val connection: Int, val satisfaction: Int) {
    init { require(listOf(comfort, breath, diction, connection, satisfaction).all { it in 1..5 }) }
}
data class PerformanceFrame(val timeMs: Long, val pitch: Pitch?)
data class NoteFeedback(val bar: Int, val midi: Int, val pitchAccuracy: Double?, val meanAbsCents: Double?,
                        val onsetErrorMs: Long?, val voicedCoverage: Double)
data class PerformanceReport(val pitchAccuracy: Double?, val rhythmAccuracy: Double?, val coverage: Double,
                             val notes: List<NoteFeedback>, val meanConfidence: Double?) {
    val weakBars get() = notes.filter { it.voicedCoverage < .7 || (it.pitchAccuracy ?: 0.0) < .7 ||
        (it.onsetErrorMs?.let { ms -> abs(ms) > 120 } ?: false) }.map { it.bar }.distinct()
}
object MelodyAnalyzer {
    fun valid(p: Pitch?) = p != null && p.hz.isFinite() && p.hz in 60.0..800.0 && p.confidence >= .85 && p.rms >= .008
    fun target(notes: List<MelodyNote>, timeMs: Long): MelodyNote? = notes.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
    fun analyze(songVerified: Boolean, notes: List<MelodyNote>, shift: Int, frames: List<PerformanceFrame>,
                tolerance: Int = 25, calibrationMs: Int? = null): PerformanceReport {
        if (!songVerified || notes.isEmpty()) return PerformanceReport(null, null, 0.0, emptyList(), null)
        require(tolerance in 10..100)
        val ordered = frames.sortedBy { it.timeMs }
        // Remove isolated octave spikes using neighbors, never using the target to fold octaves.
        val aligned = ordered.mapIndexed { index, f ->
            val left = ordered.getOrNull(index - 1)?.pitch
            val right = ordered.getOrNull(index + 1)?.pitch
            val current = f.pitch
            val isolatedOctave = valid(left) && valid(right) && valid(current) &&
                abs(1200 * log2(left!!.hz / right!!.hz)) <= 50 &&
                abs(abs(1200 * log2(current!!.hz / left.hz)) - 1200) <= 80
            val cleaned = if (isolatedOctave) current!!.copy(hz = (left!!.hz + right!!.hz) / 2) else current
            f.copy(timeMs = f.timeMs - (calibrationMs ?: 0), pitch = cleaned)
        }
        val intervals = aligned.zipWithNext().map { (a, b) -> b.timeMs - a.timeMs }.filter { it > 0 }.sorted()
        val hop = (intervals.getOrNull(intervals.size / 2) ?: 93L).coerceIn(20L, 200L)
        val results = notes.map { n ->
            // Exclude a short attack and release from sustained pitch evaluation.
            val guard = minOf(80L, n.durationMs / 5)
            val held = aligned.filter { it.timeMs >= n.startMs + guard && it.timeMs < n.endMs - guard }
            val voiced = held.filter { valid(it.pitch) }
            val expected = ceil((n.durationMs - 2 * guard).toDouble() / hop).toInt().coerceAtLeast(1)
            val coverage = voiced.size.toDouble() / maxOf(expected, held.size)
            val errors = voiced.map { abs(Music.cents(it.pitch!!.hz, n.midi + shift)) }
            // Silence is reflected in coverage, never rewarded as correct pitch.
            val accuracy = if (voiced.size < 3 || coverage < .5) null else errors.count { it <= tolerance }.toDouble() / errors.size
            val searchLow = max(0L, n.startMs - minOf(300L, n.durationMs / 2))
            val onsetFrames = aligned.filter { it.timeMs in searchLow..minOf(n.endMs, n.startMs + 400) }
            val onset = onsetFrames.zipWithNext().firstOrNull { (a, b) ->
                valid(a.pitch) && valid(b.pitch) && abs(Music.cents(a.pitch!!.hz, n.midi + shift)) <= 100 &&
                    abs(Music.cents(b.pitch!!.hz, n.midi + shift)) <= 100
            }?.first?.timeMs
            // A repeated/tied pitch without an observable transition is not an onset measurement.
            val previous = aligned.lastOrNull { it.timeMs < searchLow }
            val distinctAttack = !valid(previous?.pitch) || abs(Music.cents(previous!!.pitch!!.hz, n.midi + shift)) > 100
            NoteFeedback(n.bar, n.midi + shift, accuracy, errors.takeIf { it.isNotEmpty() }?.average(),
                if (calibrationMs != null && onset != null && distinctAttack) onset - n.startMs else null, coverage)
        }
        val reliable = results.filter { it.pitchAccuracy != null }
        val onsets = results.mapNotNull { it.onsetErrorMs }
        val coverage = results.zip(notes).sumOf { (r, n) -> r.voicedCoverage * n.durationMs } / notes.sumOf { it.durationMs }.toDouble()
        val pitch = if (coverage < .7 || reliable.isEmpty()) null else
            reliable.sumOf { it.pitchAccuracy!! * notes[results.indexOf(it)].durationMs } /
                reliable.sumOf { notes[results.indexOf(it)].durationMs }.toDouble()
        val rhythm = if (calibrationMs == null || coverage < .7 || onsets.size < 3) null else
            onsets.count { abs(it) <= 120 }.toDouble() / onsets.size
        return PerformanceReport(pitch, rhythm, coverage, results,
            frames.mapNotNull { it.pitch }.filter { valid(it) }.takeIf { it.isNotEmpty() }?.map { it.confidence }?.average())
    }
}

data class DailyPracticePlan(val week: Int, val day: Int, val title: String, val songTask: String) {
    val phases get() = listOf(PracticeStep("편안한 발성 준비", 180, "작게 허밍하고 부드럽게 소리를 시작해요. 목을 누르지 마세요.", 0),
        PracticeStep("음정 · 박자 교정", 300, "추천 기준음과 느린 4박자로 어려운 구간을 나눠 연습해요.", 0),
        PracticeStep("노래 · 구간 연습", 480, songTask, 3),
        PracticeStep("녹음 · 비교", 240, "이전 녹음과 비교하고 편안함·호흡·발음·연결·만족도를 직접 평가해요.", 3))
}
object CompletionTraining {
    private val tasks = listOf(
        listOf("노래 감상과 구간 구분 · 기준 녹음", "가사 리듬과 박자", "키 후보 비교", "첫 구간 허밍과 기준음", "후렴 멜로디 · 최종 키 선택"),
        listOf("첫 구간 음정", "첫 구간 박자", "후렴 음정 교정", "취약 구간 반복", "구간 연결과 녹음"),
        listOf("허밍과 편안한 발성", "호흡 위치와 긴 구절", "발음과 가사 전달", "음량 조절과 프레이징", "녹음 비교와 표현 교정"),
        listOf("전체 구간 연결", "반주 연습", "전체 녹음과 평가", "부족한 구간 교정", "최종 녹음과 비교"))
    fun plan(week: Int, day: Int): DailyPracticePlan {
        require(week in 5..8 && day in 1..5)
        val title = tasks[week - 5][day - 1]
        return DailyPracticePlan(week, day, title, when (week) {
            5 -> "$title. 기준음 → 멜로디 듣기 → 허밍 → ‘아’ → 가사 순서로 익혀요. 적법하게 보유한 가사는 별도로 확인해요."
            6 -> "$title. 결과의 반복 오류 마디를 골라 짧게 연습하고 연결해요."
            7 -> "$title. 목에 힘을 빼고 자연스럽게 호흡해요. 음색과 표현은 녹음을 들으며 직접 평가해요."
            else -> "$title. 선택한 키로 구간을 연결하고 이전 녹음과 비교해요. 반주는 별도 파일이 필요해요."
        })
    }
    fun completed(seconds: Int) = seconds >= 1200
    fun next(week: Int, day: Int): Pair<Int, Int>? = if (day < 5) week to day + 1 else if (week < 8) week + 1 to 1 else null
}

data class PracticeSession(val date: String, val week: Int, val day: Int, val seconds: Int, val mastered: Boolean = false,
                           val songId: String? = null, val attempt: Int = 1) {
    init { require(week in 5..8 && day in 1..5 && seconds in 0..1200 && attempt >= 1) }
    val completed get() = CompletionTraining.completed(seconds)
}
data class RecordingSession(
    val id: String, val createdAt: Long, val fileName: String, val durationMs: Long,
    val songId: String?, val sourceName: String?, val profileId: String?, val sectionId: String?, val sectionLabel: String,
    val semitones: Int?, val interrupted: Boolean, val report: PerformanceReport?, val selfAssessment: VocalSelfAssessment? = null,
    val tolerance: Int = 25, val calibrationMs: Int? = null,
)

/** Short, original technical exercise. It is NOT the melody of 옛사랑. */
object OriginalExercise {
    fun notes(profile: VocalRangeProfile?): List<MelodyNote> {
        val ns = profile?.practiceNotes ?: listOf(45, 48, 50)
        return listOf(0, ns.lastIndex, 0, ns.lastIndex, 0, ns.lastIndex, 0, 0).mapIndexed { i, n ->
            MelodyNote(ns[n], i * 1000L, 1000, i / 4 + 1)
        }
    }
}

object LatencyCalibration {
    /** Four isolated acoustic click arrivals; reject missing/noisy/ambiguous routes. */
    fun estimate(expected: List<Long>, detected: List<Long>): Int? {
        if (expected.size != 4 || detected.size != 4) return null
        val offsets = expected.zip(detected).map { (e, d) -> d - e }.sorted()
        val median = ((offsets[1] + offsets[2]) / 2).toInt()
        if (median !in -50..500 || offsets.any { abs(it - median) > 60 }) return null
        return median
    }
}
