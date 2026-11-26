package io.github.lrq3000.utterlane.transcribe

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueTerminalDeliveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun deathAfterAndroidAcceptsPostRecoversUsingSameNotificationIdentity() {
        val journal = QueueJournal(temp.root)
        val row = journal.add("audio", "Result", false, QueueStage.READY)
        val posted = mutableListOf<Int>()
        assertThrows(IOException::class.java) {
            QueueTerminalDelivery(journal).deliver(row.id) { posted.add(it.notificationId); throw IOException("Lost before mark") }
        }
        val restarted = QueueTerminalDelivery(QueueJournal(temp.root))
        assertTrue(restarted.deliver(row.id) { posted.add(it.notificationId) })
        assertFalse(restarted.deliver(row.id) { posted.add(it.notificationId) })
        assertEquals(listOf(row.notificationId, row.notificationId), posted)
    }

    @Test fun restartDeliversUnpostedTerminalExactlyOnceAfterSuccessAndNeverReplaysReviewedRows() {
        val journal = QueueJournal(temp.root)
        val ready = journal.add("a", "First", false)
        val failed = journal.add("b", "Second", true)
        journal.update(ready.copy(stage = QueueStage.READY))
        journal.update(failed.copy(stage = QueueStage.FAILED))
        val reopened = QueueJournal(temp.root)
        val delivery = QueueTerminalDelivery(reopened)
        assertThrows(IOException::class.java) { delivery.deliver(ready.id) { throw IOException("delivery interrupted") } }
        assertFalse(delivery.deliver(ready.id) { false }) // Notifications disabled, no successful post.
        assertTrue(reopened.acknowledge(failed.id))
        val delivered = mutableListOf<String>()
        fun restart() {
            val disk = QueueJournal(temp.root)
            val notices = QueueTerminalDelivery(disk)
            for (row in disk.records()) notices.deliver(row.id) { delivered.add(it.id) }
            assertFalse(notices.deliver(failed.id) { fail("acknowledged"); true })
        }
        restart()
        restart() // Includes a user who swiped the successfully delivered notification.
        assertEquals(listOf(ready.id), delivered)
    }

    @Test fun foregroundResultIsNotMarkedPostedBeforeDetachAndLateDetachCannotResurrectReviewedResult() {
        val journal = QueueJournal(temp.root)
        val delivery = QueueTerminalDelivery(journal)
        val row = journal.add("audio", "Recording", false, QueueStage.PROCESSING)
        delivery.attach(row) { assertEquals(row, it) }
        journal.update(row.copy(stage = QueueStage.READY))
        assertFalse(delivery.deliver(row.id) { fail("Still foreground"); true })
        assertTrue(journal.acknowledge(row.id))
        val removed = mutableListOf<String>()
        delivery.detach({}, { removed.add(it.id) }) { fail("Reviewed"); true }
        assertEquals(listOf(row.id), removed)
    }

    @Test fun acknowledgingDuringPostCannotBeFollowedByAStaleTerminalNotification() {
        val journal = QueueJournal(temp.root)
        val delivery = QueueTerminalDelivery(journal)
        val row = journal.add("audio", "Result", false, QueueStage.READY)
        val enteredPost = CountDownLatch(1)
        val startedReview = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val writer = thread {
            delivery.deliver(row.id) {
                enteredPost.countDown()
                check(startedReview.await(5, TimeUnit.SECONDS))
                events.add("posted")
            }
        }
        assertTrue(enteredPost.await(5, TimeUnit.SECONDS))
        startedReview.countDown()
        assertTrue(delivery.acknowledge(row.id) { events.add("removed") })
        writer.join(5000)
        assertFalse(writer.isAlive)
        assertEquals(listOf("posted", "removed"), events)
        assertTrue(QueueJournal(temp.root).records().isEmpty())
    }
}
