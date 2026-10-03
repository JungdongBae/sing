package com.jungdong.sing.core

import kotlin.math.*
import kotlin.random.Random

object Music {
    val targets = (40..60).toList() // E2–C4, default C3 (48)
    private val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    fun frequency(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
    fun midi(hz: Double): Int = (69 + 12 * log2(hz / 440)).roundToInt()
    fun name(midi: Int): String = "${names[Math.floorMod(midi, 12)]}${midi / 12 - 1}"
    fun cents(hz: Double, targetMidi: Int): Double = 1200 * log2(hz / frequency(targetMidi))
    fun guidance(cents: Double, tolerance: Int = 25): String = when {
        abs(cents) <= tolerance -> "잘 맞췄어요! 목표음과 맞아요"
        cents < 0 -> "조금 더 높게 불러 보세요 ↑"
        else -> "조금 더 낮게 불러 보세요 ↓"
    }
}

data class EarQuestion(val first: Int, val second: Int) {
    val isHigher get() = second > first
    fun answer(higher: Boolean): Boolean = higher == isHigher
    companion object {
        fun next(random: Random = Random.Default): EarQuestion {
            val first = random.nextInt(45, 53)
            val interval = random.nextInt(1, 6) * if (random.nextBoolean()) 1 else -1
            return EarQuestion(first, first + interval)
        }
        fun nextInRange(range: IntRange, random: Random = Random.Default): EarQuestion? {
            if (range.last <= range.first) return null
            val first = random.nextInt(range.first, range.last + 1)
            val candidates = (maxOf(range.first, first - 5)..minOf(range.last, first + 5)).filter { it != first }
            return EarQuestion(first, candidates.random(random))
        }
    }
}

data class NoteEvent(val midi: Int?, val beats: Int = 1)
object Songs {
    // Public-domain melody, transposed down: Twinkle, Twinkle, Little Star.
    val littleStar = listOf(
        48 to 1, 48 to 1, 55 to 1, 55 to 1, 57 to 1, 57 to 1, 55 to 2,
        53 to 1, 53 to 1, 52 to 1, 52 to 1, 50 to 1, 50 to 1, 48 to 2,
        55 to 1, 55 to 1, 53 to 1, 53 to 1, 52 to 1, 52 to 1, 50 to 2,
        55 to 1, 55 to 1, 53 to 1, 53 to 1, 52 to 1, 52 to 1, 50 to 2,
        48 to 1, 48 to 1, 55 to 1, 55 to 1, 57 to 1, 57 to 1, 55 to 2,
        53 to 1, 53 to 1, 52 to 1, 52 to 1, 50 to 1, 50 to 1, 48 to 2,
    ).map { NoteEvent(it.first, it.second) }
    val shortMelody = listOf(48, 50, 52, 50, 48, 52, 50, 48).map { NoteEvent(it) }
}

object Rhythm {
    fun beatDurationMs(bpm: Int): Double = 60_000.0 / bpm.coerceIn(50, 120)
    fun tapErrorMs(elapsedMs: Double, bpm: Int): Double {
        val beat = beatDurationMs(bpm)
        return elapsedMs - (elapsedMs / beat).roundToInt() * beat
    }
}
