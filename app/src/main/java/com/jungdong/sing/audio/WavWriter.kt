package com.jungdong.sing.audio

import java.io.File
import java.io.RandomAccessFile

/** Stream into an app-private WAV. The header is finalized even for a user-stopped take. */
class WavWriter(private val file: File, val sampleRate: Int) : AutoCloseable {
    private val output: RandomAccessFile
    private var samples = 0L
    private var closed = false
    init { require(sampleRate in listOf(22050, 44100, 48000)); output = RandomAccessFile(file, "rw"); output.setLength(0); output.write(ByteArray(44)) }
    val durationMs get() = samples * 1000 / sampleRate
    fun write(pcm: ShortArray) {
        check(!closed)
        val data = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, v -> data[i * 2] = v.toByte(); data[i * 2 + 1] = (v.toInt() shr 8).toByte() }
        output.write(data); samples += pcm.size
    }
    private fun le(value: Long, count: Int) { repeat(count) { output.write(((value shr (it * 8)) and 255).toInt()) } }
    override fun close() {
        if (closed) return
        try {
            output.seek(0); output.writeBytes("RIFF"); le(36 + samples * 2, 4); output.writeBytes("WAVEfmt "); le(16, 4)
            le(1, 2); le(1, 2); le(sampleRate.toLong(), 4); le(sampleRate * 2L, 4); le(2, 2); le(16, 2)
            output.writeBytes("data"); le(samples * 2, 4); output.fd.sync()
        } finally { closed = true; output.close() }
    }
}
