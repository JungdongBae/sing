package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test

class CompletionTest {
    private fun profile(low: Int = 43, high: Int = 64) = VocalRangeProfile("voice", 1, Music.frequency(48), low, high)
    private fun song(notes: List<MelodyNote> = listOf(MelodyNote(48,0,1000,1), MelodyNote(57,1000,1000,1)), verified: Boolean = true) =
        SongMetadata("song", "test", "synthetic.mid", "unit test only", "C", notes, SongMetadata.sections(notes), verified)
    private fun pitch(midi: Int) = Pitch(Music.frequency(midi), .99, .1)
    @Test fun normalRecommendationProvidesAtMostThreeEntirelySafeCandidates() {
        val s = song(); val p = profile(); val cs = KeyRecommender.recommend(s, p)
        assertEquals(3, cs.size)
        cs.forEach { c -> assertTrue(s.notes.all { it.midi + c.semitones in p.comfortable }) }
    }
    @Test fun noCandidateWhenMelodyWiderThanComfortableRange() {
        assertTrue(KeyRecommender.recommend(song(), profile(48,52)).isEmpty())
    }
    @Test fun missingSongOrProfileNeverFabricatesAKey() {
        assertTrue(KeyRecommender.recommend(null,profile()).isEmpty())
        assertTrue(KeyRecommender.recommend(song(),null).isEmpty())
    }
    @Test fun unverifiedDataCannotRecommend() { assertTrue(KeyRecommender.recommend(song(verified=false),profile()).isEmpty()) }
    @Test fun longHighNotesAndLongLowNotesChangeTheBestKey() {
        val high = song(listOf(MelodyNote(48,0,500,1),MelodyNote(57,500,10000,1)))
        val low = song(listOf(MelodyNote(48,0,10000,1),MelodyNote(57,10000,500,1)))
        assertTrue(KeyRecommender.recommend(high,profile()).first().semitones < KeyRecommender.recommend(low,profile()).first().semitones)
    }
    @Test fun allSupportedComfortableRangesKeepRecommendationsInsideBounds() {
        for (low in VocalRangeProfile.SUPPORTED) for (high in low..76) {
            val p = profile(low,high)
            KeyRecommender.recommend(song(),p).forEach { c -> assertTrue(c.low >= low && c.high <= high) }
        }
    }
    @Test fun finalKeyRequiresBothValidPitchAndComfortAndCurrentProfile() {
        val candidates = KeyRecommender.recommend(song(),profile()); val shift = candidates.first().semitones
        val good = KeyTrial("song","voice","s0",shift,.9,.95,4,"r1")
        assertEquals(shift, KeyRecommender.finalize(candidates,listOf(good),"song","voice"))
        assertNull(KeyRecommender.finalize(candidates,listOf(good.copy(comfort=3)),"song","voice"))
        assertNull(KeyRecommender.finalize(candidates,listOf(good.copy(pitchAccuracy=null)),"song","voice"))
        assertNull(KeyRecommender.finalize(candidates,listOf(good.copy(coverage=.5)),"song","voice"))
        assertNull(KeyRecommender.finalize(candidates,listOf(good),"song","differentVoice"))
    }
    @Test fun everyExtendedDayRemainsTwentyMinutes() {
        for (w in 5..8) for (d in 1..5) assertEquals(1200,CompletionTraining.plan(w,d).phases.sumOf { it.seconds })
        assertEquals(6 to 1, CompletionTraining.next(5,5)); assertNull(CompletionTraining.next(8,5))
    }
    @Test fun completionDoesNotImplyMastery() {
        val s = PracticeSession("2026-10-08",5,1,1200)
        assertTrue(s.completed); assertFalse(s.mastered)
    }
    @Test fun framesUseSelectedTargetInsteadOfNearestDetectedNote() {
        val notes = listOf(MelodyNote(48,0,1000,1))
        val up = MelodyAnalyzer.analyze(true,notes,0,(0L..950L step 50).map { PerformanceFrame(it,pitch(50)) })
        val down = MelodyAnalyzer.analyze(true,notes,0,(0L..950L step 50).map { PerformanceFrame(it,pitch(46)) })
        assertEquals(0.0,up.pitchAccuracy!!,.001); assertEquals(200.0,up.notes.first().meanAbsCents!!,.001)
        assertEquals(0.0,down.pitchAccuracy!!,.001)
    }
    @Test fun pureOctaveErrorIsNotAccepted() {
        val r = MelodyAnalyzer.analyze(true,listOf(MelodyNote(48,0,1000,1)),0,(0L..950L step 50).map { PerformanceFrame(it,pitch(60)) })
        assertEquals(1200.0,r.notes.first().meanAbsCents!!,.01); assertEquals(0.0,r.pitchAccuracy!!,.01)
    }
    @Test fun silenceNoiseOrUnverifiedMelodyHasNoScore() {
        val ns = listOf(MelodyNote(48,0,1000,1)); val frames = (0L..950L step 50).map { PerformanceFrame(it,null) }
        assertNull(MelodyAnalyzer.analyze(true,ns,0,frames).pitchAccuracy)
        assertNull(MelodyAnalyzer.analyze(true,ns,0,frames.map { it.copy(pitch=pitch(48).copy(confidence=.2)) }).pitchAccuracy)
        assertNull(MelodyAnalyzer.analyze(false,ns,0,frames.map { it.copy(pitch=pitch(48)) }).pitchAccuracy)
    }
    @Test fun correctSustainedPitchHasAReportButNoUncalibratedRhythmScore() {
        val r = MelodyAnalyzer.analyze(true,listOf(MelodyNote(48,0,1000,1)),0,(0L..950L step 50).map { PerformanceFrame(it,pitch(48)) })
        assertEquals(1.0,r.pitchAccuracy!!,.001); assertNull(r.rhythmAccuracy)
    }
    @Test fun correctionAlignsDelayedOnsetsAndPitch() {
        val ns = listOf(48,50,52,53).mapIndexed { i,n -> MelodyNote(n,i*1000L,800, i+1) }
        val frames = (0L..4000L step 50).map { t ->
            val note = MelodyAnalyzer.target(ns,t-100); PerformanceFrame(t,note?.let { pitch(it.midi) })
        }
        val r = MelodyAnalyzer.analyze(true,ns,0,frames,calibrationMs=100)
        assertEquals(1.0,r.pitchAccuracy!!,.001); assertEquals(1.0,r.rhythmAccuracy!!,.001)
        assertTrue(r.notes.all { it.onsetErrorMs == 0L })
    }
    @Test fun partialTakeCannotEarnAFullSongScore() {
        val ns = listOf(MelodyNote(48,0,1000,1),MelodyNote(50,1000,1000,1))
        val r = MelodyAnalyzer.analyze(true,ns,0,(0L..450L step 50).map { PerformanceFrame(it,pitch(48)) })
        assertNull(r.pitchAccuracy); assertTrue(r.coverage < .7)
    }
    @Test fun inconsistentCalibrationIsRejected() {
        assertEquals(80,LatencyCalibration.estimate(listOf(1000,2000,3000,4000),listOf(1080,2080,3080,4080)))
        assertNull(LatencyCalibration.estimate(listOf(1000,2000,3000,4000),listOf(1080,2400,3080,4080)))
        assertNull(LatencyCalibration.estimate(listOf(1000,2000,3000,4000),emptyList()))
    }
    @Test fun existingRangeHistoryMigratesWithoutInventingConfidence() {
        val legacy = "1|voice|1|130.8127826502993|43|64|MEASURED||false"
        val p = RangeHistoryCodec.decode(legacy).single(); assertNull(p.startConfidence)
        assertEquals(p,RangeHistoryCodec.decode(RangeHistoryCodec.encode(listOf(p))).single())
    }
}
