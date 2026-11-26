package io.github.lrq3000.utterlane.service

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.QueueTranscriptTarget
import io.github.lrq3000.utterlane.transcribe.QueueJournal
import io.github.lrq3000.utterlane.transcribe.QueueStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class KeyboardQueueHandoffTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun completionThatRacesPublicationKeepsTheExactTranscriptAndAudioIdentity() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = directory.root.resolve("$id.txt")
        val journal = QueueJournal(directory.root)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val handoff = KeyboardQueueHandoff(this, file, register = { audio, speakers ->
            release.await()
            journal.add(audio, "Dictation", speakers, QueueStage.DRAINING, id)
        }, finish = { error, speakers -> journal.finishCapture(id, error, "Interrupted", speakers) })
        val store = TranscriptStore(file).apply {
            beginTranscription(TranscriptSource(audioId = "original-audio"))
            append("Before settings")
        }
        assertTrue(handoff.submit("original-audio", true, { events.add("settings") }, { fail(it.toString()) }))
        assertFalse(handoff.submit("other-audio", false, { fail("duplicate settings") }, { fail(it.toString()) }))
        store.append("after settings request")
        store.markComplete()
        store.finishProducing()
        val completed = handoff.complete(store, null, true) { fail("queued text must not be inserted into an editor") }
        yield()
        assertNull(journal.find(id))
        assertTrue(events.isEmpty())
        release.complete(Unit)
        withTimeout(3000) { completed.join() }
        assertEquals(listOf("settings"), events)
        assertEquals("original-audio", journal.find(id)?.audio)
        assertEquals(QueueStage.READY, journal.find(id)?.stage)
        assertEquals("Before settings after settings request", file.readText())
    }

    @Test fun failedPublicationDoesNotTakeAwayForegroundDelivery() = runBlocking {
        val events = mutableListOf<String>()
        val file = directory.root.resolve("unclaimed.txt")
        val store = TranscriptStore(file).apply { append("Still available") }
        val handoff = KeyboardQueueHandoff(this, file, register = { _, _ -> error("Full storage") },
            finish = { _, _ -> fail("There is no queued job to complete") })
        handoff.submit("original-audio", false, { fail("Settings must not open") }, { events.add(it.message.orEmpty()) })
        val completed = handoff.complete(store, null, false) {
            events.add(store.readForTransfer().orEmpty())
            store.dispose()
        }
        withTimeout(3000) { completed.join() }
        assertEquals(listOf("Full storage", "Still available"), events)
    }

    @Test fun journalCommitWinsEvenWhenLaterPublicationWorkThrows() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = directory.root.resolve("$id.txt")
        val journal = QueueJournal(directory.root)
        val store = TranscriptStore(file).apply { append("Do not lose this") }
        val handoff = KeyboardQueueHandoff(this, file, register = { audio, speakers ->
            journal.add(audio, "Dictation", speakers, QueueStage.DRAINING, id)
            error("Notifications unavailable after write")
        }, finish = { error, speakers -> journal.finishCapture(id, error, "Interrupted", speakers) },
            isRegistered = { audio -> journal.find(id)?.audio == audio })
        val accepted = CompletableDeferred<Unit>()
        assertTrue(handoff.submit("original", false, { accepted.complete(Unit) }) { fail(it.toString()) })
        val completed = handoff.complete(store, null, false) { fail("Foreground cleanup cannot own a queued transcript") }
        withTimeout(3000) { accepted.await(); completed.join() }
        assertEquals(QueueStage.READY, journal.find(id)?.stage)
        assertEquals("Do not lose this", file.readText())
    }

    @Test fun lackOfAQueuePathDoesNotBlockOrdinaryDictation() = runBlocking {
        val handoff = KeyboardQueueHandoff(this, null, register = { _, _ -> error("Queue path unavailable") },
            finish = { _, _ -> fail("No queue row should be finalized") })
        assertNull(handoff.producerScope.coroutineContext[QueueTranscriptTarget])
        var editorDelivered = false
        handoff.complete(null, null, null) { editorDelivered = true }.join()
        assertTrue(editorDelivered)
    }

    @Test fun unreadableQueueJournalDoesNotHangForegroundDelivery() = runBlocking {
        kotlinx.coroutines.supervisorScope {
            val rejection = CompletableDeferred<String>()
            val delivered = CompletableDeferred<Unit>()
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
            val handoff = KeyboardQueueHandoff(scope, null, register = { _, _ -> error("Cannot register") },
                finish = { _, _ -> fail("No durable queue row") }, isRegistered = { error("Cannot read journal") })
            assertTrue(handoff.submit("source", false, { fail("Settings must stay closed") }) { rejection.complete(it.message.orEmpty()) })
            val completion = handoff.complete(null, null, null) { delivered.complete(Unit) }
            try {
                withTimeout(1000) { assertEquals("Cannot register", rejection.await()); delivered.await() }
            } finally { completion.cancel() }
        }
    }
}
