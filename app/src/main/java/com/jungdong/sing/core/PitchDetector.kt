package com.jungdong.sing.core

import kotlin.math.*

data class Pitch(val hz: Double, val confidence: Double, val rms: Double)

/** YIN first-dip selection preserves the fundamental instead of selecting the loudest harmonic.
 * No target-dependent octave folding: a sung octave remains a ±1200-cent error. */
class PitchDetector(private val sampleRate: Int = 22050) {
    fun detect(samples: ShortArray): Pitch? {
        if (samples.size < 2048) return null
        val mean = samples.sumOf { it.toDouble() } / samples.size
        val x = DoubleArray(samples.size) { (samples[it] - mean) / 32768.0 }
        val rms = sqrt(x.sumOf { it * it } / x.size)
        if (rms < 0.008) return null
        val minLag = sampleRate / 800
        val maxLag = min(sampleRate / 60, x.size / 2 - 1)
        val window = x.size - maxLag
        val diff = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until window) { val d = x[i] - x[i + lag]; sum += d * d }
            diff[lag] = sum
        }
        val normalized = DoubleArray(maxLag + 1) { 1.0 }
        var running = 0.0
        for (lag in 1..maxLag) {
            running += diff[lag]
            normalized[lag] = if (running > 0) diff[lag] * lag / running else 1.0
        }
        var lag = minLag
        while (lag < maxLag) {
            if (normalized[lag] < 0.15) {
                while (lag + 1 < maxLag && normalized[lag + 1] < normalized[lag]) lag++
                val left = normalized[lag - 1]
                val mid = normalized[lag]
                val right = normalized[lag + 1]
                val denominator = 2 * (2 * mid - left - right)
                val shift = if (abs(denominator) > 1e-12) (right - left) / denominator else 0.0
                val hz = sampleRate / (lag + shift.coerceIn(-1.0, 1.0))
                return if (hz in 60.0..800.0) Pitch(hz, 1 - mid, rms) else null
            }
            lag++
        }
        return null
    }
}

/** Median suppresses isolated octave spikes; sustained real register changes still pass. */
class PitchSmoother {
    private val recent = ArrayDeque<Double>()
    private var silentFrames = 0
    fun reset() { recent.clear(); silentFrames = 0 }
    fun accept(pitch: Pitch?): Pitch? {
        if (pitch == null) {
            silentFrames++
            if (silentFrames >= 3) recent.clear()
            return null
        }
        silentFrames = 0
        recent.addLast(pitch.hz)
        if (recent.size > 3) recent.removeFirst()
        return pitch.copy(hz = recent.sorted()[recent.size / 2])
    }
}
