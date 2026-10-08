package com.jungdong.sing.core

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

enum class RangeSource { MEASURED, MANUAL }

/** A practice aid, with user-confirmed comfort; never a voice type or medical classification. */
data class VocalRangeProfile(
    val id: String,
    val timestamp: Long,
    val startHz: Double,
    val lowMidi: Int,
    val highMidi: Int,
    val source: RangeSource = RangeSource.MEASURED,
    val previousId: String? = null,
    val partial: Boolean = false,
    val startConfidence: Double? = null,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        require(previousId == null || previousId.matches(Regex("[a-zA-Z0-9_-]+")))
        require(timestamp > 0 && startHz.isFinite() && startHz > 0)
        require(Music.midi(startHz) in SUPPORTED)
        require(lowMidi in SUPPORTED && highMidi in SUPPORTED && lowMidi <= highMidi)
        require(startConfidence == null || (startConfidence.isFinite() && startConfidence in 0.0..1.0))
    }
    val startMidi get() = Music.midi(startHz)
    val comfortable get() = lowMidi..highMidi
    val semitones get() = highMidi - lowMidi
    val recommended: IntRange get() {
        val margin = if (semitones >= 6) 2 else 0
        val innerLow = lowMidi + margin
        val innerHigh = highMidi - margin
        val width = minOf(7, innerHigh - innerLow)
        val center = ((lowMidi + highMidi) / 2.0).roundToInt()
        val low = (center - width / 2).coerceIn(innerLow, innerHigh - width)
        return low..low + width
    }
    val center get() = ((recommended.first + recommended.last) / 2.0).roundToInt()
    val practiceNotes get() = listOf(recommended.first, center, recommended.last).distinct()
    val songRoots: IntRange? get() = if (semitones < 9) null else lowMidi..highMidi - 9
    val recommendedSongRoot: Int? get() = songRoots?.let { (center - 4).coerceIn(it.first, it.last) }

    fun melody(): List<NoteEvent> {
        val notes = practiceNotes
        val high = notes.lastIndex
        val mid = minOf(1, high)
        return listOf(0, mid, high, mid, 0, high, mid, 0).map { NoteEvent(notes[it]) }
    }
    fun song(root: Int = recommendedSongRoot ?: lowMidi): List<NoteEvent>? {
        val roots = songRoots ?: return null
        if (root !in roots) return null
        return Songs.littleStar.map { it.copy(midi = it.midi?.plus(root - 48)) }
    }
    companion object { val SUPPORTED = 36..76 } // C2–E5, inside the detector's 60–800 Hz range.
}

enum class MeasurementFailure { NO_VOICE, UNSTABLE, OFF_TARGET, OUT_OF_RANGE, INTERRUPTED, PERMISSION_DENIED, DEVICE_ERROR }
data class RangeMeasurement(
    val hz: Double? = null, val targetCents: Double? = null,
    val validFrames: Int = 0, val totalFrames: Int = 0,
    val failure: MeasurementFailure? = null,
    val confidence: Double? = null,
) {
    val accepted get() = hz != null && failure == null
}

object PitchWindow {
    const val DURATION_MS = 3000
    fun evaluate(samples: List<Pitch?>, target: Int? = null, tolerance: Int = 25): RangeMeasurement {
        require(tolerance in 10..100)
        val valid = samples.filterNotNull().filter {
            it.hz.isFinite() && it.hz in 60.0..800.0 && it.confidence >= 0.85 && it.rms >= 0.008
        }
        if (valid.size < 18 || samples.isEmpty() || valid.size.toDouble() / samples.size < 0.7)
            return RangeMeasurement(validFrames = valid.size, totalFrames = samples.size, failure = MeasurementFailure.NO_VOICE)
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        }
        val hz = median(valid.map { it.hz })
        val deviations = valid.map { abs(1200 * log2(it.hz / hz)) }
        val stable = median(deviations) <= 20 && deviations.count { it <= 50 }.toDouble() / valid.size >= 0.85
        val error = target?.let { Music.cents(hz, it) }
        val failure = when {
            !stable -> MeasurementFailure.UNSTABLE
            Music.midi(hz) !in VocalRangeProfile.SUPPORTED -> MeasurementFailure.OUT_OF_RANGE
            error != null && abs(error) > tolerance -> MeasurementFailure.OFF_TARGET
            else -> null
        }
        return RangeMeasurement(hz, error, valid.size, samples.size, failure, valid.map { it.confidence }.average())
    }
}

