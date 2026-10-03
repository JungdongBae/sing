package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class PitchDetectorTest {
    private val detector = PitchDetector()
    private fun signal(hz: Double, fundamental: Double = 0.25, harmonic: Double = 0.0,
                       noise: Double = 0.0, dc: Double = 0.0): ShortArray {
        val random = Random(123)
        return ShortArray(4096) { i ->
            val phase = 2 * PI * hz * i / 22050
            ((fundamental * sin(phase) + harmonic * sin(2 * phase) +
                noise * random.nextDouble(-1.0, 1.0) + dc).coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }
    @Test fun detectsLowMaleRange() {
        listOf(40, 43, 45, 48, 50, 52, 57, 60).forEach { midi ->
            val pitch = detector.detect(signal(Music.frequency(midi)))
            assertNotNull("midi $midi", pitch)
            assertEquals("midi $midi", 0.0, Music.cents(pitch!!.hz, midi), 8.0)
        }
    }
    @Test fun preservesFundamentalWithStrongerSecondHarmonic() {
        val pitch = detector.detect(signal(Music.frequency(48), fundamental = 0.15, harmonic = 0.4))!!
        assertEquals(0.0, Music.cents(pitch.hz, 48), 8.0)
    }
    @Test fun toleratesModerateNoiseAndDc() {
        val pitch = detector.detect(signal(Music.frequency(48), noise = 0.03, dc = 0.1))!!
        assertEquals(0.0, Music.cents(pitch.hz, 48), 12.0)
    }
    @Test fun rejectsSilence() { assertNull(detector.detect(ShortArray(4096))) }
    @Test fun rejectsQuietInput() { assertNull(detector.detect(signal(130.81, fundamental = 0.002))) }
    @Test fun rejectsUnpitchedNoise() {
        val random = Random(4)
        assertNull(detector.detect(ShortArray(4096) { random.nextInt(-12000, 12000).toShort() }))
    }
    @Test fun rejectsDcOnly() { assertNull(detector.detect(ShortArray(4096) { 8000 })) }
    @Test fun rejectsShortFrame() { assertNull(detector.detect(ShortArray(128))) }
    @Test fun supports44100SampleRate() {
        val pitch = PitchDetector(44100).detect(ShortArray(8192) { i ->
            (0.3 * sin(2 * PI * Music.frequency(40) * i / 44100) * 32767).toInt().toShort()
        })!!
        assertEquals(0.0, Music.cents(pitch.hz, 40), 8.0)
    }
    @Test fun smootherSuppressesOneOctaveSpikeButAllowsRealChange() {
        val smoother = PitchSmoother()
        fun accept(hz: Double) = smoother.accept(Pitch(hz, 0.99, 0.2))!!.hz
        accept(130.0); accept(130.0)
        assertEquals(130.0, accept(260.0), 0.0)
        assertEquals(260.0, accept(260.0), 0.0)
    }
    @Test fun smootherClearsAfterSilence() {
        val smoother = PitchSmoother()
        smoother.accept(Pitch(130.0, 0.99, 0.2))
        repeat(3) { assertNull(smoother.accept(null)) }
        assertEquals(200.0, smoother.accept(Pitch(200.0, 0.99, 0.2))!!.hz, 0.0)
    }
}
