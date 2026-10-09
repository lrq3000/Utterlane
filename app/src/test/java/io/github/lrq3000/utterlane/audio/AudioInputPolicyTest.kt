package io.github.lrq3000.utterlane.audio

import org.junit.Assert.*
import org.junit.Test

class AudioInputPolicyTest {
    private val policy = AudioInputPolicy()
    private val phone = AudioInput(AudioInput.PHONE_KEY, "Phone", false, inputId = 1)
    private val headset = AudioInput("headset-a", "Headset", true, inputId = 7)
    private val other = AudioInput("headset-b", "Headset", true, inputId = 8)
    private val usb = AudioInput("usb", "USB", false, inputId = 9)

    @Test fun missingManualSelectionResetsAndDoesNotResurrectOnReconnect() {
        val missing = policy.reconcile(InputPreferences(headset.key, false), listOf(phone))
        assertEquals(InputPreferences(), missing)
        assertEquals(missing, policy.reconcile(missing, listOf(phone, headset)))
    }

    @Test fun automaticPreferenceSurvivesAnEmptyInventoryAndSelectsLaterConnection() {
        val missing = policy.reconcile(InputPreferences(headset.key, true), emptyList())
        assertEquals(InputPreferences(preferBluetooth = true), missing)
        assertEquals(headset.key, policy.reconcile(missing, listOf(phone, headset)).selectedKey)
    }

    @Test fun retainsChosenBluetoothAndUsesDeterministicReplacementWhenItDisappears() {
        val selected = InputPreferences(other.key, true)
        assertEquals(selected, policy.reconcile(selected, listOf(phone, headset, other)))
        assertEquals(headset.key, policy.reconcile(selected, listOf(phone, headset)).selectedKey)
        assertEquals(headset.key, policy.reconcile(InputPreferences(preferBluetooth = true), listOf(other, headset)).selectedKey)
    }

    @Test fun manualNonBluetoothChoiceDisablesAutomaticPreference() {
        val auto = InputPreferences(headset.key, true)
        assertEquals(InputPreferences(usb.key, false), policy.select(auto, usb))
        assertEquals(InputPreferences(), policy.select(auto, phone))
        assertEquals(InputPreferences(other.key, true), policy.select(auto, other))
    }

    @Test fun reconnectWithNewPortIdKeepsStableIdentityWhileConnectedAtStartup() {
        val saved = InputPreferences(headset.key, false)
        assertEquals(saved, policy.reconcile(saved, listOf(headset.copy(inputId = 777))))
    }

    @Test fun autoOffPreservesWiredInputAndAutoOnOverridesIt() {
        assertEquals(usb.key, policy.reconcile(InputPreferences(usb.key), listOf(phone, usb, headset)).selectedKey)
        assertEquals(headset.key, policy.reconcile(InputPreferences(usb.key, true), listOf(phone, usb, headset)).selectedKey)
    }
}
