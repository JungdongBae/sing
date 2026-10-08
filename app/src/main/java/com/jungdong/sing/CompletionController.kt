package com.jungdong.sing

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.jungdong.sing.audio.*
import com.jungdong.sing.core.*
import com.jungdong.sing.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.Collections
import kotlin.math.*

data class CompletionUi(
    val records: CompletionRecords = CompletionRecords(), val loading: Boolean = true, val loadFailed: Boolean = false,
    val importing: Boolean = false, val draft: SongImportResult? = null, val draftName: String = "",
    val sectionId: String? = null, val recording: Boolean = false, val playing: Boolean = false,
    val countdown: Int = 0, val positionMs: Long = 0, val pitch: Pitch? = null, val target: Int? = null,
    val timerRunning: Boolean = false, val seconds: Int = 0, val calibrationMessage: String? = null,
    val saving: Boolean = false,
    val latestId: String? = null,
)

/** Shares the existing audio engine and vocal profile. All media stays in app-private storage. */
class CompletionController(
    private val context: Context, private val scope: CoroutineScope, private val audio: AudioEngine,
    private val stopAll: () -> Unit, private val joinBasic: suspend () -> Unit,
    private val pauseBasic: () -> Unit, private val basicState: () -> SingState,
    private val error: (String) -> Unit,
) {
    private val store = CompletionStore(context)
    private val files = File(context.filesDir, "first_song").apply { mkdirs() }
    private val mutable = MutableStateFlow(CompletionUi())
    val state = mutable.asStateFlow()
    private var audioJob: Job? = null
    private var timerJob: Job? = null
    private var importJob: Job? = null
    private var cachedSongId: String? = null
    private var cachedProfileId: String? = null
    private var cachedKeys: List<KeyRecommendation> = emptyList()
    private var pendingWrites = 0
    @Volatile private var generation = 0
    @Volatile private var player: MediaPlayer? = null
    init {
        scope.launch {
            try {
                store.records.collect { records ->
                    mutable.update { it.copy(records = records, loading = false,
                        seconds = if (it.timerRunning) it.seconds else records.current(LocalDate.now().toString()).seconds) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(loading = false, loadFailed = true) }
                error("완성곡 기록을 읽지 못했어요. 파일은 변경하지 않았습니다. 다시 실행해 주세요.")
            }
        }
    }
    fun stop() {
        generation++; audioJob?.cancel(); runCatching { player?.pause() }
        mutable.update { it.copy(recording = false, playing = false, countdown = 0, pitch = null, target = null) }
    }
    suspend fun joinAudio() { audioJob?.join() }
    private fun launchAudio(recording: Boolean = false, block: suspend CoroutineScope.(Int) -> Unit) {
        if (state.value.loading || state.value.loadFailed) return
        val previous = audioJob
        stopAll()
        val token = generation
        mutable.update { it.copy(recording = recording, playing = !recording, positionMs = 0, pitch = null, target = null) }
        audioJob = scope.launch {
            previous?.join(); joinBasic()
            try { audio.focused { block(token) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error(e.message ?: "오디오 작업을 완료하지 못했어요.") }
            finally {
                if (token == generation) mutable.update { it.copy(recording = false, playing = false, countdown = 0, pitch = null, target = null) }
            }
        }
    }
    private fun update(after: suspend () -> Unit = {}, block: (CompletionRecords) -> CompletionRecords) {
        if (state.value.loading || state.value.loadFailed) return
        pendingWrites++
        mutable.update { it.copy(saving = true) }
        scope.launch {
            try { store.update(block); after() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error("완성곡 기록을 저장하지 못했어요. 다시 시도해 주세요.") }
            finally { pendingWrites--; mutable.update { it.copy(saving = pendingWrites > 0) } }
        }
    }
    fun open() { pauseBasic(); stopAll(); checkRoute() }
    fun onProfileChanged(profile: VocalRangeProfile?) {
        if (state.value.loading) return
        val r = state.value.records
        if (r.keyProfileId != profile?.id) update { it.copy(selectedKey = null, keyProfileId = profile?.id, keyFinalized = false) }
    }
    fun checkRoute() {
        val r = state.value.records
        if (r.calibrationMs != null && r.calibrationRoute != audio.routeSignature()) {
            update { it.copy(calibrationMs = null, calibrationRoute = null) }
            mutable.update { it.copy(calibrationMessage = "출력 장치가 바뀌었어요. 지연을 다시 보정해 주세요.") }
        }
    }
    fun chooseSection(id: String?) { stopAll(); mutable.update { it.copy(sectionId = id, latestId = null) } }
    private fun name(uri: Uri) = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0).take(120) else "사용자 파일"
    } ?: "사용자 파일"
    fun importMelody(uri: Uri) {
        if (state.value.importing || state.value.loading || state.value.loadFailed) return
        stopAll(); pauseTimer(); mutable.update { it.copy(importing = true, draft = null) }
        importJob = scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val fileName = name(uri)
                    val bytes: ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(chunk); if (n < 0) break
                            require(buffer.size() + n <= SongImporter.MAX_BYTES) { "MIDI/MusicXML은 2MB 이하로 가져와 주세요." }; buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    } ?: throw IllegalArgumentException("곡 자료를 열 수 없습니다.")
                    fileName to SongImporter.parse(bytes)
                }
                mutable.update { it.copy(draftName = pair.first, draft = pair.second) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error(e.message ?: "곡 자료를 읽을 수 없습니다.") }
            finally { mutable.update { it.copy(importing = false) } }
        }
    }
    fun acceptMelody(index: Int, citation: String, confirmed: Boolean) {
        val draft = state.value.draft ?: return
        val melody = draft.melodies.getOrNull(index) ?: return
        if (!confirmed || citation.isBlank()) { error("자료 출처와 멜로디·마디·타이밍을 확인해 주세요."); return }
        val song = SongMetadata(UUID.randomUUID().toString(), "옛사랑 – 이문세", state.value.draftName, citation.trim().take(500),
            melody.referenceKey, melody.notes, SongMetadata.sections(melody.notes), true)
        stopAll(); pauseTimer()
        val oldBacking = state.value.records.backing
        update(after = { oldBacking?.let { withContext(Dispatchers.IO) { File(files, it.fileName).delete() } } }) {
            it.copy(song = song, selectedKey = null, keyFinalized = false, keyProfileId = basicState().range?.id, week = 5, day = 1, attempt = 1, backing = null)
        }
        mutable.update { it.copy(draft = null, sectionId = song.sections.firstOrNull()?.id, latestId = null, seconds = 0) }
    }
    fun cancelDraft() { mutable.update { it.copy(draft = null) } }
    fun cancelImport() { importJob?.cancel() }
    fun renameSection(id: String, label: String) {
        if (label.isBlank()) return
        update { r -> r.copy(song = r.song?.copy(sections = r.song.sections.map { if (it.id == id) it.copy(label = label.trim().take(60)) else it })) }
    }
    fun recommendations(): List<KeyRecommendation> {
        val song = state.value.records.song; val profile = basicState().range
        if (song?.id != cachedSongId || profile?.id != cachedProfileId) {
            cachedKeys = KeyRecommender.recommend(song, profile)
            cachedSongId = song?.id; cachedProfileId = profile?.id
        }
        return cachedKeys
    }
    fun selectKey(shift: Int) {
        stopAll()
        if (recommendations().none { it.semitones == shift }) return
        update { it.copy(selectedKey = shift, keyFinalized = false, keyProfileId = basicState().range?.id) }
    }
    fun finalizeKey() {
        val r = state.value.records; val song = r.song ?: return; val profile = basicState().range ?: return
        val selected = KeyRecommender.finalize(recommendations(), r.trials, song.id, profile.id)
        if (selected == null) { error("같은 구간에서 모든 후보를 녹음·평가해 주세요. 유효 음성 70% 이상, 목표음 맞춤 50% 이상, 편안함 4점 이상인 키가 필요해요. 적합한 키가 없으면 최종 선택하지 않습니다."); return }
        stopAll(); update { it.copy(selectedKey = selected, keyProfileId = profile.id, keyFinalized = true) }
    }
    private fun practiceNotes(): List<MelodyNote> {
        val r = state.value.records
        return r.song?.notesFor(state.value.sectionId) ?: OriginalExercise.notes(basicState().range)
    }
    fun reference() {
        val r = state.value.records
        val first = practiceNotes().firstOrNull()?.midi ?: return
        val shift = if (r.song == null) 0 else r.selectedKey ?: run { error("먼저 적합한 키 후보를 선택해 주세요."); return }
        launchAudio { audio.play(listOf(NoteEvent(first + shift, 2)), 60) }
    }
    fun melody() {
        val notes = practiceNotes(); val r = state.value.records
        val shift = if (r.song == null) 0 else r.selectedKey ?: run { error("먼저 적합한 키 후보를 선택해 주세요."); return }
        launchAudio { token -> audio.playMelody(notes, shift) { position ->
            mutable.update { if (generation == token) it.copy(positionMs = position, target = MelodyAnalyzer.target(notes, position)?.midi?.plus(shift)) else it }
        } }
    }
    fun record(withBacking: Boolean = false) {
        checkRoute()
        val r = state.value.records; val profile = basicState().range
        val notes = if (r.song == null) emptyList() else practiceNotes()
        val shift = if (r.song == null) 0 else r.selectedKey ?: run { error("곡 녹음 전에 적합한 키를 선택해 주세요."); return }
        if (r.song != null && (r.keyProfileId != profile?.id || recommendations().none { it.semitones == shift })) { error("음역이 바뀌었어요. 키를 다시 선택해 주세요."); return }
        if (withBacking && (r.backing == null || r.backing.semitones != shift || state.value.sectionId != null)) {
            error("전체 곡과 선택 키에 맞는 반주 파일이 필요해요. 반주 피치 변경은 지원하지 않습니다."); return
        }
        val id = UUID.randomUUID().toString(); val partial = File(files, "$id.partial"); val finished = File(files, "$id.wav")
        val tolerance = basicState().tolerance
        val calibration = r.calibrationMs.takeIf { r.calibrationRoute == audio.routeSignature() }
        val sectionId = state.value.sectionId
        val sectionLabel = r.song?.section(sectionId)?.label ?: if (r.song == null) "자유 발성" else "전체 곡"
        launchAudio(recording = true) { token ->
            var writer: WavWriter? = null; var completed = false; var deviceError = false
            val frames = Collections.synchronizedList(mutableListOf<PerformanceFrame>())
            var backingPlayer: MediaPlayer? = null
            try {
                for (n in 3 downTo 1) { mutable.update { if (token == generation) it.copy(countdown = n) else it }; delay(1000) }
                mutable.update { if (token == generation) it.copy(countdown = 0) else it }
                if (withBacking) {
                    backingPlayer = MediaPlayer().also { player = it }
                    withContext(Dispatchers.IO) { configurePlayer(backingPlayer!!, File(files, r.backing!!.fileName)) }
                }
                delay(350)
                coroutineScope {
                    val input = launch {
                        audio.capture(onPcm = { rate, pcm ->
                            if (writer == null) { writer = WavWriter(partial, rate); backingPlayer?.start() }
                            writer!!.write(pcm)
                            val position = writer!!.durationMs
                            mutable.update { if (token == generation) it.copy(positionMs = position) else it }
                        }, onFrame = { time, pitch ->
                            frames.add(PerformanceFrame(time, pitch))
                            val target = MelodyAnalyzer.target(notes, time - (calibration ?: 0))?.midi?.plus(shift)
                            mutable.update { if (token == generation) it.copy(pitch = pitch, target = target) else it }
                        }) { }
                    }
                    val limit = notes.lastOrNull()?.endMs?.plus(500) ?: 600000L
                    try {
                        while (isActive && state.value.positionMs < limit) delay(50)
                        completed = true
                    } finally { input.cancel(); audio.interrupt(); input.join() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { deviceError = true; throw e }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (player === backingPlayer) player = null
                    backingPlayer?.let { runCatching { it.stop() }; it.release() }
                    val take = writer
                    if (take != null) {
                        try {
                            take.close()
                            if (take.durationMs >= 300 && !deviceError && partial.renameTo(finished)) {
                                val report = if (notes.isNotEmpty()) withContext(Dispatchers.Default) {
                                    MelodyAnalyzer.analyze(r.song?.verifiedByUser == true, notes, shift,
                                        synchronized(frames) { frames.toList() }, tolerance, calibration)
                                } else null
                                val session = RecordingSession(id, System.currentTimeMillis(), finished.name, take.durationMs,
                                    r.song?.id, r.song?.sourceName, profile?.id, sectionId, sectionLabel,
                                    if (r.song == null) null else shift, !completed, report, tolerance = tolerance, calibrationMs = calibration)
                                try { store.recording(session); mutable.update { it.copy(latestId = id) } }
                                catch (_: Exception) { finished.delete(); error("녹음 기록 저장에 실패했어요. 저장되지 않은 파일은 제거했습니다.") }
                            } else partial.delete()
                        } catch (_: Exception) { partial.delete(); error("녹음 파일을 저장하지 못했어요. 저장 공간을 확인해 주세요.") }
                    }
                }
            }
        }
    }
    private fun configurePlayer(p: MediaPlayer, file: File) {
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setDataSource(file.absolutePath); p.prepare()
    }
    private fun preparePlayer(file: File): MediaPlayer = MediaPlayer().apply {
        try {
            configurePlayer(this, file)
        } catch (e: Exception) { release(); throw e }
    }
    fun playRecording(id: String) {
        val session = state.value.records.recordings.find { it.id == id } ?: return
        playFile(File(files, session.fileName))
    }
    private fun playFile(file: File) = launchAudio { token ->
        val p = MediaPlayer(); player = p
        try {
            withContext(Dispatchers.IO) { configurePlayer(p, file) }
            currentCoroutineContext().ensureActive()
            p.start()
            while (isActive && p.isPlaying) { mutable.update { if (token == generation) it.copy(positionMs = p.currentPosition.toLong()) else it }; delay(100) }
        } finally { if (player === p) player = null; runCatching { p.stop() }; p.release() }
    }
    fun assess(id: String, assessment: VocalSelfAssessment) {
        val session = state.value.records.recordings.find { it.id == id } ?: return
        update { r ->
            val trial = if (session.songId != null && session.profileId != null && session.semitones != null && session.report != null)
                KeyTrial(session.songId, session.profileId, session.sectionId, session.semitones,
                    session.report.pitchAccuracy, session.report.coverage, assessment.comfort, session.id) else null
            r.copy(recordings = r.recordings.map { if (it.id == id) it.copy(selfAssessment = assessment) else it },
                trials = r.trials.filterNot { it.recordingId == id } + listOfNotNull(trial), keyFinalized = false)
        }
    }
    fun deleteRecording(id: String) {
        val record = state.value.records.recordings.find { it.id == id } ?: return
        stopAll()
        scope.launch {
            try {
                joinAudio()
                val deleted = withContext(Dispatchers.IO) { val file = File(files, record.fileName); !file.exists() || file.delete() }
                if (!deleted) { error("녹음 파일을 삭제하지 못했어요. 기록을 유지했습니다. 다시 시도해 주세요."); return@launch }
                store.deleteRecording(id)
            }
            catch (_: Exception) { error("녹음을 삭제하지 못했어요. 다시 시도해 주세요.") }
        }
    }
    fun importBacking(uri: Uri, semitones: Int, confirmed: Boolean) {
        if (!confirmed || state.value.importing || state.value.loading || state.value.loadFailed || semitones !in -24..24) return
        stopAll(); mutable.update { it.copy(importing = true) }
        importJob = scope.launch {
            val file = File(files, "${UUID.randomUUID()}.backing")
            try {
                val backing = withContext(Dispatchers.IO) {
                    val title = name(uri)
                    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= 100L * 1024 * 1024) { "반주는 100MB 이하로 가져와 주세요." }; output.write(buffer, 0, n)
                        }
                    } } ?: throw IllegalArgumentException("반주 파일을 열 수 없습니다.")
                    val p = preparePlayer(file)
                    try { require(p.duration in 1..600000) { "10분 이하의 반주를 사용해 주세요." }; BackingFile(title, file.name, p.duration.toLong(), semitones) }
                    finally { p.release() }
                }
                val old = state.value.records.backing
                store.update { it.copy(backing = backing) }
                old?.let { withContext(Dispatchers.IO) { File(files, it.fileName).delete() } }
            } catch (cancelled: CancellationException) { file.delete(); throw cancelled }
            catch (e: Exception) { file.delete(); error(e.message ?: "반주를 가져오지 못했어요.") }
            finally { mutable.update { it.copy(importing = false) } }
        }
    }
    fun playBacking() { state.value.records.backing?.let { playFile(File(files, it.fileName)) } }
    fun removeBacking() {
        val old = state.value.records.backing ?: return; stopAll()
        scope.launch { try { store.update { it.copy(backing = null) }; withContext(Dispatchers.IO) { File(files, old.fileName).delete() } } catch (_: Exception) { error("반주 삭제에 실패했어요.") } }
    }
    fun calibration(value: Int?) {
        if (value != null && value !in -500..500) { error("지연 보정값은 -500~500ms 안에서 설정해 주세요."); return }
        stopAll(); update { it.copy(calibrationMs = value, calibrationRoute = if (value == null) null else audio.routeSignature()) }
    }
    fun calibrate() = launchAudio { _ ->
        mutable.update { it.copy(calibrationMessage = "조용히 기다려 주세요. 스피커의 클릭 4번을 마이크로 확인합니다.") }
        val expected = Collections.synchronizedList(mutableListOf<Long>())
        val peaks = Collections.synchronizedList(mutableListOf<Long>())
        var started = 0L; var samples = 0L; var noiseSquares = 0.0; var noiseSamples = 0L; var lastPeak = -1000L
        coroutineScope {
            val input = launch {
                audio.capture(onStarted = { started = SystemClock.elapsedRealtime() }, onPcm = { rate, pcm ->
                    pcm.forEach { sample ->
                        val ms = samples * 1000 / rate; val amplitude = abs(sample.toDouble() / 32768)
                        if (ms < 600) { noiseSquares += amplitude * amplitude; noiseSamples++ }
                        val floor = if (noiseSamples > 0) sqrt(noiseSquares / noiseSamples) else 0.0
                        if (ms >= 900 && amplitude > max(.035, floor * 6) && ms - lastPeak > 400) { peaks.add(ms); lastPeak = ms }
                        samples++
                    }
                }) { }
            }
            try {
                while (started == 0L) delay(10)
                delay(1100)
                audio.play(List(4) { NoteEvent(null) }, 60, tones = false, clicks = true) { _, _ -> expected.add(SystemClock.elapsedRealtime() - started) }
                delay(300)
            } finally { input.cancel(); audio.interrupt(); input.join() }
        }
        val result = LatencyCalibration.estimate(synchronized(expected) { expected.toList() }, synchronized(peaks) { peaks.toList() })
        if (result == null) mutable.update { it.copy(calibrationMessage = "클릭을 안정적으로 확인하지 못했어요. 보정값은 변경하지 않았습니다. 조용한 곳에서 다시 시도하거나 직접 입력해 주세요.") }
        else {
            store.update { it.copy(calibrationMs = result, calibrationRoute = audio.routeSignature()) }
            mutable.update { it.copy(calibrationMessage = "지연 추정 $result ms를 적용했어요. 출력 장치가 바뀌면 다시 확인해 주세요. 실기기 정확도 검증이 필요합니다.") }
        }
    }
    fun selectDay(week: Int, day: Int) {
        if (week !in 5..8 || day !in 1..5) return
        pauseTimer(); stopAll(); update { it.copy(week = week, day = day) }
    }
    fun mastery(value: Boolean) {
        update { r ->
                val session = (r.lessonProgress() ?: r.current(LocalDate.now().toString())).copy(mastered = value)
                r.copy(sessions = r.sessions.filterNot { it.date == session.date && it.week == session.week && it.day == session.day && it.attempt == session.attempt && it.songId == session.songId } + session)
        }
    }
    fun repeatWeek() {
        pauseTimer(); stopAll(); update { it.copy(day = 1, attempt = it.attempt + 1) }
    }
    fun nextDay() {
        val r = state.value.records; val s = r.lessonProgress()
        if (s == null || !s.completed || !s.mastered) { error("20분 완료와 숙련 자가 확인이 필요해요. 어려우면 현재 주차를 반복해 주세요."); return }
        val next = CompletionTraining.next(r.week, r.day) ?: return
        pauseTimer(); stopAll(); update { it.copy(week = next.first, day = next.second) }
    }
    fun toggleTimer() {
        if (state.value.loading || state.value.loadFailed || state.value.saving) return
        if (state.value.timerRunning) { pauseTimer(); return }
        if (state.value.seconds >= 1200) return
        pauseBasic(); mutable.update { it.copy(timerRunning = true) }
        val r = state.value.records; var date = LocalDate.now().toString()
        timerJob = scope.launch {
            var last = SystemClock.elapsedRealtime(); var remainder = 0L
            try {
                while (isActive && state.value.seconds < 1200) {
                    delay(250); val now = SystemClock.elapsedRealtime()
                    if (LocalDate.now().toString() != date) {
                        date = LocalDate.now().toString(); remainder = 0
                        mutable.update { it.copy(seconds = state.value.records.current(date).seconds) }
                    } else remainder += now - last
                    last = now
                    if (remainder >= 1000) {
                        val add = (remainder / 1000).toInt(); remainder %= 1000
                        mutable.update { it.copy(seconds = (it.seconds + add).coerceAtMost(1200)) }
                        val existing = state.value.records.current(date)
                        store.progress(existing.copy(week = r.week, day = r.day, attempt = r.attempt, songId = r.song?.id, seconds = state.value.seconds))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error("연습 시간을 저장하지 못했어요.") }
            finally { mutable.update { it.copy(timerRunning = false) } }
        }
    }
    fun pauseTimer() { timerJob?.cancel(); mutable.update { it.copy(timerRunning = false) } }
    fun background() { pauseTimer(); stop(); importJob?.cancel() }
}
