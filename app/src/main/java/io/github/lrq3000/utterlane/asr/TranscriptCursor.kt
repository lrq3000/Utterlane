package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bounded page at a time; sleep never turns undelivered text into lost text. */
class TranscriptCursor {
    var offset = 0L
        private set
    var failed = false
        private set

    // The caller serializes drain/finish. Availability must be rechecked after IO:
    // the editor or lock screen can change while a disk read is suspended.
    suspend fun drain(store: TranscriptStore, available: () -> Boolean, insert: (String) -> Boolean) {
        while (!failed && available()) {
            val page = withContext(Dispatchers.IO) { store.page(offset) }
            if (page.isEmpty() || !available()) return
            if (!insert(page)) { failed = true; return }
            offset += page.toByteArray(Charsets.UTF_8).size
        }
    }

    fun hasPending(store: TranscriptStore): Boolean = offset < store.file.length()
}
