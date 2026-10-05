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
    @Test fun explicitCountMapsAllNativeTracksRatherThanDiscardingHighChannels() {
        val timeline = SpeakerTimeline(2)
        timeline.append(FloatArray(800) { when (it % 8) { 0 -> .6f; 7 -> .9f; else -> .1f } })
        assertEquals(0, timeline.speakerAt(8000))
        timeline.append(frames(4, 7, 2))
        assertEquals(1, timeline.speakerAt(24000))
        assertEquals(0, timeline.speakerAt(40000))
        assertEquals(-1, timeline.speakerAt(56000))
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

    @Test fun shortRealTurnIsNotAbsorbedByItsPredecessor() {
        val timeline = SpeakerTimeline(0)
        timeline.append(frames(0))
        timeline.append(FloatArray(12 * 8) { if (it % 8 == 1) .95f else .01f })
        timeline.append(frames(0))
        assertEquals(listOf(0, 1, 0), timeline.turns(0, 33920).map { it.speaker })
    }

    @Test fun tiedProbabilitiesAreUnknownRatherThanAnArbitraryIdentity() {
        val timeline = SpeakerTimeline(0)
        timeline.append(FloatArray(100 * 8) { if (it % 8 < 2) .8f else .01f })
        assertEquals(-1, timeline.speakerAt(8000))
    }

    @Test fun intervalUsesProbabilityMassRatherThanCountingFrameWinners() {
        val timeline = SpeakerTimeline(0)
        timeline.append(FloatArray(100 * 8) { i -> when (i % 8) {
            0 -> if (i / 8 < 70) .51f else .01f
            1 -> if (i / 8 < 70) .49f else .99f
            else -> .01f
        } })
        assertEquals(1, timeline.speakerDuring(0, 16000))
    }

    @Test fun capacityPrunesOldProbabilitiesEvenWhenNativeDeliversAnOversizedBatch() {
        val timeline = SpeakerTimeline(0, capacity = 100)
        timeline.append(frames(0, 1, 2))
        assertEquals(100, timeline.retainedFrames)
        assertEquals(-1, timeline.speakerAt(8000))
        assertEquals(2, timeline.speakerDuring(32000, 48000))
        assertEquals(48000L, timeline.endSample)
    }

    @Test fun ambiguityAndLongGapsCannotBeHiddenByMatchingNeighbors() {
        val timeline = SpeakerTimeline(0)
        timeline.append(frames(0, -1, 0))
        assertEquals(-1, timeline.speakerDuring(16000, 32000))
        val overlap = SpeakerTimeline(0)
        overlap.append(frames(0))
        overlap.append(FloatArray(20 * 8) { if (it % 8 < 2) .8f else .01f })
        overlap.append(frames(0))
        assertEquals(-1, overlap.speakerDuring(16000, 19200))
    }

    @Test fun briefWeakChannelCannotConsumeAFixedCountIdentity() {
        val timeline = SpeakerTimeline(2)
        timeline.append(FloatArray(3 * 8) { when (it % 8) { 7 -> .56f; 0 -> .46f; else -> .01f } })
        timeline.append(frames(4, 6))
        assertEquals(-1, timeline.speakerAt(0))
        assertEquals(0, timeline.speakerAt(8000))
        assertEquals(1, timeline.speakerAt(24000))
    }

    @Test fun mixedWeakAndStrongFramesCannotReserveAnIdentityBeforeTwoSustainedSpeakers() {
        for (strengths in listOf(listOf(false, false, true), listOf(true, false, true, true))) {
            val timeline = SpeakerTimeline(2)
            for (strong in strengths) timeline.append(candidateFrame(strong))
            timeline.append(frames(4, 6))
            assertEquals("Mixed evidence must not permanently reserve a slot", -1, timeline.speakerAt(0))
            val offset = strengths.size * 160L
            assertEquals(0, timeline.speakerAt(offset + 8000))
            assertEquals(1, timeline.speakerAt(offset + 24000))
        }
    }

    @Test fun threeConsecutiveStrongFramesOrFullWeakConfirmationStillEstablishIdentity() {
        for (strong in listOf(false, true)) {
            val timeline = SpeakerTimeline(2)
            val needed = if (strong) 3 else 20
            repeat(needed) { index ->
                timeline.append(candidateFrame(strong))
                assertEquals(if (index + 1 == needed) 0 else -1, timeline.speakerAt(index * 160L))
            }
        }
    }

    private fun candidateFrame(strong: Boolean) = FloatArray(8) { channel -> when (channel) {
        7 -> if (strong) .95f else .56f
        0 -> if (strong) .01f else .46f
        else -> .01f
    } }
}
