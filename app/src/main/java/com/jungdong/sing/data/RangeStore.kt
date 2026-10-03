package com.jungdong.sing.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jungdong.sing.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.rangeDataStore by preferencesDataStore(name = "vocal_range")
data class RangeRecords(val profiles: List<VocalRangeProfile> = emptyList(), val tolerance: Int = 25, val introSeen: Boolean = false) {
    val current get() = profiles.lastOrNull()
}
class RangeStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.rangeDataStore)
    private val history = stringPreferencesKey("history_v1")
    private val tolerance = intPreferencesKey("tolerance")
    private val introSeen = booleanPreferencesKey("intro_seen")
    val records = dataStore.data.map { prefs -> RangeRecords(
        RangeHistoryCodec.decode(prefs[history] ?: ""), (prefs[tolerance] ?: 25).coerceIn(10, 100), prefs[introSeen] ?: false) }.flowOn(Dispatchers.IO)
    suspend fun save(profile: VocalRangeProfile) {
        dataStore.edit { prefs ->
            val profiles = RangeHistoryCodec.decode(prefs[history] ?: "")
            require(profiles.none { it.id == profile.id })
            prefs[history] = RangeHistoryCodec.encode(profiles + profile)
            prefs[introSeen] = true
        }
    }
    suspend fun seen() { dataStore.edit { it[introSeen] = true } }
    suspend fun tolerance(value: Int) {
        require(value in 10..100)
        dataStore.edit { it[tolerance] = value }
    }
}
