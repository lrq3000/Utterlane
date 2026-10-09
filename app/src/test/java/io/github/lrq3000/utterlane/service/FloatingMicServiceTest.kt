package io.github.lrq3000.utterlane.service

import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.settings.MemoryStore
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise the actual service's View and preference collector without native recognition. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FloatingMicServiceTest {
    private val repository = SettingsRepository(MemoryStore())
    private val session = mockk<MicrophoneSession>(relaxed = true)
    private lateinit var controller: ServiceController<FloatingMicService>
    private lateinit var service: FloatingMicService
    private lateinit var button: View

    @Before fun create() {
        val app = mockk<UtterlaneApp>()
        every { app.settingsRepository } returns repository
        ReflectionHelpers.setStaticField(UtterlaneApp::class.java, "instance", app)
        ShadowSettings.setCanDrawOverlays(true)
        controller = Robolectric.buildService(FloatingMicService::class.java).create()
        service = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        button = ReflectionHelpers.getField(service, "micButton")
        ReflectionHelpers.setField(service, "microphoneSession", session)
        ReflectionHelpers.getField<AtomicBoolean>(service, "isRecording").set(true)
    }

    @After fun destroy() { controller.destroy() }

    @Test fun presetChangeUpdatesExistingViewWithoutClosingSession() = runBlocking {
        repository.setFloatingButtonSize(SettingsRepository.BUTTON_SIZE_LARGE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals((72 * service.resources.displayMetrics.density).toInt(), button.layoutParams.width)
        assertSame(button, ReflectionHelpers.getField<View>(service, "micButton"))
        assertSame(session, ReflectionHelpers.getField<MicrophoneSession>(service, "microphoneSession"))
        verify(exactly = 0) { session.stop() }
        verify(exactly = 0) { session.cancel(any()) }
    }

    @Test fun dragReturningToStartMustNotStopRecording() {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, 100f, 100f)
        touch(MotionEvent.ACTION_MOVE, 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        verify(exactly = 0) { session.stop() }
    }

    @Test fun cancelledGestureCannotBecomeARecordingTapOnLaterUp() {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_CANCEL, 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        verify(exactly = 0) { session.stop() }
    }

    @Test fun deliberateTapStillStopsRecording() {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        verify(exactly = 1) { session.stop() }
    }

    @Test fun pinchResizesWithoutTogglingAndPersistsCustomDpAfterLastPointer() = runBlocking {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        multiTouch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20f, 60f)
        multiTouch(MotionEvent.ACTION_MOVE, 10f, 70f)
        multiTouch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10f, 70f)
        touch(MotionEvent.ACTION_MOVE, 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals((84 * service.resources.displayMetrics.density).toInt(), button.layoutParams.width)
        assertEquals(84, repository.floatingButtonSizeDp.first())
        verify(exactly = 0) { session.stop() }
    }

    @Test fun coincidentSecondPointerStillSuppressesTap() {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        multiTouch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20f, 20f)
        multiTouch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        verify(exactly = 0) { session.stop() }
    }

    @Test fun dragCannotLeaveTheUsableDisplay() {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, 20000f, 20000f)
        touch(MotionEvent.ACTION_UP, 20000f, 20000f)
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "layoutParams")
        assertTrue(params.x < service.resources.displayMetrics.widthPixels)
        assertTrue(params.y < service.resources.displayMetrics.heightPixels)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 10, action, x, y, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun multiTouch(action: Int, x0: Float, x1: Float) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(x0, x1).map { x -> MotionEvent.PointerCoords().apply { this.x = x; y = 20f; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(0, 20, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
