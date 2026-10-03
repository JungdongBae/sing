package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PersonalizationTest {
    private fun profile(low: Int, high: Int) = VocalRangeProfile("test", 1, Music.frequency((low + high) / 2), low, high)
    @Test fun differentRangesReceiveDifferentRecommendations() {
        val low = profile(36, 48)
        val high = profile(55, 72)
        assertNotEquals(low.recommended, high.recommended)
        val example = profile(43, 64)
        assertTrue(example.recommended.all { it in 43..64 })
        assertTrue(example.recommended.first > 43 && example.recommended.last < 64)
    }
    @Test fun allSupportedRangesKeepEveryPracticeNoteWithinComfortableBounds() {
        for (low in VocalRangeProfile.SUPPORTED) for (high in low..VocalRangeProfile.SUPPORTED.last) {
            val p = profile(low, high)
            assertTrue(p.recommended.all { it in p.comfortable })
            assertTrue(p.center in p.comfortable)
            assertTrue(p.practiceNotes.all { it in p.comfortable })
            assertTrue(p.melody().all { it.midi!! in p.comfortable })
            assertTrue(p.practiceNotes.size in 1..3)
        }
    }
    @Test fun fullSongPreservesIntervalsAtEverySafeKey() {
        val p = profile(43, 64)
        p.songRoots!!.forEach { root ->
            val song = p.song(root)!!
            assertTrue(song.all { it.midi!! in p.comfortable })
            assertEquals(48, song.sumOf { it.beats })
            song.zip(Songs.littleStar).forEach { (actual, original) -> assertEquals(root - 48, actual.midi!! - original.midi!!) }
        }
    }
    @Test fun narrowRangesDoNotForceSongNotesOutsideBounds() {
        assertNull(profile(48, 52).songRoots)
        assertNull(profile(48, 52).song())
        assertNull(profile(48, 60).song(47))
    }
    @Test fun singleNoteRangeSupportsSafeSingleNotePractice() {
        val p = profile(45, 45)
        assertEquals(45..45, p.recommended)
        assertEquals(listOf(45), p.practiceNotes)
        assertTrue(p.melody().all { it.midi == 45 })
        assertNull(EarQuestion.nextInRange(p.recommended))
    }
    @Test fun listeningQuestionsStayInsideRecommendation() {
        val random = Random(3)
        listOf(profile(36, 44), profile(43, 64), profile(60, 76), profile(50, 51)).forEach { p ->
            repeat(100) {
                val q = EarQuestion.nextInRange(p.recommended, random)!!
                assertTrue(q.first in p.recommended && q.second in p.recommended)
                assertNotEquals(q.first, q.second)
            }
        }
    }
    @Test fun allWeeksRemainTwentyMinutesAndUsePersonalizedInstructions() {
        val p = profile(43, 64)
        for (number in 1..4) assertEquals(1200, Training.personalizedWeek(number, p).steps.sumOf { it.seconds })
        assertTrue(Training.personalizedWeek(1, p).steps[2].instruction.contains(Music.name(p.center)))
        assertTrue(Training.personalizedWeek(2, p, 50).steps[2].instruction.contains("±50"))
        assertTrue(Training.personalizedWeek(4, profile(48, 52)).steps[2].instruction.contains("짧은 패턴"))
    }
    @Test fun profileCodecRoundTripsHistoryIncludingManualAndPartial() {
        val first = profile(43, 64).copy(id = "first")
        val second = first.copy(id = "second", timestamp = 2, lowMidi = 45, highMidi = 62,
            source = RangeSource.MANUAL, previousId = first.id, partial = true)
        assertEquals(listOf(first, second), RangeHistoryCodec.decode(RangeHistoryCodec.encode(listOf(first, second))))
    }
    @Test fun invalidBoundsAndMalformedHistoryAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { profile(60, 40) }
        assertThrows(IllegalArgumentException::class.java) { profile(30, 60) }
        assertThrows(IllegalArgumentException::class.java) { RangeHistoryCodec.decode("broken") }
        assertThrows(IllegalArgumentException::class.java) { RangeHistoryCodec.decode(RangeHistoryCodec.encode(listOf(profile(40, 50), profile(40, 50)))) }
    }
}
