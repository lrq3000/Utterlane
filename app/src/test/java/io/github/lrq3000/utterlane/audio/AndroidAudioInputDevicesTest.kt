package io.github.lrq3000.utterlane.audio

import android.app.Application
import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 36])
class AndroidAudioInputDevicesTest {
    private val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    @Test fun modernBluetoothChoicesAreCommunicationEndpointsEvenWhenAddressesDiffer() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "input-address", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, sco, "output-address"))
            val candidates = platform.devices.inputs().filter { it.bluetooth }
            assertEquals("A raw source with no communication ID cannot be activated by the modern router", 1, candidates.size)
            assertEquals(8, candidates.single().communicationId)
            assertNull("Different known addresses are not the same headset", candidates.single().inputId)
        }
    }

    @Test fun uniqueSourceWithHiddenAddressPairsWithoutCreatingDuplicateChoice() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, sco, "headset"))
            val candidates = platform.devices.inputs().filter { it.bluetooth }
            assertEquals(1, candidates.size)
            assertEquals(7, candidates.single().inputId)
        }
    }

    @Test fun twoAddresslessEndpointsCannotBothClaimTheSameSource() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, sco, ""), AudioRoutingPlatform.device(9, sco, ""))
            val candidates = platform.devices.inputs().filter { it.bluetooth }
            assertEquals(2, candidates.size)
            assertTrue("An ambiguous source must remain unassigned", candidates.all { it.inputId == null })
            assertEquals(2, candidates.map { it.key }.distinct().size)
        }
    }

    @Test fun duplicateKnownAddressesAreAlsoAmbiguous() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "same", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, sco, "same"), AudioRoutingPlatform.device(9, sco, "same"))
            val candidates = platform.devices.inputs().filter { it.bluetooth }
            assertEquals("Duplicate address metadata must not erase a connected choice", 2, candidates.size)
            assertTrue(candidates.all { it.inputId == null })
            assertEquals(2, candidates.map { it.key }.distinct().size)
        }
    }

    @Test fun matchExactAddressesBeforePairingUniqueUnmatchedPorts() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "a", true), AudioRoutingPlatform.device(10, sco, "b", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, sco, "a"), AudioRoutingPlatform.device(9, sco, ""))
            val candidates = platform.devices.inputs().filter { it.bluetooth }.associateBy { it.communicationId }
            assertEquals(setOf(8, 9), candidates.keys)
            assertEquals(7, candidates.getValue(8).inputId)
            assertEquals(10, candidates.getValue(9).inputId)
        }
    }

    @Test fun unrouteableRawBluetoothAndOutputOnlySpeakersDoNotBecomeChoices() {
        AudioRoutingPlatform().use { platform ->
            val usb = AudioRoutingPlatform.device(11, AudioDeviceInfo.TYPE_USB_HEADSET, "usb", true)
            platform.inputDevices = listOf(platform.phone, usb, AudioRoutingPlatform.device(7, sco, "headset", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "speaker"))
            val candidates = platform.devices.inputs()
            assertEquals(2, candidates.size)
            assertTrue(candidates.any { it.isPhone })
            assertEquals(11, candidates.single { !it.isPhone }.inputId)
        }
    }

    @Test fun stableAddressRetainsKeyWhenAndroidReassignsBothPorts() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "headset", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(8, sco, "headset"))
            val original = platform.devices.inputs().single { it.bluetooth }
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(70, sco, "headset", true))
            platform.communicationDevices = listOf(AudioRoutingPlatform.device(80, sco, "headset"))
            val reconnected = platform.devices.inputs().single { it.bluetooth }
            assertEquals(original.key, reconnected.key)
            assertEquals(70, reconnected.inputId)
            assertEquals(80, reconnected.communicationId)
        }
    }

    @Test @Config(sdk = [28]) fun legacyBluetoothStillUsesRawInputPorts() {
        AudioRoutingPlatform().use { platform ->
            platform.inputDevices = listOf(platform.phone, AudioRoutingPlatform.device(7, sco, "headset", true))
            val input = platform.devices.inputs().single { it.bluetooth }
            assertEquals(7, input.inputId)
            assertNull(input.communicationId)
        }
    }
}
