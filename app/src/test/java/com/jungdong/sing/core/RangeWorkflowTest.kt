package com.jungdong.sing.core

import org.junit.Assert.*
import org.junit.Test

class RangeWorkflowTest {
    private fun take(state: RangeDiagnosisState, midi: Int): RangeDiagnosisState = RangeWorkflow.measured(
        RangeWorkflow.begin(state), PitchWindow.evaluate(List(30) { Pitch(Music.frequency(midi), 0.99, 0.2) },
            if (state.stage == RangeStage.START) null else state.targetMidi))
    private fun comfortableTwice(state: RangeDiagnosisState, midi: Int) =
        RangeWorkflow.comfortable(take(RangeWorkflow.comfortable(take(state, midi)), midi))
    private fun started() = comfortableTwice(RangeDiagnosisState(stage = RangeStage.START), 48)

    @Test fun stableStartingNoteNeedsTwoUserConfirmations() {
        val first = RangeWorkflow.comfortable(take(RangeDiagnosisState(stage = RangeStage.START), 48))
        assertEquals(RangeStage.START, first.stage)
        assertNull(first.startHz)
        val second = RangeWorkflow.comfortable(take(first, 48))
        assertEquals(RangeStage.LOW, second.stage)
        assertEquals(47, second.targetMidi)
        assertEquals(48, second.lowMidi)
        assertEquals(48, second.highMidi)
    }
    @Test fun twoDifferentStartingNotesMustBeRetaken() {
        val first = RangeWorkflow.comfortable(take(RangeDiagnosisState(stage = RangeStage.START), 48))
        val second = RangeWorkflow.comfortable(take(first, 50))
        assertNull(second.startHz)
        assertTrue(second.confirmations.isEmpty())
    }
    @Test fun onlyRepeatedComfortableLowTargetsAreIncluded() {
        val first = RangeWorkflow.comfortable(take(started(), 47))
        assertEquals(48, first.lowMidi)
        assertEquals(47, first.targetMidi)
        val second = RangeWorkflow.comfortable(take(first, 47))
        assertEquals(47, second.lowMidi)
        assertEquals(46, second.targetMidi)
    }
    @Test fun difficultLowTargetAfterOneConfirmationIsExcluded() {
        val once = RangeWorkflow.comfortable(take(started(), 47))
        val high = RangeWorkflow.difficult(once)
        assertEquals(RangeStage.HIGH, high.stage)
        assertEquals(48, high.lowMidi)
        assertEquals(49, high.targetMidi)
    }
    @Test fun highTargetAlsoNeedsTwoConfirmations() {
        val high = RangeWorkflow.difficult(started())
        val once = RangeWorkflow.comfortable(take(high, 49))
        assertEquals(48, once.highMidi)
        val twice = RangeWorkflow.comfortable(take(once, 49))
        assertEquals(49, twice.highMidi)
        assertEquals(50, twice.targetMidi)
    }
    @Test fun difficultHighTargetCompletesWithLastConfirmedNote() {
        val high = comfortableTwice(RangeWorkflow.difficult(started()), 49)
        val result = RangeWorkflow.difficult(high)
        val profile = result.profile("result", 1)!!
        assertEquals(48, profile.lowMidi)
        assertEquals(49, profile.highMidi)
    }
    @Test fun wrongPitchCannotAdvanceOnComfortClick() {
        val state = take(started(), 60)
        assertEquals(state, RangeWorkflow.comfortable(state))
        assertEquals(48, state.lowMidi)
    }
    @Test fun stoppedMeasurementCannotAcceptLateSamples() {
        val stopped = RangeWorkflow.interrupted(RangeWorkflow.begin(started()))
        assertFalse(stopped.running)
        val late = RangeWorkflow.measured(stopped, RangeMeasurement(hz = Music.frequency(47)))
        assertEquals(stopped, late)
        assertEquals(stopped, RangeWorkflow.comfortable(stopped))
    }
    @Test fun permissionDenialDoesNotCreateOrExpandRange() {
        val denied = RangeWorkflow.interrupted(RangeWorkflow.begin(started()), MeasurementFailure.PERMISSION_DENIED)
        assertFalse(denied.running)
        assertEquals(MeasurementFailure.PERMISSION_DENIED, denied.measurement!!.failure)
        assertNull(denied.profile("denied", 1))
        assertEquals(48, denied.lowMidi)
        assertEquals(denied, RangeWorkflow.comfortable(denied))
    }
    @Test fun emergencyStopKeepsOnlyConfirmedBoundsAndDoesNotAutomaticallyPersist() {
        val low = comfortableTwice(started(), 47)
        val stopped = RangeWorkflow.stop(RangeWorkflow.begin(low))
        assertFalse(stopped.running)
        val profile = stopped.profile("partial", 1)!!
        assertEquals(47, profile.lowMidi)
        assertEquals(48, profile.highMidi)
        assertTrue(profile.partial)
    }
    @Test fun stopBeforeStartingConfirmationHasNoSavableResult() {
        assertNull(RangeWorkflow.stop(RangeDiagnosisState(stage = RangeStage.START)).profile("none", 1))
    }
    @Test fun analysisWithoutComfortDoesNotExpandBounds() {
        val state = take(started(), 47)
        assertEquals(48, state.lowMidi)
        assertEquals(0, state.confirmations.size)
    }
}
