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
            // On modern Android only communication endpoints can activate a
            // Bluetooth route. Publishing unmatched raw sources as extra choices
            // creates entries the recorder cannot select with setCommunicationDevice.
            if (Build.VERSION.SDK_INT >= 31 && isBluetooth(device.type)) continue
            val key = key(device)
            result[key] = AudioInput(key, device.productName.toString(), isBluetooth(device.type), device.id)
        }
        // Some Bluetooth inputs appear only after the communication link activates.
        // Available communication sinks are therefore also candidates, not paired
        // devices or arbitrary A2DP speakers. Android selects their matching source.
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                communicationInputs(inputs, manager.availableCommunicationDevices).forEach { result[it.key] = it }
            } catch (e: SecurityException) { Log.w("AudioInputDevices", "Communication inventory unavailable; retaining non-Bluetooth inputs", e) }
        }
        return result.values.toList()
    }

    @androidx.annotation.RequiresApi(31)
    private fun communicationInputs(inputs: Array<AudioDeviceInfo>, devices: List<AudioDeviceInfo>): List<AudioInput> {
        val sources = inputs.filter { isBluetooth(it.type) }
        val sinks = devices.filter { isBluetooth(it.type) }
        val sourcesByAddress = sources.groupBy { it.type to it.address }
        val sinksByAddress = sinks.groupBy { it.type to it.address }
        val remainingSources = sources.associateBy { it.id }.toMutableMap()
        val remainingSinks = sinks.associateBy { it.id }.toMutableMap()
        val sourceIds = mutableMapOf<Int, Int>()
        fun pair(sink: AudioDeviceInfo, source: AudioDeviceInfo) {
            sourceIds[sink.id] = source.id
            remainingSources.remove(source.id)
            remainingSinks.remove(sink.id)
        }

        // First reserve unique exact matches. A port can belong to only one
        // endpoint; product names and duplicate address metadata are not proof.
        for (sink in sinks) {
            val identity = sink.type to sink.address
            val source = sourcesByAddress[identity]?.singleOrNull()
            if (sink.address.isNotBlank() && sinksByAddress[identity]?.size == 1 && source != null) pair(sink, source)
        }
        // Some OEMs hide an address on one side. Pair the remaining ports only
        // when both sides have exactly one candidate of that type and at least
        // one address is unknown. Different known addresses must never match.
        // These indexes keep the two-pass join O(devices), not O(inputs * sinks).
        val soleSourceByType = remainingSources.values.groupBy { it.type }.mapValues { it.value.singleOrNull() }
        val sinkCounts = remainingSinks.values.groupingBy { it.type }.eachCount()
        for (sink in remainingSinks.values.toList()) {
            val source = soleSourceByType[sink.type]
            if (sinkCounts[sink.type] == 1 && source != null && (sink.address.isBlank() || source.address.isBlank())) pair(sink, source)
        }
        return sinks.map { sink ->
            // Duplicate addresses cannot be durable identities either. Keep both
            // choices distinct for this process instead of overwriting one entry.
            val uniqueAddress = sinksByAddress[sink.type to sink.address]?.size == 1
            AudioInput(key(sink, uniqueAddress), sink.productName.toString(), true, sourceIds[sink.id], sink.id)
        }
    }

    private fun key(device: AudioDeviceInfo, uniqueAddress: Boolean = true): String {
        val address = if (Build.VERSION.SDK_INT >= 28) device.address else ""
        // A name can identify several identical headsets. If Android supplies no
        // stable address, selection lasts only for this process/port, not a guessed
        // identity restored across reboot. Addresses never enter logs or captions.
        if (address.isBlank() || !uniqueAddress) return "port:$processIdentity:${device.id}"
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
