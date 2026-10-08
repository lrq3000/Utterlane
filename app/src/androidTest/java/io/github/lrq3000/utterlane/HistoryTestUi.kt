package io.github.lrq3000.utterlane

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

/** Check history semantics in the real window, including API-28's extras-based
 * state description. Pinning is state on a navigation row, never a second action. */
internal class HistoryTestUi(private val ui: OnboardingTestUi) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    fun awaitPinned(id: String, pinned: Boolean) {
        val expected = context.getString(if (pinned) R.string.history_pinned else R.string.history_not_pinned)
        val immediate = context.getString(R.string.history_unpin_immediate)
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            val node = ui.node("history_entry_$id")
            val state = try { AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString() }
                finally { node.recycle() }
            if (state == expected || (!pinned && state == immediate)) return
            Thread.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("History row $id did not publish pinned=$pinned")
    }

    fun assertNavigationOnly(id: String) {
        val row = ui.node("history_entry_$id")
        try {
            assertTrue(row.isClickable)
            assertFalse("A row must not be a pin toggle", row.isCheckable)
            assertPassiveChildren(row)
        } finally { row.recycle() }
    }

    fun text(id: String): String = rowLabels(id) { it.text }

    /** Icons can remain child nodes in the unmerged accessibility tree. Inspect
     * the actual descriptions, not visible text or an assumed merged parent. */
    fun contentDescriptions(id: String): String = rowLabels(id) { it.contentDescription }

    private fun rowLabels(id: String, label: (AccessibilityNodeInfo) -> CharSequence?): String {
        val row = ui.node("history_entry_$id")
        return try { labelsOf(row, label) } finally { row.recycle() }
    }

    fun assertAbsent(tag: String) {
        val screen = ui.node("history_screen")
        try { assertAbsentIn(screen, tag) } finally { screen.recycle() }
    }

    private fun assertAbsentIn(node: AccessibilityNodeInfo, tag: String) {
        assertNotEquals("History must not expose $tag", tag, node.viewIdResourceName)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { assertAbsentIn(child, tag) } finally { child.recycle() }
        }
    }

    private fun labelsOf(node: AccessibilityNodeInfo, label: (AccessibilityNodeInfo) -> CharSequence?): String = buildString {
        append(label(node)?.toString().orEmpty())
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { append('\n'); append(labelsOf(child, label)) } finally { child.recycle() }
        }
    }

    private fun assertPassiveChildren(parent: AccessibilityNodeInfo) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChild(index) ?: continue
            try {
                assertFalse("No inline action may compete with opening history", child.isClickable)
                assertFalse("Pin indicators must not be checkable", child.isCheckable)
                assertPassiveChildren(child)
            } finally { child.recycle() }
        }
    }
}
