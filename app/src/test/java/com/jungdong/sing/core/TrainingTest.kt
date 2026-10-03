package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TrainingTest {
    @Test fun everyWeekIsExactlyTwentyMinutes() {
        assertEquals(4, Training.weeks.size)
        Training.weeks.forEach { assertEquals(1200, it.steps.sumOf { step -> step.seconds }) }
    }
    @Test fun weekProgressionAndClamping() {
        assertEquals(1, Training.weekForDay(0).number)
        assertEquals(1, Training.weekForDay(7).number)
        assertEquals(2, Training.weekForDay(8).number)
        assertEquals(3, Training.weekForDay(15).number)
        assertEquals(4, Training.weekForDay(22).number)
        assertEquals(4, Training.weekForDay(100).number)
    }
    @Test fun timedStepBoundaries() {
        val week = Training.weeks.first()
        assertEquals(0, Training.stepIndex(119, week))
        assertEquals(1, Training.stepIndex(120, week))
        assertEquals(2, Training.stepIndex(600, week))
        assertEquals(3, Training.stepIndex(1200, week))
    }
    @Test fun questionDirectionAndNoDuplicateNotes() {
        val random = Random(7)
        repeat(200) {
            val q = EarQuestion.next(random)
            assertNotEquals(q.first, q.second)
            assertTrue(q.first in 45..52)
            assertTrue(q.second in 40..57)
            assertTrue(q.answer(q.second > q.first))
            assertFalse(q.answer(q.second < q.first))
        }
    }
    @Test fun fullSongHasFortyEightBeatsAndComfortableRange() {
        assertEquals(48, Songs.littleStar.sumOf { it.beats })
        assertTrue(Songs.littleStar.all { it.midi!! in 48..57 })
        assertEquals(8, Songs.shortMelody.sumOf { it.beats })
    }
}
