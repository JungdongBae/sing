package com.jungdong.sing

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jungdong.sing.audio.AudioEngine
import com.jungdong.sing.core.*
import com.jungdong.sing.data.PracticeStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

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
)

class SingViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PracticeStore(application)
    private val mutable = MutableStateFlow(SingState(target = store.target(), bpm = store.bpm(),
        seconds = store.seconds(LocalDate.now()), completedDays = store.completedDays(), history = store.history(),
        selectedWeek = Training.weekForDay(store.completedDays() + 1).number))
    val state = mutable.asStateFlow()
    private val audio = AudioEngine(application) { viewModelScope.launch { stopAudio() } }
    private var audioJob: Job? = null
    private var timerJob: Job? = null
    @Volatile private var audioGeneration = 0
    private var beatTimeMs: Long? = null

    fun target(midi: Int) {
        stopAudio()
        val value = midi.coerceIn(40, 60)
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
        mutable.update { it.copy(listening = false, playing = false, metronome = false, beat = -1, pitch = null) }
    }
    private fun launchAudio(listening: Boolean = false, metronome: Boolean = false,
                            block: suspend CoroutineScope.() -> Unit) {
        val previous = audioJob
        stopAudio()
        val generation = audioGeneration
        mutable.update { it.copy(listening = listening, playing = !listening || metronome,
            metronome = metronome, error = null, tapError = null) }
        audioJob = viewModelScope.launch {
            previous?.join()
            try { audio.acquireFocus(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error(e.message ?: "오디오 오류가 발생했습니다. 다시 시도해 주세요.") }
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
    fun newQuestion() { stopAudio(); mutable.update { it.copy(question = EarQuestion.next(), questionHeard = false, earAnswer = null) } }
    fun hearQuestion() {
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
        val events = if (state.value.song) Songs.littleStar else Songs.shortMelody
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
