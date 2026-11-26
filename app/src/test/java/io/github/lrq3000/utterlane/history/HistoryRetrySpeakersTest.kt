package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryRetrySpeakersTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun historyRestoresCaptureRequestIndependentlyOfLabelsInTheTranscript() {
        val history = RecordingHistory(temp.root)
        fun record(request: Boolean?, change: Boolean?, actual: Boolean): String {
            val recording = history.begin(HistoryRetention.HOUR, speakerRequest = request)
            recording.append(shortArrayOf(2, 4, 6))
            val id = recording.entry.id
            if (change != null) history.setSpeakerRequest(id, change)
            history.setSpeakerLabels(id, actual)
            recording.finish(false)
            return id
        }
        val unavailable = record(true, null, false)
        val switchedOff = record(true, false, true)
        val oldLabeled = record(null, null, true)
        val oldPlain = record(null, null, false)
        val restored = RecordingHistory(temp.root).also { it.initialize() }
        assertFalse(restored.get(unavailable).speakerLabels)
        assertTrue(restored.get(unavailable).retrySpeakers)
        assertTrue(restored.get(switchedOff).speakerLabels)
        assertFalse(restored.get(switchedOff).retrySpeakers)
        assertTrue(restored.get(oldLabeled).retrySpeakers)
        assertFalse(restored.get(oldPlain).retrySpeakers)
    }
}
