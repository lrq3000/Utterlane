package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import org.junit.Assert.*
import org.junit.Test

class HomeFileOperationTest {
    @Test fun fileTranscriptionFailureAfterMicDenialRetainsPermissionIndependentRecovery() {
        val denied = HomeState(permissionDenied = true, message = "Microphone denied")
        val importing = denied.prepareFileOperation()
        assertFalse("A file operation must not inherit the microphone permission gate", importing.permissionDenied)
        assertNull(importing.message)
        assertTrue(importing.busy)
        val failed = importing.copy(preparing = false,
            result = TranscriptionDialogState(message = "Model unavailable"))
        assertFalse(failed.permissionDenied)
        assertFalse(failed.busy)
        assertEquals("Model unavailable", failed.result.message)
    }

    @Test fun fileServiceStartFailurePreservesThePreviousResultWithoutRevivingMicDenial() {
        val usefulResult = TranscriptionDialogState(preview = "previous usable words")
        val denied = HomeState(result = usefulResult, permissionDenied = true, message = "Microphone denied")
        val failed = denied.prepareFileOperation().copy(preparing = false, message = "Service start failed")
        assertFalse(failed.permissionDenied)
        assertFalse(failed.busy)
        assertSame("Preparation must not acknowledge useful previous work", usefulResult, failed.result)
        assertEquals("Service start failed", failed.message)
    }
}
