package com.jungdong.sing.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jungdong.sing.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RangeStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun profile(id: String = "first", low: Int = 43, high: Int = 64) =
        VocalRangeProfile(id, 1, Music.frequency(48), low, high)
    private fun store(scope: CoroutineScope, file: File) = RangeStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    @Test fun freshStoreDefaultsToTwentyFiveCentsAndUnseenIntro() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val loaded = store(scope, File(folder.root, "fresh.preferences_pb")).records.first()
            assertNull(loaded.current)
            assertEquals(25, loaded.tolerance)
            assertFalse(loaded.introSeen)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun savedRangeLoadsAfterStoreReopens() = runBlocking {
        val file = File(folder.root, "range.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { store(firstScope, file).save(profile()) }
        finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val loaded = store(secondScope, file).records.first()
            assertEquals(profile(), loaded.current)
            assertTrue(loaded.introSeen)
        } finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun remeasurementAndManualEditAppendHistoryInsteadOfOverwriting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope, File(folder.root, "history.preferences_pb"))
            store.save(profile())
            val second = profile("second", 41, 62).copy(timestamp = 2, previousId = "first")
            store.save(second)
            val manual = second.copy(id = "manual", timestamp = 3, highMidi = 60, source = RangeSource.MANUAL, previousId = "second")
            store.save(manual)
            val loaded = store.records.first()
            assertEquals(listOf(profile(), second, manual), loaded.profiles)
            assertEquals(manual, loaded.current)
            assertTrue(loaded.current!!.recommended.all { it in manual.comfortable })
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun deferredIntroAndTolerancePersistWithoutInventingRange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope, File(folder.root, "settings.preferences_pb"))
            store.seen()
            store.tolerance(50)
            val loaded = store.records.first()
            assertTrue(loaded.introSeen)
            assertEquals(50, loaded.tolerance)
            assertNull(loaded.current)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
