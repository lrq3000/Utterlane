package io.github.lrq3000.utterlane.asr

import android.app.Application
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LiveSpeakerSwitchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun togglePreservesPlainPrefixAndDrainsLabeledTailBeforePlainTextResumes() = runBlocking {
        val store = TranscriptStore(temporary.newFile())
        val controls = LiveSpeakerControl()
        val labels = mutableListOf<Long>()
        var pending: List<SpeechSpan> = emptyList()
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {},
            decode = { if (it.ownedStart == 0L) "before" else "plain" },
            options = RuntimeOptions(asrWindowSeconds = 1.0, asrMinSeconds = 1.0,
                asrLeftContextSeconds = 0.0, asrRightContextSeconds = 0.0),
            decodeSpeakers = {
                labels += it.ownedStart
                pending = listOf(SpeechSpan(if (it.ownedStart == 16000L) "first" else "again", 0))
                emptyList()
            },
            finishSpeakers = { pending.also { pending = emptyList() } },
            speakerRequested = { controls.resolve(false) })
        try {
            val block = ShortArray(16000) { 1000 }
            session.accept(block)
            controls.set(true)
            session.accept(block)
            controls.set(false)
            session.accept(block)
            controls.set(true)
            session.accept(block)
            session.finish()
            assertEquals(listOf(16000L, 48000L), labels)
            assertEquals("before\nSpeaker 1: first\nplain\nSpeaker 1: again", store.file.readText())
        } finally { session.close(); store.keepForRecovery() }
    }

    @Test fun diarizationWithARebasedClockKeepsOnlyOwnedWords() {
        val asr = object : RecognitionBackend {
            override fun transcribeWindow(samples: ShortArray) =
                WindowResult(arrayOf(" earlier", " owned"), floatArrayOf(.1f, .8f))
            override fun close() = Unit
        }
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean) =
                FloatArray(samples.size / 160 * 8) { if (it % 8 == 2) .95f else .01f }
            override fun close() = Unit
        }
        val start = 50123L // Deliberately not on the 10 ms diarization grid.
        DiarizedWindowProcessor(asr, stream, 0).use {
            val window = AudioWindow(ShortArray(24000), start, start + 8000, start + 24000, true)
            assertEquals(listOf(SpeechSpan("owned", 2)), it.process(window))
        }
    }

    @Test fun unavailableSpeakerModelKeepsPlainTranscriptAndCanBeReenabled() = runBlocking {
        val store = TranscriptStore(temporary.newFile())
        val controls = LiveSpeakerControl().also { it.set(true); it.initialize(false) }
        val options = RuntimeOptions(asrWindowSeconds = 1.0, asrMinSeconds = 1.0,
            asrLeftContextSeconds = 0.0, asrRightContextSeconds = 0.0)
        var available = false
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {},
            decode = { "kept without labels" }, options = options,
            decodeSpeakers = { listOf(SpeechSpan("now labeled", 0)) },
            speakerRequested = { controls.resolve(false) },
            speakerStart = { available.also { if (!it) controls.unavailable() } })
        try {
            val block = ShortArray(16000) { 1000 }
            session.accept(block)
            assertEquals(false, controls.state.value)
            assertEquals("kept without labels", store.file.readText())
            available = true
            controls.set(true)
            session.accept(block)
            session.finish()
            assertEquals("kept without labels\nSpeaker 1: now labeled", store.file.readText())
        } finally { session.close(); store.keepForRecovery() }
    }

    @Test fun speakerEofFailureEmitsAlreadyRecognizedWordsWithoutLabels() = runBlocking {
        val store = TranscriptStore(temporary.newFile())
        var fallback = 0
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {},
            decode = { error("The owned window was already recognized") },
            decodeSpeakers = { emptyList() },
            finishSpeakers = { listOf(SpeechSpan("already recognized", SpeechSpan.UNLABELED)) },
            onSpeakersBypassed = { fallback++ })
        try {
            session.finish()
            assertEquals("already recognized", store.file.readText())
            assertEquals(1, fallback)
            assertEquals(false, session.hasSpeakerLabels)
        } finally { session.close(); store.keepForRecovery() }
    }

    @Test fun newestTapWhileDrainingAppliesToTheNextUnstartedWindow() = runBlocking {
        val store = TranscriptStore(temporary.newFile())
        val controls = LiveSpeakerControl().also { it.set(true) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var drains = 0
        val options = RuntimeOptions(asrWindowSeconds = 1.0, asrMinSeconds = 1.0,
            asrLeftContextSeconds = 0.0, asrRightContextSeconds = 0.0)
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {},
            decode = { "unexpected plain" }, options = options,
            decodeSpeakers = { listOf(SpeechSpan(if (it.ownedStart == 0L) "first" else "next", 0)) },
            speakerRequested = { controls.resolve(false) },
            finishSpeakers = {
                if (++drains == 1) { started.complete(Unit); release.await() }
                emptyList()
            })
        try {
            val block = ShortArray(16000) { 1000 }
            session.accept(block)
            controls.set(false)
            val followingWindow = async { session.accept(block) }
            started.await()
            controls.set(true)
            release.complete(Unit)
            followingWindow.await()
            session.finish()
            assertEquals("Speaker 1: first\nSpeaker 1: next", store.file.readText())
        } finally { release.complete(Unit); session.close(); store.keepForRecovery() }
    }
}
