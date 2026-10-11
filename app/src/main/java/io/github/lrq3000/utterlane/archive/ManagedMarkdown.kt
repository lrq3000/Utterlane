package io.github.lrq3000.utterlane.archive

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ManagedMarkdown {
    fun filename(created: Long, model: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(zone).format(Instant.ofEpochMilli(created))
        val sanitized = model.take(256).replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-')
        val name = buildString {
            var bytes = 0
            val points = sanitized.codePoints().iterator()
            while (points.hasNext()) {
                val point = String(Character.toChars(points.nextInt()))
                bytes += point.toByteArray(Charsets.UTF_8).size
                if (bytes > 80) break
                append(point)
            }
        }.ifEmpty { "model" }
        return "${date}_$name.md"
    }
    fun write(input: InputStream, output: OutputStream, bytes: Long, created: Long, model: String,
        checkActive: () -> Unit = {}): Long {
        val header = "# Transcript\n\nDate: ${Instant.ofEpochMilli(created)}\nModel: ${model.take(512).replace(Regex("[\\r\\n\\p{Cntrl}]+"), " ")}\n\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        return copyExactly(input, output, bytes, checkActive)
    }

    internal fun copyExactly(input: InputStream, output: OutputStream, bytes: Long, checkActive: () -> Unit): Long {
        require(bytes >= 0)
        val buffer = ByteArray(64 * 1024)
        var remaining = bytes
        while (remaining > 0) {
            checkActive()
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) throw java.io.EOFException("Archival input ended before its committed length")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        checkActive()
        return bytes
    }
}

/** IO-only provider port; no Android or history dependency. */
interface ExternalArtifacts {
    /** Recovery only: caller must supply an exact name from a durable creation intent. */
    fun findExact(folder: String, name: String): String?
    fun create(folder: String, name: String, mime: String): String
    fun write(uri: String, body: (OutputStream) -> Unit)
    /** False/error means retry, absence is success. */
    fun delete(uri: String): Boolean
    fun verifyCreated(uri: String, name: String) {}
}

object ArchivalTransfer {
    fun execute(job: ArchivalJob, input: InputStream, bytes: Long, provider: ExternalArtifacts,
        target: (String) -> Boolean, checkActive: () -> Unit = {}) {
        checkActive()
        val r = job.request
        val uri = provider.create(r.destination, job.targetName, r.mimeType)
        // Do not write even one byte before the parent durably owns the URI.
        if (!target(uri)) throw java.io.InterruptedIOException("Archival was cancelled")
        provider.verifyCreated(uri, job.targetName)
        checkActive()
        provider.write(uri) { output ->
            when (r.operation) {
                ArchivalOperation.COPY_AUDIO -> ManagedMarkdown.copyExactly(input, output, bytes, checkActive)
                ArchivalOperation.SAVE_MARKDOWN -> ManagedMarkdown.write(input, output, bytes, r.created, r.model, checkActive)
                ArchivalOperation.ENCODE_AUDIO -> error("Encoding requires the progressive codec worker")
            }
            output.flush()
        }
        checkActive()
    }
}
