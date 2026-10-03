package com.jungdong.sing.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import com.jungdong.sing.core.*
import kotlinx.coroutines.*
import kotlin.math.*

class AudioEngine(private val context: Context, onFocusLoss: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) onFocusLoss()
        }.build()
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null

    // Stop unblocks device I/O; the owning coroutine alone releases the resource in finally.
    fun interrupt() = synchronized(lock) {
        recorder?.let { runCatching { it.stop() } }
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    @SuppressLint("MissingPermission")
    suspend fun capture(onPitch: (Pitch?) -> Unit) = withContext(Dispatchers.IO) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "마이크 권한이 필요합니다. 설정에서 마이크 접근을 허용해 주세요."
        }
        val rate = listOf(22050, 44100, 48000).firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: error("이 기기에서 마이크 형식을 지원하지 않습니다.")
        val size = if (rate == 22050) 4096 else 8192
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val input = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minBuffer, size * 4)).build()
        val detector = PitchDetector(rate)
        val smoother = PitchSmoother()
        synchronized(lock) { recorder = input }
        try {
            check(input.state == AudioRecord.STATE_INITIALIZED) { "마이크를 초기화할 수 없습니다." }
            currentCoroutineContext().ensureActive()
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "마이크를 사용할 수 없습니다. 다른 앱의 녹음을 종료해 주세요." }
            val frame = ShortArray(size)
            val hop = ShortArray(size / 2)
            var filled = 0
            while (currentCoroutineContext().isActive) {
                val read = input.read(hop, 0, hop.size, AudioRecord.READ_BLOCKING)
                currentCoroutineContext().ensureActive()
                check(read > 0) { "마이크 읽기가 중단됐습니다. 다시 시작해 주세요." }
                frame.copyInto(frame, 0, read, size)
                hop.copyInto(frame, size - read, 0, read)
                filled += read
                if (filled >= size) onPitch(smoother.accept(detector.detect(frame)))
            }
        } finally {
            synchronized(lock) {
                if (recorder === input) recorder = null
                runCatching { input.stop() }
                input.release()
            }
        }
    }

    /** Static PCM looping avoids coroutine scheduling drift in the metronome.
     * UI beat callbacks follow the playback head, not the time PCM was queued. */
    suspend fun play(events: List<NoteEvent>, bpm: Int, tones: Boolean = true,
                     clicks: Boolean = false, loop: Boolean = false,
                     onBeat: (Int, Int?) -> Unit = { _, _ -> }) = withContext(Dispatchers.Default) {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "다른 앱이 오디오를 사용 중입니다. 잠시 후 다시 시도해 주세요."
        }
        var output: AudioTrack? = null
        try {
            val rate = 22050
            val framesPerBeat = (rate * 60.0 / bpm.coerceIn(50, 120)).roundToInt()
            val totalBeats = events.sumOf { it.beats }
            require(totalBeats > 0)
            val pcm = ShortArray(totalBeats * framesPerBeat)
            val beatNotes = ArrayList<Int?>()
            var start = 0
            events.forEach { event ->
                repeat(event.beats) { beatNotes.add(event.midi) }
                val duration = event.beats * framesPerBeat
                for (i in 0 until duration) {
                    val time = i.toDouble() / rate
                    var value = 0.0
                    if (tones && event.midi != null) {
                        val envelope = min(1.0, i / (rate * 0.015)) *
                            min(1.0, (duration - i) / (rate * 0.04))
                        value += 0.22 * envelope * sin(2 * PI * Music.frequency(event.midi) * time)
                    }
                    if (clicks && i % framesPerBeat < (rate * 0.035).toInt()) {
                        val beat = start / framesPerBeat + i / framesPerBeat
                        val clickTime = (i % framesPerBeat).toDouble() / rate
                        value += 0.35 * exp(-clickTime * 100) * sin(2 * PI * (if (beat % 4 == 0) 1500 else 1000) * clickTime)
                    }
                    pcm[start + i] = (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                }
                start += duration
            }
            currentCoroutineContext().ensureActive()
            val activeTrack = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            output = activeTrack
            synchronized(lock) { track = activeTrack }
            check(activeTrack.state != AudioTrack.STATE_UNINITIALIZED) { "소리 재생을 초기화할 수 없습니다." }
            check(activeTrack.write(pcm, 0, pcm.size) == pcm.size) { "소리를 준비하지 못했습니다." }
            if (loop) check(activeTrack.setLoopPoints(0, pcm.size, -1) == AudioTrack.SUCCESS)
            currentCoroutineContext().ensureActive()
            activeTrack.play()
            var lastBeat = -1L
            while (currentCoroutineContext().isActive) {
                val head = activeTrack.playbackHeadPosition.toLong() and 0xffffffffL
                if (!loop && head >= pcm.size) break
                val beat = head / framesPerBeat
                if (beat != lastBeat) {
                    onBeat((beat % 4).toInt(), beatNotes[(beat % totalBeats).toInt()])
                    lastBeat = beat
                }
                delay(10)
            }
        } finally {
            synchronized(lock) {
                if (track === output) track = null
                output?.let { runCatching { it.stop() }; it.release() }
            }
            manager.abandonAudioFocusRequest(focus)
        }
    }
}
