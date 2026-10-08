package com.jungdong.sing.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun partialTakeHasPlayablePcmHeaderAndCorrectDuration() {
        val file=File(folder.root,"partial.wav")
        val writer=WavWriter(file,22050)
        writer.write(ShortArray(22050) { if (it % 2 == 0) 1234 else -1234 }); writer.close(); writer.close()
        val bytes=file.readBytes(); val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",String(bytes.copyOfRange(0,4))); assertEquals("WAVE",String(bytes.copyOfRange(8,12)))
        assertEquals(22050,b.getInt(24)); assertEquals(44100,b.getInt(40)); assertEquals(44144,bytes.size)
        assertEquals(1234,b.getShort(44).toInt()); assertEquals(-1234,b.getShort(46).toInt()); assertEquals(1000L,writer.durationMs)
    }
    @Test fun noMicrophoneSamplesMeansNoRecordedContent() {
        val file=File(folder.root,"empty.wav"); val writer=WavWriter(file,48000); writer.close()
        assertEquals(0L,writer.durationMs); assertEquals(44L,file.length())
    }
}
