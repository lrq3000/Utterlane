package io.github.lrq3000.utterlane.util

import android.app.Application
import android.content.res.Configuration
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StorageFailureTest {
    @Test fun structuredErrnoSurvivesSeveralWrappers() {
        val errno = ErrnoException("write", OsConstants.ENOSPC)
        assertTrue(StorageFailure.isFull(errno))
        assertTrue(StorageFailure.isFull(IllegalStateException("Save failed", IOException("IO failed", errno))))
        assertFalse(StorageFailure.isFull(ErrnoException("write", OsConstants.EACCES)))
        // A function/path called ENOSPC is not stronger evidence than an actual errno.
        assertFalse(StorageFailure.isFull(ErrnoException("ENOSPC", OsConstants.EIO)))
    }

    @Test fun fallbackRequiresAnExplicitErrnoTokenOrTheCompleteOsPhrase() {
        listOf("write failed: ENOSPC", "No space left on device", "save: no SPACE left on DEVICE (28)").forEach {
            assertTrue(it, StorageFailure.isFull(IOException(it)))
        }
        listOf("IO error", "Disk quota exceeded", "Out of memory", "No space left in buffer",
            "Cannot open ENOSPC_backup.wav", "Permission denied", "Storage unavailable").forEach {
            assertFalse(it, StorageFailure.isFull(IOException(it)))
        }
        assertFalse(StorageFailure.isFull(IOException()))
    }

    @Test(timeout = 1000) fun cyclicCausesTerminateAndStillFindNestedFullErrors() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertFalse(StorageFailure.isFull(first))
        val full = IOException("ENOSPC")
        val wrapper = IOException("wrapper", full)
        full.initCause(wrapper)
        assertTrue(StorageFailure.isFull(wrapper))
    }

    @Test fun causeTraversalUsesIdentityRatherThanThrowableEquality() {
        val inner = EqualError("ENOSPC")
        assertTrue(StorageFailure.isFull(EqualError("wrapper").also { it.initCause(inner) }))
    }

    @Test fun messageIsActionableLocalizedAndNullForOtherFailures() {
        val context = RuntimeEnvironment.getApplication()
        val error = IOException("ENOSPC")
        val english = StorageFailure.userMessage(context, error)
        assertEquals("Not enough storage space. Free up some space and try again.", english)
        assertNull(StorageFailure.userMessage(context, IOException("Other failure")))
        val locales = "bg cs da de el es et fi fr hr hu it lt lv mt nl pl pt ro sk sl sv uk".split(' ')
        for (language in locales) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = StorageFailure.userMessage(context.createConfigurationContext(configuration), error)
            assertFalse(language, localized.isNullOrBlank())
            assertNotEquals("Missing $language translation", english, localized)
        }
    }

    private class EqualError(message: String) : IOException(message) {
        override fun equals(other: Any?) = other is EqualError
        override fun hashCode() = 0
    }
}
