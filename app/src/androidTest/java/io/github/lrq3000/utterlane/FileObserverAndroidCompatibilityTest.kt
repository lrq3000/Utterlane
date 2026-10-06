package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.transcribe.AudioMonitorService
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Run on the supported pre-29 API family as well as newer Android versions. */
@RunWith(AndroidJUnit4::class)
class FileObserverAndroidCompatibilityTest {
    @Test fun serviceCanConstructAndStartItsLocalFolderObserver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "onboarding-observer-${UUID.randomUUID()}").apply { check(mkdir()) }
        val service = AudioMonitorService()
        try {
            // Isolate actual observer construction from foreground-service
            // startup, so a missing platform constructor is a test failure
            // instead of killing the instrumentation process on its Main thread.
            AudioMonitorService::class.java.getDeclaredMethod("createFileObserver", File::class.java)
                .apply { isAccessible = true }.invoke(service, directory)
        } finally {
            AudioMonitorService::class.java.getDeclaredMethod("stopMonitoring").apply { isAccessible = true }.invoke(service)
            directory.delete()
        }
    }
}