enum class RangeStage { INTRO, START, LOW, HIGH, RESULT }
data class RangeDiagnosisState(
    val stage: RangeStage = RangeStage.INTRO,
    val startHz: Double? = null, val targetMidi: Int? = null,
    val lowMidi: Int? = null, val highMidi: Int? = null,
    val confirmations: List<Double> = emptyList(),
    val measurement: RangeMeasurement? = null,
    val running: Boolean = false, val progressMs: Int = 0,
    val message: String? = null, val partial: Boolean = false,
    val startConfidence: Double? = null, val confirmationConfidences: List<Double> = emptyList(),
) {
    val canMeasure get() = stage in listOf(RangeStage.START, RangeStage.LOW, RangeStage.HIGH)
    fun profile(id: String, timestamp: Long, previousId: String? = null): VocalRangeProfile? {
        if (stage != RangeStage.RESULT || startHz == null || lowMidi == null || highMidi == null) return null
        return VocalRangeProfile(id, timestamp, startHz, lowMidi, highMidi, previousId = previousId, partial = partial, startConfidence = startConfidence)
    }
}

/** Pure workflow: a target is admitted only after two valid takes AND two comfort confirmations. */
object RangeWorkflow {
    fun begin(state: RangeDiagnosisState): RangeDiagnosisState = if (!state.canMeasure) state else
        state.copy(running = true, progressMs = 0, measurement = null, message = "편안하게 ‘아~’ 소리를 내 주세요.")
    fun measured(state: RangeDiagnosisState, result: RangeMeasurement): RangeDiagnosisState {
        if (!state.running) return state // Late device results cannot revive an interrupted attempt.
        return state.copy(running = false, progressMs = PitchWindow.DURATION_MS, measurement = result,
            message = when (result.failure) {
                null -> "목에 힘이 들어가지 않았나요? 편안했다면 알려 주세요."
                MeasurementFailure.NO_VOICE -> "목소리를 충분히 듣지 못했어요. 조용한 곳에서 다시 측정해 주세요."
                MeasurementFailure.UNSTABLE -> "음이 많이 흔들렸어요. 힘을 빼고 한 음을 유지하며 다시 측정해 주세요."
                MeasurementFailure.OFF_TARGET -> (if ((result.targetCents ?: 0.0) < 0) "조금 높여보세요." else "조금 낮춰보세요.") + " 기준음을 다시 듣고 측정해 주세요."
                MeasurementFailure.OUT_OF_RANGE -> "현재 측정 가능한 범위는 C2~E5입니다. 무리하지 말고 다시 측정하거나 중단해 주세요."
                else -> "측정이 중단됐어요. 준비가 되면 다시 시작해 주세요."
            })
    }
    fun interrupted(state: RangeDiagnosisState, failure: MeasurementFailure = MeasurementFailure.INTERRUPTED): RangeDiagnosisState =
        if (!state.running && failure == MeasurementFailure.INTERRUPTED) state else state.copy(
            running = false, progressMs = 0, measurement = RangeMeasurement(failure = failure),
            message = if (failure == MeasurementFailure.PERMISSION_DENIED) "마이크 권한이 필요해요. 앱 권한 설정에서 허용하고 다시 시작해 주세요." else "측정을 멈췄어요. 이 시도의 음은 기록하지 않습니다.")
    fun comfortable(state: RangeDiagnosisState): RangeDiagnosisState {
        val result = state.measurement ?: return state
        if (state.running || !result.accepted || !state.canMeasure) return state
        val hz = result.hz!!
        if (state.stage == RangeStage.START && state.confirmations.isNotEmpty() &&
            abs(1200 * log2(hz / state.confirmations.first())) > 50) return state.copy(
            confirmations = emptyList(), confirmationConfidences = emptyList(), measurement = null, message = "두 번의 시작음이 많이 달라요. 평소 말하듯 다시 두 번 측정해 주세요.")
        val confirmations = state.confirmations + hz
        val confidences = state.confirmationConfidences + listOfNotNull(result.confidence)
        if (confirmations.size < 2) return state.copy(confirmations = confirmations, confirmationConfidences = confidences, measurement = null,
            message = "같은 음을 한 번 더 3초간 내고 편안한지 확인해 주세요.")
        if (state.stage == RangeStage.START) {
            val startHz = confirmations.average()
            val midi = Music.midi(startHz)
            val ready = state.copy(startHz = startHz, lowMidi = midi, highMidi = midi, confirmations = emptyList(), confirmationConfidences = emptyList(), measurement = null,
                startConfidence = confidences.takeIf { it.size == 2 }?.average())
            return if (midi > VocalRangeProfile.SUPPORTED.first) ready.copy(stage = RangeStage.LOW, targetMidi = midi - 1,
                message = "시작음이 정해졌어요. 조금 낮은 음을 편안하게 따라 불러 보세요.") else beginHigh(ready)
        }
        val target = state.targetMidi ?: return state
        val ready = state.copy(confirmations = emptyList(), confirmationConfidences = emptyList(), measurement = null)
        return when (state.stage) {
            RangeStage.LOW -> if (target <= VocalRangeProfile.SUPPORTED.first) beginHigh(ready.copy(lowMidi = target)) else
                ready.copy(lowMidi = target, targetMidi = target - 1, message = "잘 했어요. 조금 더 낮춰보세요. 어려우면 여기서 멈춰도 좋아요.")
            RangeStage.HIGH -> if (target >= VocalRangeProfile.SUPPORTED.last) finish(ready.copy(highMidi = target)) else
                ready.copy(highMidi = target, targetMidi = target + 1, message = "잘 했어요. 조금 높여보세요. 목에 힘이 들면 즉시 멈추세요.")
            else -> state
        }
    }
    private fun beginHigh(state: RangeDiagnosisState): RangeDiagnosisState {
        val start = state.startHz?.let(Music::midi) ?: return state
        return if (start >= VocalRangeProfile.SUPPORTED.last) finish(state) else state.copy(
            stage = RangeStage.HIGH, targetMidi = start + 1, measurement = null, confirmations = emptyList(), confirmationConfidences = emptyList(), running = false,
            message = "이제 시작음보다 조금 높은 음을 따라 불러보세요. 무리하게 고음을 내지 마세요.")
    }
    private fun finish(state: RangeDiagnosisState) = state.copy(stage = RangeStage.RESULT, running = false,
        confirmations = emptyList(), confirmationConfidences = emptyList(), measurement = null, message = "두 번씩 편안하게 확인한 음만 모았어요.")
    fun difficult(state: RangeDiagnosisState): RangeDiagnosisState = when (state.stage) {
        RangeStage.LOW -> beginHigh(state)
        RangeStage.HIGH -> finish(state)
        RangeStage.START -> state.copy(running = false, measurement = null, confirmations = emptyList(), confirmationConfidences = emptyList(), message = "쉬었다가 더 편안한 소리로 다시 측정해 주세요.")
        else -> state
    }
    fun stop(state: RangeDiagnosisState): RangeDiagnosisState = if (state.startHz == null) RangeDiagnosisState(
        message = "진단을 중단했어요. 기존에 저장한 음역은 유지됩니다.") else finish(state).copy(
        partial = true, message = "진단을 중단했어요. 이미 두 번 편안하게 확인한 범위만 결과에 포함됩니다.")
}

/** Small, versioned text codec for DataStore; rejects malformed data instead of silently replacing history. */
object RangeHistoryCodec {
    fun encode(profiles: List<VocalRangeProfile>): String = profiles.joinToString("\n") {
        listOf("2", it.id, it.timestamp, it.startHz, it.lowMidi, it.highMidi, it.source.name, it.previousId ?: "", it.partial, it.startConfidence ?: "").joinToString("|")
    }
    fun decode(text: String): List<VocalRangeProfile> = if (text.isBlank()) emptyList() else text.lineSequence().map { line ->
        val parts = line.split('|')
        require((parts.size == 9 && parts[0] == "1") || (parts.size == 10 && parts[0] == "2")) { "음역 기록 형식을 읽을 수 없습니다." }
        VocalRangeProfile(parts[1], parts[2].toLong(), parts[3].toDouble(), parts[4].toInt(), parts[5].toInt(),
            RangeSource.valueOf(parts[6]), parts[7].ifEmpty { null }, parts[8].toBooleanStrict(), parts.getOrNull(9)?.takeIf { it.isNotEmpty() }?.toDouble())
    }.toList().also { require(it.map { p -> p.id }.distinct().size == it.size) }
}
