package io.github.lrq3000.utterlane.history

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Atomic metadata publication is shared by both independently pruned histories. */
internal object HistoryMetadata {
    fun read(file: File): Properties = Properties().apply { file.inputStream().use { load(it) } }
    fun write(file: File, properties: Properties) {
        val pending = File(file.parentFile, file.name + ".tmp")
        pending.outputStream().use { properties.store(it, "Utterlane private history") }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun readMark(p: Properties, fallback: Long) = RetentionMark(
        p.getProperty("reference", fallback.toString()).toLong(), p.getProperty("pinned", "false").toBoolean(),
        p.getProperty("holdForLaunch")?.takeIf { it.isNotEmpty() })
    fun writeMark(p: Properties, mark: RetentionMark) {
        p.setProperty("reference", mark.since.toString())
        p.setProperty("pinned", mark.pinned.toString())
        mark.holdForLaunch?.let { p.setProperty("holdForLaunch", it) }
    }
}
