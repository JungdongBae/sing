package com.jungdong.sing.data

import android.content.Context
import java.time.LocalDate

/** Only counters/dates are persisted. Audio is never recorded to a file. */
class PracticeStore(context: Context) {
    private val prefs = context.getSharedPreferences("practice", Context.MODE_PRIVATE)
    fun target() = prefs.getInt("target", 48).coerceIn(40, 60)
    fun setTarget(midi: Int) { prefs.edit().putInt("target", midi).apply() }
    fun bpm() = prefs.getInt("bpm", 60).coerceIn(50, 120)
    fun setBpm(bpm: Int) { prefs.edit().putInt("bpm", bpm).apply() }
    fun seconds(date: LocalDate): Int = prefs.getInt("seconds:$date", 0).coerceIn(0, 1200)
    fun save(date: LocalDate, seconds: Int) { prefs.edit().putInt("seconds:$date", seconds.coerceIn(0, 1200)).apply() }
    fun history(): List<Pair<LocalDate, Int>> = prefs.all.keys.filter { it.startsWith("seconds:") }
        .mapNotNull { key -> runCatching { LocalDate.parse(key.substringAfter(':')) }.getOrNull()?.let { it to seconds(it) } }
        .filter { it.second > 0 }.sortedByDescending { it.first }
    fun completedDays() = history().count { it.second >= 1200 }
}
