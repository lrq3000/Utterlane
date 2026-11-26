package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.*

class QueueRetryRegistrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun confirmedDeletionCannotPassValidationUntilSiblingOwnsTheRecording() {
        val inspecting = CountDownLatch(1)
        val inspectedByDeleter = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val io = object : AudioAssetIO by PrivateAudioAssetIO {
            override fun available(part: AudioPart): Boolean {
                inspecting.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                return PrivateAudioAssetIO.available(part)
            }
        }
        val history = RecordingHistory(File(temp.root, "audio"), audioIO = io)
        val journal = QueueJournal(File(temp.root, "queue"))
        val source = history.importAudioOwned(byteArrayOf(1, 2, 3).inputStream(), "ogg", AudioImportMetadata("test.ogg"), ImportRetentionChoice.KEEP)
        val original = journal.add(source.entry.id, "test", false, QueueStage.STOPPED)
        source.lease.close()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val retry = pool.submit<QueueRecord> { QueueRetryRegistration(history, journal).add(original.audio, false, null, "test", original.id) }
            assertTrue(inspecting.await(10, TimeUnit.SECONDS))
            val deletion = pool.submit<Boolean> {
                inspectedByDeleter.countDown()
                synchronized(history) {
                    journal.anotherOwnsAudio(original.audio, original.id).also { if (!it) history.delete(original.audio) }
                }
            }
            assertTrue(inspectedByDeleter.await(10, TimeUnit.SECONDS))
            resume.countDown()
            assertEquals(original.audio, retry.get(10, TimeUnit.SECONDS).audio)
            assertTrue(deletion.get(10, TimeUnit.SECONDS))
            assertTrue(history.get(original.audio).part(0).isFile)
        } finally { resume.countDown(); pool.shutdownNow() }
    }
}
