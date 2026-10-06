package io.github.lrq3000.utterlane

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.File

/** Shared real-window inspection; no guessed tap coordinates or fake UI state. */
internal class OnboardingTestUi {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    fun prepare() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    private fun roots(): List<AccessibilityNodeInfo> = instrumentation.uiAutomation.windows.mapNotNull { it.root }
        .filter { root ->
            val belongs = root.packageName?.toString() == app.packageName
            @Suppress("DEPRECATION")
            if (!belongs) root.recycle()
            belongs
        }

    fun node(id: String): AccessibilityNodeInfo = awaitNode(id) { it.viewIdResourceName == id }
    fun textNode(text: String): AccessibilityNodeInfo = awaitNode(text, 150_000) { it.text?.toString() == text }
    fun click(id: String) {
        val node = node(id)
        try { assertTrue("Cannot click $id", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
        finally { @Suppress("DEPRECATION") node.recycle() }
    }

    fun recognizedText(id: String? = null, windowId: Int? = null): String {
        val node = awaitNode("recognized Alice reading", 150_000) {
            (id == null || it.viewIdResourceName == id) && (windowId == null || it.windowId == windowId) &&
                it.text?.toString()?.contains("rabbit", ignoreCase = true) == true
        }
        return try { node.text.toString() } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    private fun awaitNode(description: String, timeout: Long = 10_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val diagnosticLimit = InstrumentationRegistry.getArguments().getString("onboardingTimeoutSeconds")?.toLongOrNull()?.times(1000)
        val deadline = android.os.SystemClock.uptimeMillis() + (diagnosticLimit ?: timeout)
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            // Closing a Compose popup through accessibility can leave API-28
            // LDPlayer's activeWindow pointing at the removed popup. Inspect the
            // app's live windows, as the existing audio integration QA does.
            var found: AccessibilityNodeInfo? = null
            roots().forEach { root ->
                if (found == null) found = find(root, predicate)
                @Suppress("DEPRECATION") root.recycle()
            }
            found?.let { return it }
            Thread.sleep(100)
        }
        val visible = mutableListOf<String>()
        fun describe(node: AccessibilityNodeInfo) {
            if (!node.text.isNullOrEmpty()) visible.add("window=${node.windowId}, id=${node.viewIdResourceName}: ${node.text}")
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                describe(child)
                @Suppress("DEPRECATION") child.recycle()
            }
        }
        roots().forEach { root -> describe(root); @Suppress("DEPRECATION") root.recycle() }
        screenshot("failure")
        val manager = (app.applicationContext as UtterlaneApp).recognizerManager
        fun field(target: Any, name: String): Any? = runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }.getOrNull()
        val backend = field(manager, "recognizer")
        val runtime = "ready=${manager.isReady.value}, loading=${manager.isLoading.value}, failure=${manager.failure.value}, " +
            "mutex=${field(manager, "mutex")}, sessions=${field(manager, "sessions")}, pending=${field(manager, "pendingOperations")}, " +
            "worker=${backend?.let { field(it, "workerPid") }}, replies=${backend?.let { (field(it, "replies") as? Map<*, *>)?.size }}"
        val stacks = Thread.getAllStackTraces().filterValues { frames -> frames.any { it.className.startsWith("io.github.lrq3000.utterlane.asr") } }
            .map { (thread, frames) -> "${thread.name}: ${frames.joinToString("\n")}" }
        throw AssertionError("Onboarding node not found: $description; visible=$visible; runtime=$runtime; stacks=$stacks")
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // The dialog can visibly replace progress with a finished transcript
        // while API-28's virtual-node cache still contains the old progress tree.
        // Refresh each node from its provider before reading text or child IDs.
        if (!node.refresh()) return null
        @Suppress("DEPRECATION")
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = find(child, predicate)
            @Suppress("DEPRECATION") child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun screenshot(name: String) {
        val directory = File(app.getExternalFilesDir(null), "onboarding-qa").apply { mkdirs() }
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
}
