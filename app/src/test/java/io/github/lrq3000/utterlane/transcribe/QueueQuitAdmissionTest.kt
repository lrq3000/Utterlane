package io.github.lrq3000.utterlane.transcribe

import android.app.Application
import io.github.lrq3000.utterlane.UtterlaneApp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class QueueQuitAdmissionTest {
    @Test fun quitWaitsForAnAcceptedServiceStartEvenBeforeItOwnsAnyForegroundRows() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val app = mockk<UtterlaneApp> {
            every { noBackupFilesDir } returns context.noBackupFilesDir
            every { getSharedPreferences(any(), any()) } answers { context.getSharedPreferences(firstArg(), secondArg()) }
            every { applicationScope } returns appScope
        }
        val queue = TranscriptionQueue(app)
        assertTrue(queue.admission.request { /* Android accepted the start; its callback has not arrived. */ })
        queue.beginQuit()
        val idle = async(start = CoroutineStart.UNDISPATCHED) { queue.awaitQuitIdle() }
        try {
            repeat(10) { yield() }
            assertFalse("An unattached requested service still owns a pending start", idle.isCompleted)
            queue.admission.running()
            repeat(10) { yield() }
            assertFalse("Starting does not release the Quit barrier", idle.isCompleted)
            queue.admission.refused()
            withTimeout(1000) { idle.await() }
        } finally {
            idle.cancel()
            queue.endQuit()
            appScope.cancel()
        }
    }
}
