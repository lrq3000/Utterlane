package io.github.lrq3000.utterlane.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.util.Log
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.settings.FloatingButtonSize
import io.github.lrq3000.utterlane.ui.theme.NativeBrandStyle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class FloatingMicService : Service() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(io.github.lrq3000.utterlane.settings.AppLanguage.wrap(base))

    companion object {
        private const val TAG = "FloatingMicService"
        const val ACTION_STOP = "io.github.lrq3000.utterlane.STOP_SERVICE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var micButton: ImageView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var microphoneSession: MicrophoneSession? = null
    private var recordingOverlay: io.github.lrq3000.utterlane.ui.RecordingOverlay? = null

    private val isRecording = AtomicBoolean(false)
    private var themeMode = SettingsRepository.THEME_SYSTEM
    private var isIntentionalStop = false
    private var preferredDiameterDp = FloatingButtonSize.DEFAULT_DP
    private var floatingAttached = false
    private var legacyInsets = Insets.NONE
    private lateinit var touchListener: FloatingControlTouchListener
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (floatingAttached) {
                touchListener.cancel()
                updateFloatingLayout()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Check overlay permission before setting up floating view
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingView()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If restarted by system (intent is null), don't restart - user must explicitly enable
        if (intent == null) {
            Log.i(TAG, "Service restarted by system, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                isIntentionalStop = true
                stopSelf()
                return START_NOT_STICKY
            }
        }

        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(UtterlaneApp.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(UtterlaneApp.NOTIFICATION_ID, notification)
            }
            // Dismiss any failure notification from previous boot attempt
            getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(UtterlaneApp.SERVICE_ALERT_NOTIFICATION_ID)
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+ blocks microphone FGS from BOOT_COMPLETED or background
            Log.w(TAG, "Cannot start foreground service from boot/background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            // Missing RECORD_AUDIO permission for microphone FGS type
            Log.w(TAG, "Missing permission for foreground service", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            // Race condition: app went to background before startForeground completed
            Log.w(TAG, "Cannot start foreground service, app in background", e)
            showFailureNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY  // Don't auto-restart
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Only an explicit Stop disables the preference; unexpected teardown keeps recovery intact.
        if (isIntentionalStop) {
            UtterlaneApp.instance.applicationScope.launch {
                UtterlaneApp.instance.settingsRepository.setServiceEnabled(false)
            }
        }
        microphoneSession?.cancel(discard = false)
        microphoneSession = null
        hideCapturePanel()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        if (floatingAttached) {
            floatingAttached = false
            windowManager.removeView(floatingView)
        }
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams")
    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_mic, null)
        micButton = floatingView.findViewById(R.id.floating_mic_button)
        updateMicButtonState()
        serviceScope.launch {
            UtterlaneApp.instance.settingsRepository.themeMode.collect { mode ->
                themeMode = mode
                updateMicButtonState()
            }
        }

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        // Default position - will be updated async once loaded from settings
        val defaultX = 100
        val defaultY = 300

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultX
            y = defaultY
            // Use the full-display frame, then account for bars/cutouts in one place.
            // Otherwise WindowManager and our clamp can each subtract the same inset.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        floatingView.layoutDirection = resources.configuration.layoutDirection
        touchListener = FloatingControlTouchListener(
            ViewConfiguration.get(this).scaledTouchSlop,
            position = { layoutParams.x to layoutParams.y },
            diameterDp = { micButton.layoutParams.width / resources.displayMetrics.density },
            isRtl = { floatingView.layoutDirection == View.LAYOUT_DIRECTION_RTL },
            onMove = { x, y -> layoutParams.x = x; layoutParams.y = y; updateFloatingLayout() },
            onResize = { dp -> preferredDiameterDp = FloatingButtonSize.bounded(dp); updateFloatingLayout() },
            onFinish = { resized -> persistFloatingGeometry(resized) }
        )
        micButton.setOnClickListener { toggleRecording() }
        micButton.setOnTouchListener(touchListener)
        // Do not let a late asynchronous restore override the user's first drag.
        micButton.isEnabled = false
        ViewCompat.setOnApplyWindowInsetsListener(floatingView) { _, insets ->
            legacyInsets = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            updateFloatingLayout()
            insets
        }
        updateFloatingLayout()
        windowManager.addView(floatingView, layoutParams)
        floatingAttached = true
        ViewCompat.requestApplyInsets(floatingView)

        // Observe presentation continuously: neither the service nor its capture
        // session is restarted when a preset or custom diameter changes.
        serviceScope.launch {
            val (savedX, savedY) = UtterlaneApp.instance.settingsRepository.buttonPosition.first()
            if (savedX != -1) layoutParams.x = savedX
            if (savedY != -1) layoutParams.y = savedY
            UtterlaneApp.instance.settingsRepository.floatingButtonSizeDp.distinctUntilChanged().collect { dp ->
                // A settings edit supersedes an unfinished gesture, without writing
                // that older gesture back over the newly selected preference.
                if (dp != preferredDiameterDp) touchListener.cancel(persist = false)
                preferredDiameterDp = dp
                updateFloatingLayout()
                micButton.isEnabled = true
            }
        }
    }

    private fun persistFloatingGeometry(resized: Boolean) {
        val x = layoutParams.x
        val y = layoutParams.y
        val dp = preferredDiameterDp
        serviceScope.launch {
            val settings = UtterlaneApp.instance.settingsRepository
            if (resized) settings.setFloatingButtonSizeDp(dp)
            settings.setButtonPosition(x, y)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayGeometry(): FloatingControlGeometry {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return FloatingControlGeometry(metrics.bounds.width(), metrics.bounds.height(), insets.left, insets.top, insets.right, insets.bottom)
        }
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return FloatingControlGeometry(metrics.widthPixels, metrics.heightPixels,
            legacyInsets.left, legacyInsets.top, legacyInsets.right, legacyInsets.bottom)
    }

    private fun updateFloatingLayout() {
        val density = resources.displayMetrics.density
        val padding = (4 * density).roundToInt()
        floatingView.setPadding(padding, padding, padding, padding)
        val geometry = displayGeometry()
        val diameter = geometry.diameterPx(preferredDiameterDp, density, 2 * padding, 2 * padding)
        if (micButton.layoutParams.width != diameter || micButton.layoutParams.height != diameter) {
            micButton.layoutParams = micButton.layoutParams.apply { width = diameter; height = diameter }
        }
        layoutParams.width = diameter + 2 * padding
        layoutParams.height = diameter + 2 * padding
        // Window gravity is not a View layout direction: resolve START explicitly
        // so the system frame and our start-relative drag/clamp use the same edge.
        layoutParams.gravity = Gravity.getAbsoluteGravity(Gravity.TOP or Gravity.START, floatingView.layoutDirection)
        val (x, y) = geometry.clampPosition(layoutParams.x, layoutParams.y, layoutParams.width, layoutParams.height,
            floatingView.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        layoutParams.x = x
        layoutParams.y = y
        if (floatingAttached) windowManager.updateViewLayout(floatingView, layoutParams)
    }

    private fun initializeRecognizer() {
        Log.i(TAG, "initializeRecognizer called")
        serviceScope.launch(Dispatchers.IO) {
            val recognizerManager = UtterlaneApp.instance.recognizerManager
            val modelManager = UtterlaneApp.instance.modelManager
            Log.i(TAG, "Model ready: ${modelManager.isModelReady()}, recognizer ready: ${recognizerManager.isInitialized()}")

            if (modelManager.isModelReady() && !recognizerManager.isInitialized()) {
                val success = recognizerManager.initialize()
                Log.i(TAG, "Recognizer initialization: $success")
            }
        }
    }

    private fun toggleRecording() {
        Log.i(TAG, "toggleRecording called, isRecording=${isRecording.get()}")
        if (isRecording.get()) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (microphoneSession != null) { showToast(getString(R.string.state_processing)); return }
        if (MicrophoneSession.isBusy()) { showToast(getString(R.string.stream_busy)); return }
        // Check mic permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted")
            android.widget.Toast.makeText(this, getString(R.string.toast_mic_required), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val recognizerManager = UtterlaneApp.instance.recognizerManager
        Log.i(TAG, "startRecording called, recognizer ready=${recognizerManager.isInitialized()}")

        if (!recognizerManager.isInitialized()) {
            Log.i(TAG, "Capture session will initialize the selected model")
        }

        isRecording.set(true)
        updateMicButtonState()

        val target = TextInjectionService.instance?.captureStreamingTarget() ?: StreamingTextTarget(this) { false }
        recordingOverlay = io.github.lrq3000.utterlane.ui.RecordingOverlay(this).apply {
            onDoneClick = { if (isRecording.get()) stopRecording() }
            onCancelClick = {
                target.close()
                microphoneSession?.cancel(); microphoneSession = null; isRecording.set(false)
                updateMicButtonState(); hideCapturePanel()
            }
            show()
        }
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, serviceScope,
            onText = { delta, store -> target.accept(store); recordingOverlay?.setStatus(delta) },
            onComplete = { store, error ->
                isRecording.set(false); microphoneSession = null; updateMicButtonState()
                hideCapturePanel()
                target.finish(store, preserve = error != null)
                error?.let { showToast(it.message); store?.let { result -> TranscriptRecovery.show(this, result) } }
            }, onCaptureEnded = { isRecording.set(false); updateMicButtonState() }, onWarning = { showToast(it) },
            onSessionClosed = { target.sessionClosed() })
        Log.i(TAG, "Starting incremental audio recording")
        microphoneSession?.let { recordingOverlay?.bind(serviceScope, it); it.start() }
    }

    private fun stopRecording() {
        Log.i(TAG, "stopRecording called")
        isRecording.set(false)
        updateMicButtonState()

        microphoneSession?.stop()
    }

    private fun updateMicButtonState() {
        val recording = isRecording.get()
        micButton.setImageResource(
            if (recording) R.drawable.ic_mic_recording else R.drawable.ic_mic
        )
        if (recording) micButton.setBackgroundResource(R.drawable.mic_button_recording_bg)
        else micButton.background = NativeBrandStyle.waveform(
            NativeBrandStyle.palette(this, themeMode), 0f, oval = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::micButton.isInitialized) {
            touchListener.cancel()
            floatingView.layoutDirection = resources.configuration.layoutDirection
            updateFloatingLayout()
            updateMicButtonState()
            ViewCompat.requestApplyInsets(floatingView)
        }
    }

    private fun showFailureNotification() {
        UtterlaneApp.instance.serviceAlertNotification.show(R.string.service_start_floating_mic)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingMicService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, SettingsActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, UtterlaneApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_utterlane_notification)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun hideCapturePanel() { recordingOverlay?.hide(); recordingOverlay = null }
}
