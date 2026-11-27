package io.github.lrq3000.utterlane.service

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.MemoryStore
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.transcribe.TranscriptionQueue
import io.github.lrq3000.utterlane.transcribe.TranscriptionQueueService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val session = mockk<StreamingDictationOverlay>(relaxed = true)
    private val queueForeground = MutableStateFlow<TranscriptionQueueService?>(null)
    private lateinit var controller: ServiceController<FloatingMicService>
    private lateinit var service: FloatingMicService
    private lateinit var button: View

    @Before fun create() {
        val app = mockk<UtterlaneApp>()
        every { app.settingsRepository } returns repository
        every { app.transcriptionQueue } returns mockk<TranscriptionQueue> {
            every { foreground } returns queueForeground
        }
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

    @Test fun persistedDiameterUpdatesExistingViewWithoutClosingSession() = runBlocking {
        repository.setFloatingButtonSizeDp(72)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals((72 * service.resources.displayMetrics.density).toInt(), button.layoutParams.width)
        assertSame(button, ReflectionHelpers.getField<View>(service, "micButton"))
        assertSame(session, ReflectionHelpers.getField<StreamingDictationOverlay>(service, "microphoneSession"))
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

    @Test fun recordingRecolorsTheOvalWithoutReplacingTheMicGlyph() {
        val image = button as ImageView
        val glyph = image.drawable
        val idle = gradientColors().toList()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateMicButtonState")
        assertSame("The inner mic glyph must remain unchanged", glyph, image.drawable)
        val red = gradientColors()
        assertTrue(red.distinct().size > 1)
        assertTrue(red.all { Color.red(it) > Color.green(it) && Color.red(it) > Color.blue(it) })
        assertNotEquals(idle, red.toList())
    }

    @Test @Config(sdk = [36]) fun savingAfterATapShowsRainbowThenRestoresIdleGradient() {
        val idle = gradientColors().toList()
        tapAndConfirmFinalization(success = true)
        val animation = checkNotNull(saveAnimation()) { "Successful tap-stop must start feedback" }
        animation.setCurrentFraction(.25f)
        assertNotEquals("An intermediate save frame must not be idle", idle, gradientColors().toList())
        animation.setCurrentFraction(.66f)
        assertTrue(Color.blue(gradientColors().first()) > Color.green(gradientColors().first()))
        animation.end()
        assertEquals(idle, gradientColors().toList())
    }

    @Test @Config(sdk = [36]) fun failedFinalizationDoesNotPlaySuccessFeedback() {
        val idle = gradientColors().toList()
        tapAndConfirmFinalization(success = false)
        assertNull(saveAnimation())
        assertEquals(idle, gradientColors().toList())
    }

    @Test @Config(sdk = [36]) fun earlierSaveAnimationCannotRecolorANewTake() {
        tapAndConfirmFinalization(success = true)
        val animation = checkNotNull(saveAnimation())
        animation.setCurrentFraction(.15f)
        ReflectionHelpers.getField<AtomicBoolean>(service, "isRecording").set(true)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateMicButtonState")
        val recording = gradientColors().toList()
        assertNull(saveAnimation())
        animation.end()
        assertEquals(recording, gradientColors().toList())
    }

    private fun gradientColors(): IntArray = ((button.background as RippleDrawable).getDrawable(0) as GradientDrawable).colors!!

    private fun saveAnimation(): ValueAnimator? =
        ReflectionHelpers.getField(ReflectionHelpers.getField<Any>(service, "appearance"), "feedback")

    private fun tapAndConfirmFinalization(success: Boolean) {
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onDictationFinalized", ReflectionHelpers.ClassParameter.from(Boolean::class.java, success))
    }

    @Test fun droppingIdleMicOnBottomTargetStopsOnlyItsServiceAndKeepsPreferenceAndPosition() = runBlocking {
        repository.setServiceEnabled(true)
        repository.setButtonPosition(100, 300)
        ReflectionHelpers.setField(service, "microphoneSession", null)
        ReflectionHelpers.getField<AtomicBoolean>(service, "isRecording").set(false)
        dragToDismiss(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("A drop should stop the floating service", shadowOf(service).isStoppedBySelf)
        assertTrue(repository.serviceEnabled.first())
        assertEquals(100 to 300, repository.buttonPosition.first())
    }

    @Test fun cancellingDragOnBottomTargetDoesNotHideTheMic() {
        dragToDismiss(MotionEvent.ACTION_CANCEL)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        verify(exactly = 0) { session.stop() }
    }

    @Test fun droppingDuringDictationStopsAcceptingTapsWithoutTearingDownItsOwner() {
        val accepted = slot<() -> Unit>()
        every { session.stopInBackground(capture(accepted)) } returns Unit
        dragToDismiss(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("No new taps while transferring the active take", button.isEnabled)
        assertFalse("Keep the foreground owner until handoff or completion", shadowOf(service).isStoppedBySelf)
        accepted.captured()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A durable row alone is not a foreground owner", shadowOf(service).isStoppedBySelf)
        queueForeground.value = mockk()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Release only the floating service after the queue attaches", shadowOf(service).isStoppedBySelf)
        verify(exactly = 0) { session.cancel(any()) }
    }

    @Test fun pinchResizesWithoutTogglingAndRestoresCustomDpAfterServiceRecreation() = runBlocking {
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
        controller.destroy()
        controller = Robolectric.buildService(FloatingMicService::class.java).create()
        service = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val restored = ReflectionHelpers.getField<View>(service, "micButton")
        assertEquals((84 * service.resources.displayMetrics.density).toInt(), restored.layoutParams.width)
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
        repository.setFloatingButtonSizeDp(72)
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

    private fun dragToDismiss(end: Int) {
        val params = ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "layoutParams")
        val manager = service.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION") val metrics = android.util.DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }
        val density = service.resources.displayMetrics.density
        val x = 20f + metrics.widthPixels / 2f - params.x - params.width / 2f
        val y = 20f + metrics.heightPixels - 48 * density - params.y - params.height / 2f
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, x, y)
        touch(end, x, y)
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
