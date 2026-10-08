package com.jungdong.sing.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class SongImportTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun chunk(name: String, data: ByteArray): ByteArray = name.toByteArray() + bytes(data.size ushr 24,data.size ushr 16,data.size ushr 8,data.size) + data
    private fun midi(): ByteArray {
        val tempo = bytes(0,255,81,3,7,161,32, 131,96,255,81,3,15,66,64, 0,255,47,0)
        val notes = bytes(0,144,48,80, 131,96,48,0, 0,144,50,80, 131,96,128,50,0, 0,255,47,0)
        return chunk("MThd",bytes(0,1,0,2,1,224)) + chunk("MTrk",tempo) + chunk("MTrk",notes)
    }
    private fun xml(extra: String = "", notes: String = "<note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration></note><note><pitch><step>D</step><octave>3</octave></pitch><duration>1</duration></note>") =
        """<?xml version="1.0" encoding="UTF-8"?><score-partwise><part-list><score-part id="P1"><part-name>Voice</part-name></score-part></part-list><part id="P1"><measure number="1"><attributes><divisions>1</divisions><time><beats>2</beats><beat-type>4</beat-type></time><key><fifths>0</fifths></key></attributes><direction><sound tempo="60"/></direction>$notes$extra</measure></part></score-partwise>""".toByteArray()
    @Test fun midiRunningStatusNoteOffAndTempoChangesPreserveTiming() {
        val m = SongImporter.parse(midi()).melodies.single()
        assertEquals(listOf(48,50),m.notes.map { it.midi })
        assertEquals(500L,m.notes[0].durationMs); assertEquals(500L,m.notes[1].startMs); assertEquals(1000L,m.notes[1].durationMs)
        assertNull(m.referenceKey)
    }
    @Test fun musicXmlParsesPitchTempoBarAndKey() {
        val m = SongImporter.parse(xml()).melodies.single()
        assertEquals("C",m.referenceKey); assertEquals(listOf(48,50),m.notes.map { it.midi })
        assertEquals(1000L,m.notes[0].durationMs); assertEquals(1000L,m.notes[1].startMs)
    }
    @Test fun musicXmlRestsArePreserved() {
        val notes = "<note><rest/><duration>1</duration></note><note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration></note>"
        assertEquals(1000L,SongImporter.parse(xml(notes=notes)).melodies.single().notes.single().startMs)
    }
    @Test fun tiesAreMergedInsteadOfScoredAsTwoAttacks() {
        val notes = "<note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration><tie type=\"start\"/></note><note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration><tie type=\"stop\"/></note>"
        val ns = SongImporter.parse(xml(notes=notes)).melodies.single().notes
        assertEquals(1,ns.size); assertEquals(2000L,ns.single().durationMs)
    }
    @Test fun polyphonicChordIsNotSilentlyUsedAsMelody() {
        val notes = "<note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration></note><note><chord/><pitch><step>E</step><octave>3</octave></pitch><duration>1</duration></note>"
        assertThrows(IllegalArgumentException::class.java) { SongImporter.parse(xml(notes=notes)) }
    }
    @Test fun unfoldedRepeatsAreRequired() {
        assertThrows(IllegalArgumentException::class.java) { SongImporter.parse(xml("<barline><repeat direction=\"backward\"/></barline>")) }
    }
    @Test fun externalEntitiesAndOversizeInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SongImporter.parse("<!DOCTYPE score-partwise SYSTEM 'https://example.com/a'><score-partwise/>".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SongImporter.parse(ByteArray(SongImporter.MAX_BYTES+1)) }
    }
    @Test fun tempoOnOneMusicXmlPartAppliesToAllParts() {
        val original = String(xml())
        val added = "<part id=\"P2\"><measure number=\"1\"><attributes><time><beats>2</beats><beat-type>4</beat-type></time></attributes><note><pitch><step>E</step><octave>3</octave></pitch><duration>1</duration></note><note><pitch><step>F</step><octave>3</octave></pitch><duration>1</duration></note></measure></part>"
        val score = original.replace("</score-partwise>",added+"</score-partwise>")
        val melodies = SongImporter.parse(score.toByteArray()).melodies
        assertEquals(2,melodies.size); assertTrue(melodies.all { it.notes.first().durationMs == 1000L })
    }
    @Test fun soundRepeatJumpWithoutTempoIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SongImporter.parse(xml("<direction><sound dacapo=\"yes\"/></direction>")) }
    }
    @Test fun truncatedMidiIsRejected() { assertThrows(IllegalArgumentException::class.java) { SongImporter.parse(midi().copyOf(20)) } }
}
