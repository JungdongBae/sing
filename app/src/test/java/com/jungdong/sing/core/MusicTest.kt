package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    @Test fun noteNamesAndConcertPitch() {
        assertEquals("C3", Music.name(48))
        assertEquals("E2", Music.name(40))
        assertEquals("A4", Music.name(69))
        assertEquals(440.0, Music.frequency(69), 0.001)
        assertEquals(130.8128, Music.frequency(48), 0.001)
    }
    @Test fun centsAlwaysUsesSelectedTargetInsteadOfNearestDetectedNote() {
        val d3 = Music.frequency(50)
        assertEquals(200.0, Music.cents(d3, 48), 0.001)
        assertEquals(-200.0, Music.cents(Music.frequency(48), 50), 0.001)
        assertEquals(0.0, Music.cents(d3, 50), 0.001)
    }
    @Test fun realOctaveIsNotFoldedIntoZeroError() {
        assertEquals(1200.0, Music.cents(Music.frequency(60), 48), 0.001)
        assertEquals(-1200.0, Music.cents(Music.frequency(36), 48), 0.001)
    }
    @Test fun noteFrequencyRoundTrip() { (40..80).forEach { assertEquals(it, Music.midi(Music.frequency(it))) } }
    @Test fun guidanceHasCorrectDirectionAndTolerance() {
        assertTrue(Music.guidance(-26.0).contains("높게"))
        assertTrue(Music.guidance(26.0).contains("낮게"))
        assertTrue(Music.guidance(25.0).contains("맞아요"))
        assertTrue(Music.guidance(-25.0).contains("맞아요"))
    }
    @Test fun rhythmFeedbackIsSignedAroundNearestBeat() {
        assertEquals(1000.0, Rhythm.beatDurationMs(60), 0.001)
        assertEquals(500.0, Rhythm.beatDurationMs(120), 0.001)
        assertEquals(0.0, Rhythm.tapErrorMs(1000.0, 60), 0.001)
        assertEquals(-50.0, Rhythm.tapErrorMs(950.0, 60), 0.001)
        assertEquals(50.0, Rhythm.tapErrorMs(1050.0, 60), 0.001)
    }
}
