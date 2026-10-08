package io.github.lrq3000.utterlane.audio

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.lrq3000.utterlane.settings.MemoryStore
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Mock only the hardware/service boundary. Production inventory, routing,
 * preferences and policy code run against real SDK classes and Android loopers.
 * Request acceptance deliberately does NOT imply an observed route transition.
 */
internal class AudioRoutingPlatform : Closeable {
    val application: Application = RuntimeEnvironment.getApplication()
    val manager = mockk<AudioManager>(relaxed = true)
    val record = mockk<AudioRecord>(relaxed = true)
    val context = object : ContextWrapper(application) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.AUDIO_SERVICE) manager else super.getSystemService(name)
    }
    val phone = device(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "", source = true, name = "Phone")
    var inputDevices: List<AudioDeviceInfo> = listOf(phone)
    var communicationDevices: List<AudioDeviceInfo> = emptyList()
    var actualInput: AudioDeviceInfo? = phone
    var currentCommunication: AudioDeviceInfo? = null
    var mode = AudioManager.MODE_NORMAL
    var scoOn = false
    var acceptCommunication = true
    var acceptPreference = true
    var running = true
    var state = CaptureInputState()
    var preferred: AudioDeviceInfo? = null
    private var communicationChanged: (() -> Unit)? = null
    private var routingChanged: (() -> Unit)? = null
    private val deviceCallbacks = linkedMapOf<AudioDeviceCallback, Handler?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val settings = SettingsRepository(MemoryStore())
    val devices by lazy { AndroidAudioInputDevices(context) }
    private var selection: AudioInputController? = null
    private val routes = mutableListOf<AndroidCaptureRoute>()

    init {
        every { manager.getDevices(AudioManager.GET_DEVICES_INPUTS) } answers { inputDevices.toTypedArray() }
        every { manager.mode } answers { mode }
        every { manager.mode = any() } answers { mode = firstArg(); Unit }
        every { manager.isBluetoothScoOn } answers { scoOn }
        every { manager.isBluetoothScoOn = any() } answers { scoOn = firstArg(); Unit }
        every { manager.registerAudioDeviceCallback(any(), any()) } answers {
            deviceCallbacks[firstArg()] = secondArg(); Unit
        }
        every { manager.unregisterAudioDeviceCallback(any()) } answers { deviceCallbacks.remove(firstArg()); Unit }
        if (Build.VERSION.SDK_INT >= 31) {
            every { manager.availableCommunicationDevices } answers { communicationDevices }
            every { manager.communicationDevice } answers { currentCommunication }
            every { manager.setCommunicationDevice(any()) } answers { acceptCommunication }
            every { manager.addOnCommunicationDeviceChangedListener(any(), any()) } answers {
                val executor = firstArg<Executor>()
                val listener = secondArg<AudioManager.OnCommunicationDeviceChangedListener>()
                communicationChanged = { executor.execute { listener.onCommunicationDeviceChanged(currentCommunication) } }
            }
            every { manager.removeOnCommunicationDeviceChangedListener(any()) } answers { communicationChanged = null }
        }
        every { record.routedDevice } answers { actualInput }
        every { record.setPreferredDevice(any()) } answers { preferred = firstArg(); acceptPreference }
        every { record.addOnRoutingChangedListener(any(), any()) } answers {
            val listener = firstArg<AudioRouting.OnRoutingChangedListener>()
            val handler = secondArg<Handler?>()
            routingChanged = { if (handler == null) listener.onRoutingChanged(record)
                else handler.post { listener.onRoutingChanged(record) } }
        }
        every { record.removeOnRoutingChangedListener(any()) } answers { routingChanged = null }
    }

    fun controller(): AudioInputController = selection ?: AudioInputController(devices, settings, scope).also { selection = it }

    fun route(key: String = AudioInput.PHONE_KEY): AndroidCaptureRoute = runBlocking {
        val controller = controller()
        check(controller.select(key)) { "Test selected an unavailable input: $key" }
        AndroidCaptureRoute(context, controller, controller.snapshotForRecording(), { running }, { state = it })
            .also { routes.add(it) }
    }

    fun observeRoute(input: AudioDeviceInfo?, communication: AudioDeviceInfo? = currentCommunication) {
        actualInput = input
        currentCommunication = communication
        communicationChanged?.invoke()
        routingChanged?.invoke()
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun scoState(value: Int) {
        application.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, value))
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun changed(removed: List<AudioDeviceInfo> = emptyList(), added: List<AudioDeviceInfo> = emptyList()) {
        deviceCallbacks.toList().forEach { (callback, handler) ->
            val delivery = Runnable {
                if (removed.isNotEmpty()) callback.onAudioDevicesRemoved(removed.toTypedArray())
                if (added.isNotEmpty()) callback.onAudioDevicesAdded(added.toTypedArray())
            }
            if (handler == null) delivery.run() else handler.post(delivery)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    override fun close() {
        routes.forEach { it.detach(record); it.close() }
        selection?.close()
        scope.cancel()
    }

    companion object {
        fun device(id: Int, type: Int, address: String, source: Boolean = false, name: String = "Headset"): AudioDeviceInfo =
            mockk<AudioDeviceInfo> {
                every { getId() } returns id
                every { getType() } returns type
                every { getAddress() } returns address
                every { getProductName() } returns name
                every { isSource } returns source
                every { isSink } returns !source
            }
    }
}
