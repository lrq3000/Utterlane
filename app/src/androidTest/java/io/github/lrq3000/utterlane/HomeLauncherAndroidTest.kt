package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the installed launcher contract, including the isolated QA identity. */
@RunWith(AndroidJUnit4::class)
class HomeLauncherAndroidTest {
    @Test fun launcherStartsTheRecordingWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("The application must have a launcher entry", intent)
        assertEquals("Opening Utterlane must lead to its recording workspace",
            "io.github.lrq3000.utterlane.home.HomeActivity", intent!!.component!!.className)
    }
}
