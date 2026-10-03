package io.github.lrq3000.utterlane.asr

import java.io.File
import java.security.MessageDigest

object ArtifactVerifier {
    fun valid(file: File, artifact: ModelArtifact): Boolean {
        if (!file.isFile || file.length() != artifact.bytes) return false
        return artifact.sha256 == null || sha256(file) == artifact.sha256
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
