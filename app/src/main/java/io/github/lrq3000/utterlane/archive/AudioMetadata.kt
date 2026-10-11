package io.github.lrq3000.utterlane.archive

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties

/** Reclamation authority needs both the replacement file and its directory entry
 * synced before unlinking original PCM. HistoryMetadata's ordinary atomic rename
 * alone is not this durability boundary. Windows JVM tests exercise atomic files;
 * Android uses its native directory-fsync API for the production ordering. */
internal object AudioMetadata {
    fun write(file: File, properties: Properties) {
        ArchiveDisk.write(file, properties)
        syncDirectory(checkNotNull(file.parentFile))
    }
    fun syncDirectory(directory: File) {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            check(directory.isDirectory)
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        } else if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
