package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import io.github.lrq3000.utterlane.settings.RuntimeOptions

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
        val timeline = SpeakerTimeline(0, options = RuntimeOptions(unknownBridgeMs = 350))
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

    @Test fun alignmentToleranceRecoversAWordWhoseVoicedOffsetPrecedesItsReportedEnd() {
        // A 240 ms word interval contains only 20 ms of voiced evidence, but
        // its 120 ms timestamp uncertainty includes the preceding word body.
        for (tolerance in listOf(0, 120)) {
            val timeline = alignmentTimeline(tolerance) { frame, channel ->
                if (channel == 0) { if (frame < 150 || frame >= 200) .995f else .08f } else .01f
            }
            assertEquals(if (tolerance == 0) -1 else 0, timeline.speakerDuring(1480 * 16L, 1720 * 16L))
            assertEquals(-1, timeline.speakerDuring(1480 * 16L, 1720 * 16L, coarse = true))
        }
    }

    @Test fun alignmentToleranceRecoversAWordJustBeforeTheVoicedOnsetWithoutBridgingTheWholePause() {
        // The voice resumes 10 ms after the reported word end. The preceding
        // same-voice turn is too far away for the 350 ms unknown-gap bridge.
        for (tolerance in listOf(0, 120)) {
            val timeline = alignmentTimeline(tolerance) { frame, channel ->
                if (channel == 0) { if (frame < 80 || frame >= 145) .995f else .08f } else .01f
            }
            assertEquals(if (tolerance == 0) -1 else 0, timeline.speakerDuring(1200 * 16L, 1440 * 16L))
        }
    }

    @Test fun alignmentCannotReachPastTheConfiguredTolerance() {
        val timeline = alignmentTimeline(120) { frame, channel ->
            if (channel == 0 && (frame < 80 || frame >= 158)) .995f else .08f
        }
        assertEquals(-1, timeline.speakerDuring(1200 * 16L, 1440 * 16L))
    }

    @Test fun alignmentRejectsConflictingNeighborsEvenWhenOneHasMoreProbabilityMass() {
        val timeline = alignmentTimeline(120) { frame, channel -> when {
            channel == 0 && frame < 111 -> .995f
            channel == 1 && frame >= 145 -> .995f
            else -> .08f
        } }
        assertEquals(-1, timeline.speakerDuring(1200 * 16L, 1440 * 16L))
    }

    @Test fun alignmentPreservesAFortyMillisecondRealInterjectionDespiteSurroundingVoice() {
        val timeline = alignmentTimeline(120) { frame, channel ->
            if (channel == if (frame in 120..123) 1 else 0) .995f else .01f
        }
        assertEquals(1, timeline.speakerDuring(1200 * 16L, 1240 * 16L, previous = 0))
    }

    @Test fun alignmentCannotReplaceUnconfirmedOrOverlappingWordEvidenceWithItsNeighbor() {
        for (overlap in listOf(false, true)) {
            val timeline = alignmentTimeline(120) { frame, channel -> when {
                frame !in 120..123 -> if (channel == 0) .995f else .01f
                channel == 0 -> if (overlap) .8f else .46f
                channel == 1 -> if (overlap) .8f else .56f
                else -> .01f
            } }
            assertEquals(-1, timeline.speakerDuring(1200 * 16L, 1240 * 16L))
        }
    }

    @Test fun initialWordBackfillsFromFirstConfirmedVoiceAfterLongLeadingSilenceInAutoAndFixedModes() {
        for (count in listOf(0, 2)) {
            val timeline = initialTimeline(count)
            timeline.append(trackFrames(2000, -1))
            assertEquals(-1, timeline.speakerDuring(19400 * 16L, 19800 * 16L))
            timeline.append(trackFrames(50, 6))
            assertEquals(if (count == 0) 6 else 0, timeline.speakerDuring(19400 * 16L, 19800 * 16L))
            assertEquals(-1, timeline.speakerDuring(19400 * 16L, 19800 * 16L, coarse = true))
            assertTrue(timeline.retainedFrames <= 1600)
        }
    }

    @Test fun firstSpeechBackfillObeysTheConfiguredGapAndRequiresSustainedEvidence() {
        for (bridge in listOf(0, 350)) {
            val timeline = initialTimeline(bridge = bridge)
            timeline.append(trackFrames(300, -1))
            timeline.append(trackFrames(1, 6))
            assertEquals(-1, timeline.speakerDuring(2400 * 16L, 2800 * 16L))
            timeline.append(trackFrames(2, 6))
            for (gap in listOf(10, 200, 350, 360)) {
                val stop = (3000 - gap) * 16L
                assertEquals("bridge=$bridge gap=$gap", if (gap <= bridge) 6 else -1,
                    timeline.speakerDuring(stop - 400 * 16L, stop))
            }
        }
    }

    @Test fun competingInitialTracksCannotForceBackfillAcrossAnOtherwiseSilentWord() {
        val timeline = initialTimeline()
        timeline.append(trackFrames(300, -1))
        timeline.append(FloatArray(3 * 8) { if (it % 8 == 1 || it % 8 == 6) .8f else .01f })
        timeline.append(trackFrames(50, 6))
        assertEquals(-1, timeline.speakerDuring(2400 * 16L, 2800 * 16L))
    }

    @Test fun initialCompetitionAlsoBlocksTheLeadingAudioEdgeFallback() {
        val timeline = SpeakerTimeline(0)
        timeline.append(trackFrames(2, -1))
        timeline.append(FloatArray(3 * 8) { if (it % 8 == 1 || it % 8 == 6) .8f else .01f })
        timeline.append(trackFrames(50, 6))
        assertEquals(-1, timeline.speakerDuring(0, 160))
    }

    @Test fun prunedConfirmedVoiceCannotMakeALaterTurnLookLikeInitialSpeech() {
        for (count in listOf(0, 2)) {
            val timeline = initialTimeline(count, capacity = 100)
            timeline.append(trackFrames(100, 1))
            timeline.append(trackFrames(500, -1))
            timeline.append(trackFrames(50, 6))
            assertEquals(-1, timeline.speakerAt(0))
            assertEquals(-1, timeline.speakerDuring(5600 * 16L, 5800 * 16L))
        }
    }

    @Test fun prunedInitialCompetitionStillPreventsConfidentBackfill() {
        val timeline = initialTimeline(capacity = 100)
        timeline.append(FloatArray(3 * 8) { if (it % 8 == 1 || it % 8 == 6) .8f else .01f })
        timeline.append(trackFrames(597, -1))
        timeline.append(trackFrames(50, 6))
        assertEquals(-1, timeline.speakerDuring(5600 * 16L, 5800 * 16L))
    }

    @Test fun userConfiguredOneSecondBridgeJoinsASevenHundredMillisecondPauseOnlyForTheSameVoice() {
        for (bridge in listOf(350, 1000)) for (returning in listOf(6, 4)) {
            val timeline = initialTimeline(bridge = bridge)
            timeline.append(trackFrames(100, 6))
            timeline.append(trackFrames(70, -1))
            timeline.append(trackFrames(100, returning))
            assertEquals(if (bridge == 1000 && returning == 6) 6 else -1,
                timeline.speakerDuring(1403 * 16L, 1563 * 16L))
        }
    }

    @Test fun briefRealWordEvidenceWinsOverTheLongerSameVoiceBridge() {
        val timeline = initialTimeline(bridge = 1000)
        timeline.append(trackFrames(100, 6))
        timeline.append(trackFrames(42, -1))
        timeline.append(trackFrames(4, 4))
        timeline.append(trackFrames(24, -1))
        timeline.append(trackFrames(100, 6))
        assertEquals(4, timeline.speakerDuring(1403 * 16L, 1563 * 16L))
    }

    private fun initialTimeline(count: Int = 0, capacity: Int = 1600, bridge: Int = 350) =
        SpeakerTimeline(count, capacity, RuntimeOptions(alignmentToleranceMs = 0, unknownBridgeMs = bridge))

    private fun trackFrames(count: Int, channel: Int) = FloatArray(count * 8) { if (it % 8 == channel) .95f else .01f }

    private fun alignmentTimeline(tolerance: Int, probability: (Int, Int) -> Float) =
        // Isolate timestamp tolerance from independently configurable gap bridging.
        SpeakerTimeline(0, options = RuntimeOptions(alignmentToleranceMs = tolerance, unknownBridgeMs = 350)).apply {
            append(FloatArray(250 * 8) { probability(it / 8, it % 8) })
        }

    private fun candidateFrame(strong: Boolean) = FloatArray(8) { channel -> when (channel) {
        7 -> if (strong) .95f else .56f
        0 -> if (strong) .01f else .46f
        else -> .01f
    } }
}
