package io.github.lrq3000.utterlane.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/** Audio-policy inventory needs no Bluetooth scan, pairing, or location permission. */
class AndroidAudioInputDevices(context: Context) : AudioInputDevices {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val processIdentity = UUID.randomUUID().toString()
    private var callback: AudioDeviceCallback? = null

    override fun inputs(): List<AudioInput> {
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val result = linkedMapOf<String, AudioInput>()
        val phone = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        result[AudioInput.PHONE_KEY] = AudioInput(AudioInput.PHONE_KEY, "", false, phone?.id)
        for (device in inputs) {
            if (!isSelectable(device.type)) continue
            val key = key(device)
            result[key] = AudioInput(key, device.productName.toString(), isBluetooth(device.type), device.id)
        }
        // Some Bluetooth inputs appear only after the communication link activates.
        // Available communication sinks are therefore also candidates, not paired
        // devices or arbitrary A2DP speakers. Android selects their matching source.
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val sourcesByAddress = inputs.associateBy { it.type to it.address }
                val soleSourcesByType = inputs.groupBy { it.type }.mapValues { it.value.singleOrNull() }
                for (device in manager.availableCommunicationDevices) {
                    if (!isBluetooth(device.type)) continue
                    val key = key(device)
                    val source = if (device.address.isNotBlank()) sourcesByAddress[device.type to device.address]
                        else soleSourcesByType[device.type]
                    source?.let { result.remove(key(it)) }
                    result[key] = AudioInput(key, device.productName.toString(), true, source?.id, device.id)
                }
            } catch (e: SecurityException) { Log.w("AudioInputDevices", "Communication inventory unavailable; retaining microphone inputs", e) }
        }
        return result.values.toList()
    }

    private fun key(device: AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= 28) device.address else ""
        // A name can identify several identical headsets. If Android supplies no
        // stable address, selection lasts only for this process/port, not a guessed
        // identity restored across reboot. Addresses never enter logs or captions.
        if (address.isBlank()) return "port:$processIdentity:${device.id}"
        val digest = MessageDigest.getInstance("SHA-256").digest("${device.type}:$address".toByteArray())
        return "device:" + digest.joinToString("") { "%02x".format(it) }
    }

    override fun observe(onChanged: () -> Unit) {
        check(callback == null)
        callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
        }.also { manager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
    }

    override fun close() { callback?.let(manager::unregisterAudioDeviceCallback); callback = null }

    companion object {
        fun isBluetooth(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

        private fun isSelectable(type: Int): Boolean = isBluetooth(type) || type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)
    }
}
