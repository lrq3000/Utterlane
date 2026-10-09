package io.github.lrq3000.utterlane.util

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import io.github.lrq3000.utterlane.R
import java.util.Collections
import java.util.IdentityHashMap

/** Classifies actual write failures; free-space estimates cannot guarantee that a write will fit. */
object StorageFailure {
    // Providers and codec layers sometimes retain only errno text. Match a complete
    // errno token or the standard OS phrase, not generic IO, quota or memory errors.
    private val fullMessage = Regex("\\bENOSPC\\b|\\bno space left on device\\b", RegexOption.IGNORE_CASE)

    fun isFull(error: Throwable): Boolean {
        // Throwable subclasses may override equality. Identity is what makes even
        // a malformed multi-node cause cycle safe, with O(n) traversal and lookup.
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is ErrnoException) {
                if (current.errno == OsConstants.ENOSPC) return true
            } else if (current.message?.let(fullMessage::containsMatchIn) == true) return true
            current = current.cause
        }
        return false
    }

    /** Keep the original throwable for logging/recovery; this is presentation only. */
    fun userMessage(context: Context, error: Throwable): String? =
        if (isFull(error)) context.getString(R.string.storage_full_actionable) else null
}
