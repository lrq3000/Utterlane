package io.github.lrq3000.utterlane.asr

import android.os.Bundle
import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** Strict IPC boundary: disk recovery defaults must not silently repair sender corruption. */
internal object OptionsCodec {
    private val keys = RuntimeOptions().toMap().keys

    fun toBundle(options: RuntimeOptions) = Bundle().apply {
        options.requireValid().toMap().forEach { (key, value) -> putString(key, value) }
    }

    @Suppress("DEPRECATION") // Read the actual type before accepting it as a wire string.
    fun fromBundle(bundle: Bundle): RuntimeOptions = fromValues(bundle.keySet().associateWith { bundle.get(it) })

    fun fromValues(values: Map<String, *>): RuntimeOptions {
        require(values.keys == keys && values.values.all { it is String }) { "Invalid runtime options snapshot" }
        val draft = RuntimeOptions.parseDraft(values.mapValues { it.value as String })
        require(draft.errors.isEmpty()) { "Invalid runtime options: ${draft.errors.keys.joinToString()}" }
        return requireNotNull(draft.options)
    }
}
