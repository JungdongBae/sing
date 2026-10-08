package com.jungdong.sing.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.GsonBuilder
import com.jungdong.sing.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.completionDataStore by preferencesDataStore(name = "first_song")
data class BackingFile(val name: String, val fileName: String, val durationMs: Long, val semitones: Int)
data class CompletionRecords(
    val version: Int = 1, val song: SongMetadata? = null, val selectedKey: Int? = null,
    val keyProfileId: String? = null, val keyFinalized: Boolean = false,
    val week: Int = 5, val day: Int = 1, val attempt: Int = 1,
    val sessions: List<PracticeSession> = emptyList(), val recordings: List<RecordingSession> = emptyList(),
    val trials: List<KeyTrial> = emptyList(), val calibrationMs: Int? = null,
    val calibrationRoute: String? = null, val backing: BackingFile? = null,
) {
    fun current(date: String): PracticeSession = sessions.findLast {
        it.date == date && it.week == week && it.day == day && it.attempt == attempt && it.songId == song?.id
    } ?: PracticeSession(date, week, day, 0, songId = song?.id, attempt = attempt)
    val completionRate get() = sessions.filter { it.completed && it.songId == song?.id }
        .map { it.week to it.day }.distinct().size / 20.0
}
object CompletionCodec {
    private val gson = GsonBuilder().serializeNulls().create()
    fun encode(records: CompletionRecords): String = gson.toJson(records)
    fun decode(text: String): CompletionRecords {
        if (text.isBlank()) return CompletionRecords()
        val r = gson.fromJson(text, CompletionRecords::class.java)
        require(r.version == 1 && r.week in 5..8 && r.day in 1..5 && r.attempt >= 1)
        require(r.selectedKey == null || r.selectedKey in -24..24)
        require(r.calibrationMs == null || r.calibrationMs in -500..500)
        r.song?.let { song ->
            require(song.notes.isNotEmpty() && song.notes.size <= 20000 && song.id.isNotBlank())
            require(song.notes.all { it.midi in 0..127 && it.startMs >= 0 && it.durationMs > 0 && it.bar >= 1 })
            require(song.notes.zipWithNext().all { (a, b) -> a.endMs <= b.startMs } && song.durationMs <= 600000)
            require(song.sections.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= song.durationMs })
        }
        r.sessions.forEach { require(it.week in 5..8 && it.day in 1..5 && it.seconds in 0..1200 && it.attempt >= 1) }
        r.recordings.forEach { require(it.id.matches(Regex("[a-zA-Z0-9_-]+")) && it.fileName == "${it.id}.wav" && it.durationMs > 0) }
        require(r.recordings.map { it.id }.distinct().size == r.recordings.size)
        return r
    }
}
class CompletionStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.completionDataStore)
    private val key = stringPreferencesKey("records_v1")
    val records = dataStore.data.map { CompletionCodec.decode(it[key] ?: "") }.flowOn(Dispatchers.IO)
    suspend fun update(transform: (CompletionRecords) -> CompletionRecords) {
        dataStore.edit { prefs -> prefs[key] = CompletionCodec.encode(transform(CompletionCodec.decode(prefs[key] ?: ""))) }
    }
    suspend fun practice(session: PracticeSession) = update { r -> r.copy(sessions = r.sessions.filterNot {
        it.date == session.date && it.week == session.week && it.day == session.day && it.attempt == session.attempt && it.songId == session.songId
    } + session) }
    suspend fun recording(session: RecordingSession) = update { r ->
        require(r.recordings.none { it.id == session.id }); r.copy(recordings = r.recordings + session)
    }
    suspend fun deleteRecording(id: String) = update { r -> r.copy(recordings = r.recordings.filterNot { it.id == id }, trials = r.trials.filterNot { it.recordingId == id }, keyFinalized = false) }
}
