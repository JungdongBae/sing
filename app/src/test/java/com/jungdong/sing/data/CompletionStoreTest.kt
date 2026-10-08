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

class CompletionStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store(scope: CoroutineScope, file: File) = CompletionStore(PreferenceDataStoreFactory.create(scope=scope,produceFile={file}))
    @Test fun progressAndAssessmentSurviveReopeningActualDataStore() = runBlocking {
        val file = File(folder.root,"completion.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val assessment = VocalSelfAssessment(4,3,5,4,4)
        val recording = RecordingSession("r1",1,"r1.wav",3000,null,null,"voice",null,"허밍",null,true,null,assessment)
        try {
            val s = store(firstScope,file)
            s.practice(PracticeSession("2026-10-08",5,1,600))
            s.recording(recording)
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val nextScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val r = store(nextScope,file).records.first()
            assertEquals(600,r.current("2026-10-08").seconds)
            assertEquals(recording,r.recordings.single()); assertEquals(assessment,r.recordings.single().selfAssessment)
        } finally { nextScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun retakesAreSeparateFromPriorCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val s=store(scope,File(folder.root,"retake.preferences_pb"))
            s.practice(PracticeSession("2026-10-08",5,1,1200,true))
            s.update { it.copy(attempt=2) }
            val r=s.records.first(); assertEquals(0,r.current("2026-10-08").seconds); assertFalse(r.current("2026-10-08").mastered)
            assertEquals(1,r.sessions.size)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun deletionRemovesTrialAndRecordingTogether() = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val s=store(scope,File(folder.root,"delete.preferences_pb"))
            s.recording(RecordingSession("r1",1,"r1.wav",1000,"song","test","voice","s0","구간",0,false,null))
            s.update { it.copy(trials=listOf(KeyTrial("song","voice","s0",0,.9,.9,5,"r1")),keyFinalized=true) }
            s.deleteRecording("r1"); val r=s.records.first()
            assertTrue(r.recordings.isEmpty()); assertTrue(r.trials.isEmpty()); assertFalse(r.keyFinalized)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun codecRejectsMalformedDataInsteadOfResettingHistory() {
        assertThrows(Exception::class.java) { CompletionCodec.decode("not json") }
        assertThrows(Exception::class.java) { CompletionCodec.decode("{\"version\":88}") }
    }
}
