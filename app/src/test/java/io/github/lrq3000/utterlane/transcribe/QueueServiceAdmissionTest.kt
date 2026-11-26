package io.github.lrq3000.utterlane.transcribe

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class QueueServiceAdmissionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun refusalBeforeOrAfterCopyAndProcessDeathOfferResumeOfSameStagedJob() {
        val root = temp.newFolder()
        val journal = QueueJournal(root)
        val service = QueueServiceAdmission()
        assertFalse(service.request { throw IllegalStateException("not allowed") })
        val job = journal.add("private-input", "original", false)
        assertTrue(service.status.value.needsResume(job))
        assertTrue(service.request { }) // Android accepted the request but refuses promotion in the Service.
        service.refused()
        assertTrue(service.status.value.needsResume(job))
        val restored = QueueServiceAdmission()
        val staged = QueueJournal(root).records().single()
        assertEquals(job, staged)
        assertTrue(restored.status.value.needsResume(staged))
        assertTrue(restored.request { })
        restored.running()
        assertFalse(restored.status.value.needsResume(staged))
        assertEquals(listOf(job), QueueJournal(root).records())
    }
}
