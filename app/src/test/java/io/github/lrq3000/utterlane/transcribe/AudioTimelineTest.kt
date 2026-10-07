package io.github.lrq3000.utterlane.transcribe

import org.junit.Assert.*
import org.junit.Test

class AudioTimelineTest {
    @Test fun boundariesAndFinalShortPartUseOneContinuousTimeline() {
        val timeline = AudioTimeline(7200123, 3600000)
        assertEquals(AudioTimeline.Position(0, 0), timeline.locate(-1))
        assertEquals(AudioTimeline.Position(0, 3599999), timeline.locate(3599999))
        assertEquals(AudioTimeline.Position(1, 0), timeline.locate(3600000))
        assertEquals(AudioTimeline.Position(2, 123), timeline.locate(Long.MAX_VALUE))
        assertEquals(123L, timeline.duration(2))
    }

    @Test fun recordingCanExceedTheNativePlayersIntMillisecondRange() {
        val timeline = AudioTimeline(100L * 86400000, 3600000)
        assertEquals(AudioTimeline.Position(2399, 3600000), timeline.locate(timeline.durationMs))
        val single = AudioTimeline(9000)
        assertEquals(AudioTimeline.Position(0, 4500), single.locate(4500))
        assertThrows(IllegalArgumentException::class.java) { AudioTimeline(0) }
    }
}
