package io.github.lrq3000.utterlane.asr

import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A queued attempt owns its private append target even when no Activity or history row exists. */
internal class QueueTranscriptTarget(val file: File) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<QueueTranscriptTarget>
}
