package io.github.lrq3000.utterlane.asr

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DictionaryDefaultsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun correctsOnlyTheStandaloneProductName() {
        val context = context()
        assertEquals("Utterlane, Utterlane; not myutterlane.", DictionaryManager(context)
            .applyReplacements("utterlane, UTTERLANE; not myutterlane."))
    }

    @Test fun removingTheDefaultDoesNotBringItBackOnReload() = runBlocking {
        val context = context()
        DictionaryManager(context).removeRule("utterlane")
        val reloaded = DictionaryManager(context)
        assertEquals("utterlane", reloaded.applyReplacements("utterlane"))
        assertEquals(emptyList<DictionaryManager.ReplacementRule>(), reloaded.rules.value)
    }

    @Test fun savedDictionaryIsAuthoritative() {
        val context = context()
        File(context.filesDir, "replacements.json").writeText("""
            {"version":1,"rules":[{"from":"utterlane","to":"My chosen spelling"}]}
        """.trimIndent())
        assertEquals("My chosen spelling", DictionaryManager(context).applyReplacements("utterlane"))
    }

    private fun context() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = temporary.newFolder()
        override fun getFilesDir(): File = root
    }
}
