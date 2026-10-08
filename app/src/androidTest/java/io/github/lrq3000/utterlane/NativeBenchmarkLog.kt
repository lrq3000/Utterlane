package io.github.lrq3000.utterlane

import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/** Capture existing native counters only in the isolated QA process. */
internal class NativeBenchmarkLog(file: File) : Closeable {
    private val output = FileOutputStream(file)
    private val previous = Os.dup(FileDescriptor.err)
    private val environment = listOf("CRISPASR_PARAKEET_BENCH", "CRISPASR_PARAKEET_ENC_PROBE",
        "CRISPASR_PARAKEET_DECODE_TIMING").associateWith { Os.getenv(it) }

    init {
        Os.dup2(output.fd, 2)
        environment.keys.forEach { Os.setenv(it, "1", true) }
    }

    override fun close() {
        try {
            Os.dup2(previous, 2)
            environment.forEach { (key, value) ->
                if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true)
            }
        } finally {
            Os.close(previous)
            output.close()
        }
    }
}
