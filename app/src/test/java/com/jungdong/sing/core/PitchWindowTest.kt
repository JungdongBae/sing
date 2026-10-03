package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

class PitchWindowTest {
    private fun tone(midi: Int) = Pitch(Music.frequency(midi), 0.99, 0.2)
    @Test fun normalInputUsesStableMedianAndRealSignal() {
        val detector = PitchDetector()
        val pitch = detector.detect(ShortArray(4096) { i -> (0.3 * sin(2 * PI * Music.frequency(48) * i / 22050) * 32767).toInt().toShort() })!!
        val result = PitchWindow.evaluate(List(30) { pitch }, 48)
        assertTrue(result.accepted)
        assertEquals(130.81, result.hz!!, 0.5)
    }
    @Test fun silenceAndEmptyInputFail() {
        assertEquals(MeasurementFailure.NO_VOICE, PitchWindow.evaluate(List(30) { null }).failure)
        assertFalse(PitchWindow.evaluate(emptyList()).accepted)
    }
    @Test fun noiseConfidenceAndQuietInputCannotCountAsVoice() {
        assertFalse(PitchWindow.evaluate(List(30) { tone(48).copy(confidence = 0.5) }).accepted)
        assertFalse(PitchWindow.evaluate(List(30) { tone(48).copy(rms = 0.001) }).accepted)
        assertFalse(PitchWindow.evaluate(List(30) { tone(48).copy(hz = Double.NaN) }).accepted)
    }
    @Test fun insufficientVoicingFailsEvenWithSomeGoodFrames() {
        assertFalse(PitchWindow.evaluate(List(30) { if (it < 19) tone(48) else null }).accepted)
        assertFalse(PitchWindow.evaluate(List(10) { tone(48) }).accepted)
    }
    @Test fun unstableNotesFail() {
        assertEquals(MeasurementFailure.UNSTABLE, PitchWindow.evaluate(List(30) { tone(if (it % 2 == 0) 48 else 50) }).failure)
    }
    @Test fun persistentOctaveMixtureIsNotFoldedIntoSuccess() {
        assertEquals(MeasurementFailure.UNSTABLE, PitchWindow.evaluate(List(30) { tone(if (it < 20) 48 else 60) }, 48).failure)
    }
    @Test fun smallNumberOfOctaveOutliersDoesNotMoveMedian() {
        val result = PitchWindow.evaluate(List(30) { tone(if (it < 28) 48 else 60) }, 48)
        assertTrue(result.accepted)
        assertEquals(Music.frequency(48), result.hz!!, 0.01)
    }
    @Test fun higherAndLowerInputsAreJudgedAgainstSelectedTarget() {
        val high = PitchWindow.evaluate(List(30) { tone(50) }, 48)
        val low = PitchWindow.evaluate(List(30) { tone(46) }, 48)
        assertEquals(MeasurementFailure.OFF_TARGET, high.failure)
        assertEquals(200.0, high.targetCents!!, 0.01)
        assertEquals(-200.0, low.targetCents!!, 0.01)
    }
    @Test fun toleranceIsAdjustableAndOctaveStillFails() {
        val offset = Music.frequency(48) * 2.0.pow(40.0 / 1200)
        val frames = List(30) { Pitch(offset, 0.99, 0.2) }
        assertFalse(PitchWindow.evaluate(frames, 48, 25).accepted)
        assertTrue(PitchWindow.evaluate(frames, 48, 50).accepted)
        assertFalse(PitchWindow.evaluate(List(30) { tone(60) }, 48, 100).accepted)
    }
    @Test fun freeStartHasNoTargetError() {
        val result = PitchWindow.evaluate(List(30) { tone(43) })
        assertTrue(result.accepted)
        assertNull(result.targetCents)
    }
    @Test fun unsupportedStartFailsWithoutExtendingTheRange() {
        assertEquals(MeasurementFailure.OUT_OF_RANGE, PitchWindow.evaluate(List(30) { tone(79) }).failure)
    }
}
