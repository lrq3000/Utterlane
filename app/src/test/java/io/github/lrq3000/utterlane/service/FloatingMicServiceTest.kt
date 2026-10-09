package io.github.lrq3000.utterlane.service

import android.app.Application
import android.os.Looper
import android.view.Gravity
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
@Config(application = Application::class, sdk = [28, 31, 36])
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

    @Test fun rtlUsesRightAnchoredCoordinatesAndFollowsTheFinger() {
        val root = ReflectionHelpers.getField<View>(service, "floatingView")
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "layoutParams")
        params.x = 150
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateFloatingLayout")
        assertEquals(Gravity.RIGHT, params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        val start = params.x
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, 60f, 20f)
        assertEquals(start - 40, params.x)
        touch(MotionEvent.ACTION_UP, 60f, 20f)
        verify(exactly = 0) { session.stop() }
    }

    @Test fun motionWithinSlopDoesNotMoveTheWindowAndRemainsATap() {
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "layoutParams")
        val start = params.x to params.y
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, 21f, 21f)
        assertEquals(start, params.x to params.y)
        touch(MotionEvent.ACTION_UP, 21f, 21f)
        verify(exactly = 1) { session.stop() }
    }

    @Test fun replacingAPinchPointerRebasesInsteadOfJumpingAndNeverStartsADrag() = runBlocking {
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "layoutParams")
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        multiTouch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20f, 60f)
        multiTouch(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0 to 20f, 1 to 60f, 2 to 80f))
        multiTouch(MotionEvent.ACTION_POINTER_UP, listOf(0 to 20f, 1 to 60f, 2 to 80f))
        multiTouch(MotionEvent.ACTION_MOVE, listOf(1 to 60f, 2 to 90f)) // Replacement pair grows 20 -> 30px.
        multiTouch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(1 to 60f, 2 to 90f))
        val start = params.x to params.y
        touch(MotionEvent.ACTION_MOVE, 120f, 120f)
        touch(MotionEvent.ACTION_UP, 120f, 120f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(start, params.x to params.y)
        assertEquals(84, repository.floatingButtonSizeDp.first())
        verify(exactly = 0) { session.stop() }
    }

    @Test fun restoredPositionAndResizedWindowFitInsideTheDisplay() = runBlocking {
        controller.destroy()
        repository.setButtonPosition(Int.MAX_VALUE, Int.MAX_VALUE)
        repository.setFloatingButtonSize("large")
        controller = Robolectric.buildService(FloatingMicService::class.java).create()
        service = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "layoutParams")
        val manager = service.getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics)
        assertTrue(params.x >= 0 && params.y >= 0)
        assertTrue(params.x + params.width <= metrics.widthPixels)
        assertTrue(params.y + params.height <= metrics.heightPixels)
        repository.setFloatingButtonSizeDp(144)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(params.x + params.width <= metrics.widthPixels)
        assertTrue(params.y + params.height <= metrics.heightPixels)
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
        multiTouch(action, listOf(0 to x0, 1 to x1))
    }

    private fun multiTouch(action: Int, pointers: List<Pair<Int, Float>>) {
        val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = pointers.map { (_, x) -> MotionEvent.PointerCoords().apply { this.x = x; y = 20f; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(0, 20, action, pointers.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
