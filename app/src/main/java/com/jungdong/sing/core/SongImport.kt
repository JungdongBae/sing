package com.jungdong.sing.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlin.math.roundToLong

data class ImportedMelody(val label: String, val referenceKey: String?, val notes: List<MelodyNote>)
data class SongImportResult(val melodies: List<ImportedMelody>, val warnings: List<String>)
private data class TickNote(val midi: Int, val start: Long, val length: Long, val bar: Int)
private data class Tempo(val tick: Long, val micros: Long)

/** Bounded import, no network, no audio, no lyrics. Melody verification is an explicit user step. */
object SongImporter {
    const val MAX_BYTES = 2 * 1024 * 1024
    fun parse(bytes: ByteArray): SongImportResult {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "MIDI/MusicXML은 2MB 이하로 가져와 주세요." }
        return if (bytes.take(4).toByteArray().contentEquals("MThd".toByteArray())) midi(bytes) else musicXml(bytes)
    }
    private fun convert(notes: List<TickNote>, tempos: List<Tempo>, ppq: Long): List<MelodyNote> {
        val ordered = (listOf(Tempo(0, 500000)) + tempos).sortedBy { it.tick }.groupBy { it.tick }.map { it.value.last() }.sortedBy { it.tick }
        fun millis(tick: Long): Long {
            var result = 0.0; var position = 0L; var speed = 500000L
            for (event in ordered) {
                if (event.tick > tick) break
                result += (event.tick - position) * speed.toDouble() / ppq / 1000
                position = event.tick; speed = event.micros
            }
            return (result + (tick - position) * speed.toDouble() / ppq / 1000).roundToLong()
        }
        return notes.sortedBy { it.start }.map { MelodyNote(it.midi, millis(it.start), maxOf(1, millis(it.start + it.length) - millis(it.start)), it.bar) }
    }
    private fun acceptable(notes: List<MelodyNote>) = notes.isNotEmpty() && notes.size <= 20000 &&
        notes.last().endMs <= 600000 && notes.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }
    private class Bytes(private val bytes: ByteArray) {
        var pos = 0
        fun byte(): Int { require(pos < bytes.size) { "MIDI 파일이 잘렸습니다." }; return bytes[pos++].toInt() and 255 }
        fun number(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or byte().toLong() }; return v }
        fun take(n: Int): ByteArray { require(n >= 0 && n <= bytes.size - pos); return bytes.copyOfRange(pos, pos + n).also { pos += n } }
        fun variable(): Long {
            var value = 0L
            repeat(4) { val b = byte(); value = (value shl 7) or (b and 127).toLong(); if (b and 128 == 0) return value }
            error("MIDI 가변 길이가 잘못되었습니다.")
        }
        val remaining get() = bytes.size - pos
    }
    private fun key(fifths: Int, minor: Boolean): String {
        require(fifths in -7..7)
        val major = listOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
        val minors = listOf("Abm", "Ebm", "Bbm", "Fm", "Cm", "Gm", "Dm", "Am", "Em", "Bm", "F#m", "C#m", "G#m", "D#m", "A#m")
        return (if (minor) minors else major)[fifths + 7]
    }
    private fun midi(bytes: ByteArray): SongImportResult {
        val input = Bytes(bytes); require(String(input.take(4), Charsets.US_ASCII) == "MThd")
        val header = input.number(4).toInt(); require(header >= 6 && header <= 1024)
        val format = input.number(2).toInt(); require(format in 0..1) { "MIDI 형식 0/1만 지원합니다." }
        val tracks = input.number(2).toInt(); require(tracks in 1..128 && (format != 0 || tracks == 1))
        val ppq = input.number(2); require(ppq in 1..32767) { "SMPTE MIDI는 지원하지 않습니다." }; input.take(header - 6)
        data class Raw(val label: String, val notes: List<TickNote>)
        val raw = mutableListOf<Raw>(); val tempos = mutableListOf<Tempo>(); val keys = mutableListOf<Pair<Long, String>>()
        var meter: Pair<Int, Int>? = null
        val warnings = mutableListOf<String>()
        repeat(tracks) { trackIndex ->
            require(String(input.take(4), Charsets.US_ASCII) == "MTrk")
            val length = input.number(4); require(length <= input.remaining && length <= MAX_BYTES)
            val t = Bytes(input.take(length.toInt())); var tick = 0L; var running = 0; var name = "트랙 ${trackIndex + 1}"
            val active = mutableMapOf<Pair<Int, Int>, Long>(); val byChannel = mutableMapOf<Int, MutableList<TickNote>>()
            while (t.remaining > 0) {
                tick += t.variable(); require(tick <= ppq * 60 * 60) { "MIDI 시간이 너무 깁니다." }
                val first = t.byte(); val status = if (first >= 128) first else running
                require(status >= 128) { "MIDI running status가 잘못되었습니다." }
                if (status in 128..239) running = status else running = 0
                when {
                    status == 255 -> {
                        val type = t.byte(); val n = t.variable(); require(n <= t.remaining); val data = t.take(n.toInt())
                        when (type) {
                            3 -> name = String(data, Charsets.UTF_8).take(80)
                            81 -> { require(data.size == 3); val speed = Bytes(data).number(3); require(speed in 100000..3000000); tempos.add(Tempo(tick, speed)) }
                            88 -> {
                                require(data.size >= 2); val beats = data[0].toInt() and 255; val pow = data[1].toInt() and 255
                                require(beats in 1..12 && pow in 0..5)
                                val value = beats to (1 shl pow)
                                require(meter == null || meter == value) { "박자표가 바뀌는 MIDI는 현재 지원하지 않습니다." }; meter = value
                            }
                            89 -> { require(data.size == 2 && data[1].toInt() in 0..1); keys.add(tick to key(data[0].toInt(), data[1].toInt() == 1)) }
                            47 -> require(data.isEmpty())
                        }
                    }
                    status == 240 || status == 247 -> { val n = t.variable(); require(n <= t.remaining); t.take(n.toInt()) }
                    status in 128..239 -> {
                        val kind = status and 240; val channel = status and 15
                        val a = if (first < 128) first else t.byte(); require(a < 128)
                        val b = if (kind == 192 || kind == 208) 0 else t.byte(); require(b < 128)
                        if (channel != 9 && (kind == 144 || kind == 128)) {
                            val k = channel to a
                            if (kind == 144 && b > 0) {
                                require(k !in active) { "겹치는 동일 음이 있습니다. 멜로디 전용 MIDI를 사용해 주세요." }; active[k] = tick
                            } else {
                                val start = active.remove(k)
                                if (start != null && tick > start) byChannel.getOrPut(channel) { mutableListOf() }.add(TickNote(a, start, tick - start, 1))
                            }
                        }
                    }
                    else -> error("지원하지 않는 MIDI 이벤트입니다.")
                }
            }
            require(active.isEmpty()) { "끝나지 않은 MIDI 음이 있습니다." }
            byChannel.forEach { (channel, notes) -> raw.add(Raw("$name · 채널 ${channel + 1}", notes)) }
        }
        require(input.remaining == 0) { "MIDI 뒤에 알 수 없는 데이터가 있습니다." }
        val (beats, denominator) = meter ?: (4 to 4)
        if (meter == null) warnings.add("박자표가 없어 마디 번호는 4/4 기준입니다. 구간과 마디를 직접 확인해 주세요.")
        if (tempos.isEmpty()) warnings.add("템포 정보가 없어 MIDI 기본값 120 BPM을 사용합니다. 타이밍을 직접 확인해 주세요.")
        val barTicks = ppq * beats * 4.0 / denominator
        val ref = keys.map { it.second }.distinct().singleOrNull()
        if (ref == null) warnings.add("기준 키 정보가 없거나 키가 바뀝니다. 원키를 추정하지 않고 반음 이동량으로 표시합니다.")
        val melodies = raw.mapNotNull { part ->
            val notes = convert(part.notes.map { it.copy(bar = (it.start / barTicks).toInt() + 1) }, tempos, ppq)
            if (!acceptable(notes)) { warnings.add("${part.label}: 다성부·10분 초과·빈 파트는 제외했습니다."); null }
            else ImportedMelody(part.label, ref, notes)
        }
        require(melodies.isNotEmpty()) { "분리된 단선율 멜로디 파트가 없습니다." }
        return SongImportResult(melodies, warnings)
    }
    private fun Element.children(tag: String? = null): List<Element> = (0 until childNodes.length).mapNotNull {
        (childNodes.item(it) as? Element)?.takeIf { e -> tag == null || e.tagName == tag }
    }
    private fun Element.child(tag: String) = children(tag).firstOrNull()
    private fun Element.value(tag: String) = child(tag)?.textContent?.trim()
    private fun musicXml(bytes: ByteArray): SongImportResult {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val xml = decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "외부 DTD/엔티티가 없는 UTF-8 MusicXML을 사용해 주세요." }
        require(!xml.contains("encoding=\"UTF-16\"", true)) { "UTF-8 MusicXML만 지원합니다." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false; isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> error("외부 자료를 읽지 않습니다.") }
        val root = builder.parse(InputSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))).documentElement
        require(root.tagName == "score-partwise") { "압축하지 않은 score-partwise MusicXML(.musicxml/.xml)을 사용해 주세요." }
        require(root.getElementsByTagName("repeat").length == 0 && root.getElementsByTagName("ending").length == 0 &&
            root.getElementsByTagName("segno").length == 0 && root.getElementsByTagName("coda").length == 0) {
            "반복·도돌이표를 펼친 MusicXML을 사용해 주세요. 원곡 구조를 임의로 추정하지 않습니다."
        }
        val sounds = root.getElementsByTagName("sound")
        (0 until sounds.length).forEach { index ->
            val sound = sounds.item(index) as Element
            require(listOf("dacapo", "dalsegno", "tocoda", "fine").none { sound.hasAttribute(it) }) {
                "재생 순서의 반복·이동을 펼친 MusicXML을 사용해 주세요."
            }
        }
        val names = root.child("part-list")?.children("score-part")?.associate { it.getAttribute("id") to (it.value("part-name") ?: it.getAttribute("id")) } ?: emptyMap()
        val ppq = 9600L; val melodies = mutableListOf<ImportedMelody>(); val warnings = mutableListOf<String>()
        data class XmlRaw(val label: String, val referenceKey: String?, val ticks: List<TickNote>)
        val raw = mutableListOf<XmlRaw>(); val globalTempos = mutableListOf<Tempo>()
        val parts = root.children("part"); require(parts.size in 1..128)
        parts.forEach { part ->
            var divisions = 1L; var beats = 4; var denominator = 4; var measureStart = 0L
            val voices = mutableMapOf<String, MutableList<TickNote>>(); val tempos = mutableListOf<Tempo>()
            val keyNames = mutableSetOf<String>()
            val ties = mutableMapOf<Pair<String, Int>, Int>()
            val measures = part.children("measure"); require(measures.size <= 2000)
            measures.forEachIndexed { index, measure ->
                var cursor = 0L; var furthest = 0L; var previousStart = 0L
                measure.children().forEach { item ->
                    when (item.tagName) {
                        "attributes" -> {
                            item.value("divisions")?.let { divisions = it.toLong(); require(divisions in 1..100000) }
                            item.child("time")?.let { time ->
                                beats = time.value("beats")?.toIntOrNull() ?: error("복합 박자표는 지원하지 않습니다.")
                                denominator = time.value("beat-type")?.toInt() ?: 4; require(beats in 1..12 && denominator in listOf(1,2,4,8,16,32))
                            }
                            item.child("transpose")?.let { require((it.value("chromatic")?.toInt() ?: 0) == 0) { "실음으로 변환한 멜로디 악보를 사용해 주세요." } }
                            item.child("key")?.value("fifths")?.let { fifths ->
                                val mode = item.child("key")?.value("mode") ?: "major"
                                require(mode == "major" || mode == "minor"); keyNames.add(key(fifths.toInt(), mode == "minor"))
                            }
                        }
                        "direction" -> item.child("sound")?.getAttribute("tempo")?.takeIf { it.isNotBlank() }?.let {
                            val bpm = it.toDouble(); require(bpm.isFinite() && bpm in 20.0..600.0)
                            require(!item.child("sound")!!.hasAttribute("dacapo") && !item.child("sound")!!.hasAttribute("dalsegno")) { "반복을 펼친 악보가 필요합니다." }
                            val offset = item.value("offset")?.toLong() ?: 0
                            val tick = measureStart + cursor + offset * ppq / divisions
                            require(tick >= 0)
                            tempos.add(Tempo(tick, (60_000_000 / bpm).roundToLong()))
                        }
                        "backup", "forward" -> {
                            val d = (item.value("duration") ?: error("박자 길이가 없습니다.")).toLong() * ppq / divisions
                            require(d > 0); cursor += if (item.tagName == "backup") -d else d; require(cursor >= 0); furthest = maxOf(furthest, cursor)
                        }
                        "note" -> {
                            require(item.child("grace") == null) { "꾸밈음을 일반 음으로 펼친 악보를 사용해 주세요." }
                            val duration = (item.value("duration") ?: error("음 길이가 없습니다.")).toLong() * ppq / divisions; require(duration > 0)
                            val chord = item.child("chord") != null; val onset = if (chord) previousStart else cursor
                            val voice = "${item.value("voice") ?: "1"} · 보표 ${item.value("staff") ?: "1"}"
                            val pitch = item.child("pitch")
                            if (pitch != null) {
                                val step = pitch.value("step") ?: error("음 이름이 없습니다.")
                                val pitchClass = mapOf("C" to 0,"D" to 2,"E" to 4,"F" to 5,"G" to 7,"A" to 9,"B" to 11)[step] ?: error("음 이름 오류")
                                val alterText = pitch.value("alter")?.toDouble() ?: 0.0; require(alterText.isFinite() && alterText == alterText.toInt().toDouble()) { "반음보다 작은 조옮김은 지원하지 않습니다." }
                                val midi = ((pitch.value("octave") ?: error("옥타브가 없습니다.")).toInt() + 1) * 12 + pitchClass + alterText.toInt(); require(midi in 0..127)
                                val list = voices.getOrPut(voice) { mutableListOf() }; val k = voice to midi
                                val tieTypes = item.children("tie").map { it.getAttribute("type") }
                                if ("stop" in tieTypes) {
                                    val priorIndex = ties.remove(k) ?: error("연결되지 않은 붙임줄입니다.")
                                    val prior = list[priorIndex]; require(prior.start + prior.length == measureStart + onset)
                                    list[priorIndex] = prior.copy(length = prior.length + duration)
                                    if ("start" in tieTypes) ties[k] = priorIndex
                                } else {
                                    list.add(TickNote(midi, measureStart + onset, duration, index + 1))
                                    if ("start" in tieTypes) ties[k] = list.lastIndex
                                }
                            } else require(item.child("rest") != null) { "비음정 타악기 파트는 지원하지 않습니다." }
                            previousStart = onset
                            if (!chord) cursor += duration
                            furthest = maxOf(furthest, onset + duration, cursor)
                        }
                    }
                }
                require(furthest > 0)
                val expected = ppq * beats * 4 / denominator
                measureStart += if (measure.getAttribute("implicit") == "yes") furthest else maxOf(expected, furthest)
            }
            require(ties.isEmpty()) { "끝나지 않은 붙임줄이 있습니다." }
            globalTempos.addAll(tempos)
            voices.forEach { (voice, ticks) ->
                val label = "${names[part.getAttribute("id")] ?: part.getAttribute("id")} · 성부 $voice"
                raw.add(XmlRaw(label, keyNames.singleOrNull(), ticks))
            }
        }
        require(globalTempos.groupBy { it.tick }.values.all { values -> values.map { it.micros }.distinct().size == 1 }) { "파트 사이의 템포가 다릅니다. 하나의 검증된 템포 지도를 사용해 주세요." }
        if (globalTempos.isEmpty()) warnings.add("템포가 없어 기본 120 BPM입니다. 타이밍을 직접 확인해 주세요.")
        raw.forEach { part ->
            val notes = convert(part.ticks, globalTempos, ppq)
            if (acceptable(notes)) melodies.add(ImportedMelody(part.label, part.referenceKey, notes))
            else warnings.add("${part.label}: 겹치는 음 또는 10분 초과 파트는 제외했습니다.")
        }
        require(melodies.isNotEmpty()) { "분리된 단선율 멜로디 파트가 없습니다." }
        return SongImportResult(melodies, warnings.distinct())
    }
}
