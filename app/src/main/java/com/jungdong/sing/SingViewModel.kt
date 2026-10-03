package com.jungdong.sing

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jungdong.sing.audio.AudioEngine
import com.jungdong.sing.core.*
import com.jungdong.sing.data.PracticeStore
import com.jungdong.sing.data.RangeStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.UUID

data class SingState(
    val target: Int = 48, val bpm: Int = 60, val pitch: Pitch? = null,
    val listening: Boolean = false, val playing: Boolean = false, val metronome: Boolean = false,
    val beat: Int = -1, val error: String? = null, val question: EarQuestion = EarQuestion.next(),
    val questionHeard: Boolean = false, val earAnswer: Boolean? = null,
    val earCorrect: Int = 0, val earTotal: Int = 0,
    val tapError: Double? = null, val song: Boolean = false,
    val today: LocalDate = LocalDate.now(), val seconds: Int = 0,
    val sessionRunning: Boolean = false, val completedDays: Int = 0,
    val history: List<Pair<LocalDate, Int>> = emptyList(), val selectedWeek: Int = 1,
    val rangeHistory: List<VocalRangeProfile> = emptyList(), val rangeLoading: Boolean = true,
    val tolerance: Int = 25, val diagnosisOpen: Boolean = false,
    val diagnosis: RangeDiagnosisState = RangeDiagnosisState(), val rangeSaving: Boolean = false,
    val songRoot: Int = 48,
) {
    val range get() = rangeHistory.lastOrNull()
    val targetBounds get() = range?.comfortable ?: 40..60
    val earAvailable get() = range == null || range!!.semitones > 0
    val songAvailable get() = range == null || range!!.songRoots != null
}

class SingViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PracticeStore(application)
    private val rangeStore = RangeStore(application)
    private val mutable = MutableStateFlow(SingState(target = store.target(), bpm = store.bpm(),
        seconds = store.seconds(LocalDate.now()), completedDays = store.completedDays(), history = store.history(),
        selectedWeek = Training.weekForDay(store.completedDays() + 1).number))
    val state = mutable.asStateFlow()
    private val audio = AudioEngine(application) { viewModelScope.launch { stopAudio() } }
    private var audioJob: Job? = null
    private var timerJob: Job? = null
    @Volatile private var audioGeneration = 0
    private var beatTimeMs: Long? = null

    init {
        viewModelScope.launch {
            var firstLoad = true
            try {
                rangeStore.records.collect { records ->
                    val profile = records.current
                    val changed = profile?.id != state.value.range?.id
                    if (changed) stopAudio()
                    val target = if (changed && profile != null) profile.center else state.value.target
                    if (changed && profile != null) store.setTarget(target)
                    mutable.update { it.copy(rangeHistory = records.profiles, rangeLoading = false, tolerance = records.tolerance,
                        diagnosisOpen = it.diagnosisOpen || (firstLoad && !records.introSeen && profile == null),
                        target = target, songRoot = if (changed) profile?.recommendedSongRoot ?: 48 else it.songRoot,
                        question = if (changed) profile?.let { p -> EarQuestion.nextInRange(p.recommended) } ?: EarQuestion.next() else it.question,
                        questionHeard = if (changed) false else it.questionHeard, earAnswer = if (changed) null else it.earAnswer) }
                    firstLoad = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(rangeLoading = false, error = "저장된 음역 기록을 읽지 못했어요. 기존 파일은 변경하지 않았습니다. 다시 실행해 주세요.") } }
        }
    }

    fun target(midi: Int) {
        stopAudio()
        val bounds = state.value.targetBounds
        val value = midi.coerceIn(bounds.first, bounds.last)
        store.setTarget(value)
        mutable.update { it.copy(target = value, pitch = null) }
    }
    fun bpm(value: Int) {
        val bpm = value.coerceIn(50, 120)
        if (bpm == state.value.bpm) return
        val restart = state.value.metronome
        stopAudio()
        store.setBpm(bpm)
        mutable.update { it.copy(bpm = bpm, tapError = null) }
        if (restart) metronome()
    }
    fun selectWeek(week: Int) { mutable.update { it.copy(selectedWeek = week.coerceIn(1, 4)) } }
    fun selectSong(song: Boolean) { stopAudio(); mutable.update { it.copy(song = song) } }
    fun error(message: String) { mutable.update { it.copy(error = message) } }

    fun stopAudio() {
        audioGeneration++
        audioJob?.cancel()
        audio.interrupt()
        beatTimeMs = null
        mutable.update { it.copy(listening = false, playing = false, metronome = false, beat = -1, pitch = null,
            diagnosis = RangeWorkflow.interrupted(it.diagnosis)) }
    }
    private fun launchAudio(listening: Boolean = false, metronome: Boolean = false, diagnosis: Boolean = false,
                            block: suspend CoroutineScope.() -> Unit) {
        val previous = audioJob
        stopAudio()
        val generation = audioGeneration
        mutable.update { it.copy(listening = listening, playing = !listening || metronome,
            metronome = metronome, error = null, tapError = null,
            diagnosis = if (diagnosis) RangeWorkflow.begin(it.diagnosis) else it.diagnosis) }
        audioJob = viewModelScope.launch {
            previous?.join()
            try { audio.acquireFocus(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                error(e.message ?: "오디오 오류가 발생했습니다. 다시 시도해 주세요.")
                if (generation == audioGeneration && diagnosis) mutable.update { it.copy(diagnosis = RangeWorkflow.interrupted(it.diagnosis, MeasurementFailure.DEVICE_ERROR)) }
            }
            finally {
                audio.releaseFocus()
                if (generation == audioGeneration) {
                    beatTimeMs = null
                    mutable.update { it.copy(listening = false, playing = false, metronome = false, beat = -1, pitch = null) }
                }
            }
        }
    }
    fun listen() {
        if (state.value.listening) { stopAudio(); return }
        launchAudio(listening = true) {
            val generation = audioGeneration
            audio.capture { pitch ->
                mutable.update { if (generation == audioGeneration) it.copy(pitch = pitch) else it }
            }
        }
    }
    fun reference() {
        val target = state.value.target
        launchAudio { audio.play(listOf(NoteEvent(target, 2)), 60) }
    }
    fun newQuestion() { stopAudio(); mutable.update { it.copy(question = it.range?.let { p -> EarQuestion.nextInRange(p.recommended) } ?: EarQuestion.next(), questionHeard = false, earAnswer = null) } }
    fun hearQuestion() {
        if (!state.value.earAvailable) { error("높낮이를 비교하려면 편안한 음이 두 개 이상 필요해요. 편안할 때 음역을 다시 측정해 주세요."); return }
        val question = state.value.question
        mutable.update { it.copy(questionHeard = false) }
        launchAudio {
            audio.play(listOf(NoteEvent(question.first), NoteEvent(null), NoteEvent(question.second)), 80)
            mutable.update { it.copy(questionHeard = true) }
        }
    }
    fun answer(higher: Boolean) {
        mutable.update {
            if (!it.questionHeard || it.earAnswer != null) it else {
                val correct = it.question.answer(higher)
                it.copy(earAnswer = correct, earTotal = it.earTotal + 1, earCorrect = it.earCorrect + if (correct) 1 else 0)
            }
        }
    }
    fun metronome() {
        if (state.value.metronome) { stopAudio(); return }
        val bpm = state.value.bpm
        launchAudio(metronome = true) {
            val generation = audioGeneration
            audio.play(List(4) { NoteEvent(null) }, bpm, tones = false, clicks = true, loop = true) { beat, _ ->
                // Main-thread timestamp is shared with the tap handler.
                val playedAt = SystemClock.elapsedRealtime()
                viewModelScope.launch {
                    if (generation == audioGeneration) {
                        beatTimeMs = playedAt
                        mutable.update { it.copy(beat = beat) }
                    }
                }
            }
        }
    }
    fun tap() {
        val previousBeat = beatTimeMs ?: return
        mutable.update { it.copy(tapError = Rhythm.tapErrorMs((SystemClock.elapsedRealtime() - previousBeat).toDouble(), it.bpm)) }
    }
    fun melody(follow: Boolean) {
        val snapshot = state.value
        val events = if (snapshot.song) snapshot.range?.song(snapshot.songRoot) ?: if (snapshot.range == null) Songs.littleStar else null
            else snapshot.range?.melody() ?: Songs.shortMelody
        if (events == null) { error("현재 확인한 음역 안에서는 작은별 전체를 부르기 어려워요. 짧은 패턴으로 연습해 주세요."); return }
        val bpm = state.value.bpm
        launchAudio(listening = follow) {
            val generation = audioGeneration
            coroutineScope {
                val input = if (follow) launch {
                    audio.capture { pitch -> mutable.update { if (generation == audioGeneration) it.copy(pitch = pitch) else it } }
                } else null
                try {
                    audio.play(events, bpm, tones = !follow, clicks = true) { beat, note ->
                        mutable.update { if (generation == audioGeneration) it.copy(beat = beat, target = note ?: it.target) else it }
                    }
                } finally {
                    input?.cancel()
                    if (follow) audio.interrupt()
                    input?.join()
                }
            }
        }
    }

    fun songRoot(root: Int) {
        val bounds = state.value.range?.songRoots ?: if (state.value.range == null) 48..48 else return
        stopAudio()
        mutable.update { it.copy(songRoot = root.coerceIn(bounds.first, bounds.last)) }
    }
    fun weeklyNotes(): List<Int> = state.value.range?.let { if (state.value.selectedWeek == 1) listOf(it.center) else it.practiceNotes } ?: listOf(48, 50, 52)
    fun prepareWeeklyTool(tool: Int) {
        if (tool == 0) target(weeklyNotes().first())
        if (tool == 3) selectSong(state.value.selectedWeek == 4 && state.value.songAvailable)
    }
    fun openDiagnosis() {
        pauseSession(); stopAudio()
        mutable.update { it.copy(diagnosisOpen = true, diagnosis = RangeDiagnosisState(), error = null) }
    }
    fun closeDiagnosis() {
        if (state.value.rangeSaving) return
        stopAudio()
        mutable.update { it.copy(diagnosisOpen = false, diagnosis = RangeDiagnosisState()) }
        viewModelScope.launch { try { rangeStore.seen() } catch (_: Exception) { error("진단 안내 설정을 저장하지 못했어요. 다시 시도해 주세요.") } }
    }
    fun startDiagnosis() { stopAudio(); mutable.update { it.copy(diagnosis = RangeDiagnosisState(stage = RangeStage.START)) } }
    fun diagnosisReference() {
        val draft = state.value.diagnosis
        val target = if (draft.stage == RangeStage.RESULT) draft.startHz?.let(Music::midi) ?: 48 else
            draft.targetMidi ?: draft.confirmations.firstOrNull()?.let(Music::midi) ?: state.value.range?.center ?: 48
        launchAudio { audio.play(listOf(NoteEvent(target, 2)), 60) }
    }
    fun measureRange() {
        if (!state.value.diagnosis.canMeasure || state.value.rangeSaving) return
        val target = if (state.value.diagnosis.stage == RangeStage.START) null else state.value.diagnosis.targetMidi
        val tolerance = state.value.tolerance
        launchAudio(listening = true, diagnosis = true) {
            val generation = audioGeneration
            // Previous playback is joined and released above. Allow its acoustic tail to settle as well.
            delay(350)
            val samples = java.util.Collections.synchronizedList(mutableListOf<Pitch?>())
            coroutineScope {
                val input = launch { audio.capture { pitch ->
                    samples.add(pitch)
                    mutable.update { if (generation == audioGeneration) it.copy(pitch = pitch) else it }
                } }
                try {
                    val started = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - started < PitchWindow.DURATION_MS) {
                        delay(50)
                        val elapsed = (SystemClock.elapsedRealtime() - started).toInt().coerceAtMost(PitchWindow.DURATION_MS)
                        mutable.update { if (generation == audioGeneration) it.copy(diagnosis = it.diagnosis.copy(progressMs = elapsed)) else it }
                    }
                } finally {
                    input.cancel(); audio.interrupt(); input.join()
                }
            }
            val result = withContext(Dispatchers.Default) { PitchWindow.evaluate(synchronized(samples) { samples.toList() }, target, tolerance) }
            mutable.update { if (generation == audioGeneration) it.copy(diagnosis = RangeWorkflow.measured(it.diagnosis, result)) else it }
        }
    }
    fun comfortable() { mutable.update { it.copy(diagnosis = RangeWorkflow.comfortable(it.diagnosis)) } }
    fun difficult() { stopAudio(); mutable.update { it.copy(diagnosis = RangeWorkflow.difficult(it.diagnosis)) } }
    fun stopDiagnosis() { stopAudio(); mutable.update { it.copy(diagnosis = RangeWorkflow.stop(it.diagnosis)) } }
    fun microphonePermissionDenied() {
        stopAudio()
        mutable.update { it.copy(diagnosis = RangeWorkflow.interrupted(it.diagnosis, MeasurementFailure.PERMISSION_DENIED),
            error = "마이크 권한이 없어 측정을 시작하지 못했어요. 권한 설정에서 허용해 주세요. 듣기와 박자 연습은 계속 사용할 수 있어요.") }
    }
    fun saveDiagnosis() {
        val snapshot = state.value
        if (snapshot.rangeSaving) return
        val profile = snapshot.diagnosis.profile(UUID.randomUUID().toString(), System.currentTimeMillis(), snapshot.range?.id) ?: return
        saveRange(profile)
    }
    fun saveManualRange(low: Int, high: Int) {
        if (state.value.rangeSaving) return
        if (low !in VocalRangeProfile.SUPPORTED || high !in VocalRangeProfile.SUPPORTED || low > high) {
            error("최저음은 최고음보다 높을 수 없어요. C2~E5 안에서 편안한 범위를 선택해 주세요."); return
        }
        val old = state.value.range
        val profile = VocalRangeProfile(UUID.randomUUID().toString(), System.currentTimeMillis(),
            old?.startHz ?: Music.frequency((low + high) / 2), low, high, RangeSource.MANUAL, old?.id)
        saveRange(profile)
    }
    private fun saveRange(profile: VocalRangeProfile) {
        stopAudio()
        mutable.update { it.copy(rangeSaving = true, error = null) }
        viewModelScope.launch {
            try {
                rangeStore.save(profile)
                mutable.update { it.copy(diagnosisOpen = false, diagnosis = RangeDiagnosisState()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error("음역을 저장하지 못했어요. 기존 기록은 유지됩니다. 다시 시도해 주세요.") }
            finally { mutable.update { it.copy(rangeSaving = false) } }
        }
    }
    fun tolerance(value: Int) {
        stopAudio()
        viewModelScope.launch { try { rangeStore.tolerance(value.coerceIn(10, 100)) } catch (_: Exception) { error("허용 오차를 저장하지 못했어요. 다시 시도해 주세요.") } }
    }

    private fun refreshDate() {
        val today = LocalDate.now()
        if (today != state.value.today) {
            store.save(state.value.today, state.value.seconds)
            mutable.update { it.copy(today = today, seconds = store.seconds(today), history = store.history(), completedDays = store.completedDays()) }
        }
    }
    fun toggleSession() {
        refreshDate()
        if (state.value.sessionRunning) { pauseSession(); return }
        if (state.value.seconds >= Training.DAILY_SECONDS) return
        mutable.update { it.copy(sessionRunning = true) }
        timerJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var remainder = 0L
            while (isActive && state.value.seconds < Training.DAILY_SECONDS) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                if (LocalDate.now() != state.value.today) {
                    refreshDate(); remainder = 0
                } else remainder += now - last
                last = now
                val seconds = (remainder / 1000).toInt()
                if (seconds > 0) {
                    remainder %= 1000
                    mutable.update { it.copy(seconds = (it.seconds + seconds).coerceAtMost(Training.DAILY_SECONDS)) }
                    store.save(state.value.today, state.value.seconds)
                    mutable.update { it.copy(history = store.history(), completedDays = store.completedDays()) }
                }
            }
            mutable.update { it.copy(sessionRunning = false) }
            stopAudio()
        }
    }
    fun pauseSession() {
        timerJob?.cancel()
        store.save(state.value.today, state.value.seconds)
        mutable.update { it.copy(sessionRunning = false) }
    }
    fun foreground() { refreshDate() }
    fun background() { pauseSession(); stopAudio() }
    override fun onCleared() { background(); super.onCleared() }
}
