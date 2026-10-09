package io.github.lrq3000.utterlane.settings

import android.Manifest
import android.app.Application
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31, 36], application = Application::class)
class HfpPermissionStateTest {
    @Test fun onlyExplicitActionRequestsConnectAndLegacyNeverRequests() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val state = HfpPermissionState(app)
        val requested = mutableListOf<String>()
        state.refresh()
        assertEquals(Build.VERSION.SDK_INT < 31, state.granted)
        assertTrue(requested.isEmpty())
        state.requestIfNeeded { requested.add(it) }
        assertEquals(if (Build.VERSION.SDK_INT < 31) emptyList<String>()
            else listOf(Manifest.permission.BLUETOOTH_CONNECT), requested)
    }

    @Test fun deniedGrantAndRevocationAreRefreshedOnReturnFromSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val state = HfpPermissionState(app)
        state.onResult(false)
        assertTrue(state.denied)
        assertFalse(state.granted)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        state.refresh()
        assertTrue(state.granted)
        assertFalse(state.denied)
        state.requestIfNeeded { fail("An existing grant must not prompt") }
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        state.refresh()
        assertFalse(state.granted)
    }
}
