package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only verified files enter payload; validators and incomplete files can never be installed. */
internal class ModelDownloadCheckpoint(private val directory: File, private val model: ModelDefinition) {
    val payload = File(directory, "payload")

    fun prepare() {
        val manifest = File(directory, "catalog")
        val identity = model.artifacts.joinToString("\n") { "${it.localName}\t${it.bytes}\t${it.sha256}\t${it.url}" }
        if (!manifest.isFile || manifest.readText() != identity) {
            check(directory.deleteRecursively()) { "Cannot reset model checkpoint" }
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create model checkpoint" }
            manifest.writeText(identity)
        }
        check(payload.mkdirs() || payload.isDirectory) { "Cannot create model payload directory" }
    }

    suspend fun fetch(artifact: ModelArtifact, open: suspend (Request) -> ModelManager.DownloadBody,
        progress: (bytes: Long, connecting: Boolean) -> Unit) {
        val complete = File(payload, artifact.localName)
        if (ArtifactVerifier.valid(complete, artifact)) return
        val partial = File(directory, "${artifact.localName}.part")
        val validator = File(directory, "${artifact.localName}.etag")
        fun resetPartial() {
            Files.deleteIfExists(partial.toPath())
            Files.deleteIfExists(validator.toPath())
        }
        // A digest pins the complete representation even when the CDN changes how it serves or tags the file.
        val pinned = artifact.sha256 != null
        val savedTag = if (!pinned && validator.isFile) strongTag(validator.readText()) else null
        var offset = partial.length()
        // A fully written but not-yet-committed file might have been interrupted
        // just before verification. Only a pinned digest can prove it now.
        if (offset == artifact.bytes && pinned && ArtifactVerifier.valid(partial, artifact)) {
            Files.move(partial.toPath(), complete.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            progress(artifact.bytes, false)
            return
        }
        if (offset >= artifact.bytes || (offset > 0L && savedTag == null && !pinned)) {
            resetPartial()
            offset = 0L
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            progress(offset, true)
            val request = Request.Builder().url(artifact.url).header("Accept-Encoding", "identity").apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    savedTag?.let { header("If-Range", it) }
                }
            }.build()
            val retryFromZero = open(request).use { response ->
                val expectedRange = "bytes $offset-${artifact.bytes - 1}/${artifact.bytes}"
                val encoding = response.header("Content-Encoding")
                val tag = response.header("ETag")
                val full = response.code == 200
                val suffix = response.code == 206 && offset > 0 && response.header("Content-Range") == expectedRange &&
                    (pinned || savedTag != null && savedTag == tag)
                val identityEncoding = encoding == null || encoding.equals("identity", ignoreCase = true)
                when {
                    offset > 0 && (response.code == 416 || response.code == 206 && (!suffix || !identityEncoding)) -> true
                    (!full && !suffix) || !identityEncoding -> throw IOException("Unexpected model response: HTTP ${response.code}")
                    else -> {
                        if (full) {
                            // A server can ignore Range, including after a representation change.
                            resetPartial()
                            offset = 0L
                            progress(offset, false)
                            if (!pinned) strongTag(tag)?.let(validator::writeText)
                        }
                        try {
                            writeArtifact(artifact, partial, response, offset, { progress(it, false) })
                            Files.move(partial.toPath(), complete.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        } catch (badContent: SecurityException) {
                            resetPartial()
                            progress(0, false)
                            throw badContent
                        } catch (interrupted: IOException) {
                            if (!pinned && strongTag(tag) == null) {
                                // These bytes cannot be identified on the next request; do not report them as resumable.
                                resetPartial()
                                progress(0, false)
                            }
                            throw interrupted
                        }
                        false
                    }
                }
            }
            if (!retryFromZero) return
            resetPartial()
            offset = 0L // A malformed reply to an ordinary GET cannot start a retry loop.
        }
    }

    private fun strongTag(value: String?) = value?.takeIf {
        it.length >= 2 && it.first() == '"' && it.last() == '"' && it.substring(1, it.lastIndex).all { c -> c > ' ' && c != '"' && c != '\u007f' }
    }
}

/** Verify in the write pass. A resumed prefix is hashed once; fresh transfers need no second disk pass. */
internal suspend fun writeArtifact(artifact: ModelArtifact, file: File, input: java.io.InputStream, offset: Long,
    progress: (Long) -> Unit, retryShortRead: Boolean = true) {
    val digest = artifact.sha256?.let { MessageDigest.getInstance("SHA-256") }
    val buffer = ByteArray(65536)
    val coroutine = currentCoroutineContext()
    if (offset > 0 && digest != null) file.inputStream().use { existing ->
        while (true) {
            coroutine.ensureActive()
            val count = existing.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    var bytes = offset
    progress(bytes)
    java.io.FileOutputStream(file, offset > 0).use { output ->
        while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > artifact.bytes - bytes) throw SecurityException("Unexpected model size: ${artifact.localName}")
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            bytes += count
            progress(bytes)
        }
    }
    if (bytes != artifact.bytes) {
        val message = "Incomplete model file: ${artifact.localName}"
        if (retryShortRead) throw IOException(message) else throw SecurityException(message)
    }
    if (digest != null && digest.digest().joinToString("") { "%02x".format(it) } != artifact.sha256)
        throw SecurityException("Model integrity verification failed: ${artifact.localName}")
}
