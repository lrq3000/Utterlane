package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class SpeakerTimelineTest {
    private fun frames(vararg speakers: Int) = speakers.flatMap { speaker ->
        List(100) { List(8) { if (it == speaker) .95f else .01f } }.flatten()
    }.toFloatArray()

    @Test fun autoKeepsReturningSpeakersAndUnknownAudio() {
        val timeline = SpeakerTimeline(0)
        timeline.append(frames(0, 2, 0, -1))
        assertEquals(0, timeline.speakerAt(8000))
        assertEquals(2, timeline.speakerAt(24000))
        assertEquals(0, timeline.speakerAt(40000))
        assertEquals(-1, timeline.speakerAt(56000))
    }
    @Test fun explicitCountConstrainsTracksRatherThanInventingVoices() {
        val timeline = SpeakerTimeline(2)
        timeline.append(FloatArray(800) { when (it % 8) { 0 -> .6f; 7 -> .9f; else -> .1f } })
        assertEquals(0, timeline.speakerAt(8000))
        assertThrows(IllegalArgumentException::class.java) { SpeakerTimeline(9) }
        assertThrows(IllegalArgumentException::class.java) { SpeakerTimeline(-1) }
    }
    @Test fun timelineIsBoundedAndRetainsAbsoluteTimeAfterPruning() {
        val timeline = SpeakerTimeline(0)
        repeat(100) { second ->
            timeline.discardBefore(second * 16000L)
            timeline.append(frames(second % 2))
            assertEquals(second % 2, timeline.speakerAt(second * 16000L + 8000))
            assertTrue(timeline.retainedFrames <= 100)
        }
        assertThrows(IllegalArgumentException::class.java) { timeline.append(floatArrayOf(Float.NaN)) }
    }
    @Test fun speakerTurnsCoverOwnedAudioWithoutGapsOrOverlaps() {
        val timeline = SpeakerTimeline(0)
        timeline.append(frames(0, 1, 0))
        val turns = timeline.turns(8000, 40000)
        assertEquals(listOf(0, 1, 0), turns.map { it.speaker })
        assertEquals(8000L, turns.first().start)
        assertEquals(40000L, turns.last().end)
        turns.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }
}
