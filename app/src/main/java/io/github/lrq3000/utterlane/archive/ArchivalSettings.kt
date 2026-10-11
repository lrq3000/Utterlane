package io.github.lrq3000.utterlane.archive

/** Immutable per-producer preferences; null audio folder means app-private. */
data class ArchivalSettings(val audioFolder: String? = null, val markdownFolder: String? = null,
    val automaticMarkdown: Boolean = false, val quality: AudioQuality = AudioQuality()) {
    val automaticDestination get() = markdownFolder?.takeIf { automaticMarkdown }
    val enabled get() = audioFolder != null || automaticDestination != null || quality.encoding
}

data class ArchivePlan(val folder: String, val epoch: Long) {
    fun writeTo(properties: java.util.Properties) {
        properties.setProperty("archiveFolder", folder); properties.setProperty("archiveEpoch", epoch.toString())
    }
    companion object {
        fun read(properties: java.util.Properties): ArchivePlan? = properties.getProperty("archiveFolder")?.let {
            ArchivePlan(it, properties.getProperty("archiveEpoch").toLong())
        }
    }
}

class ArchivalCancelledException : IllegalStateException("The archival producer was cancelled")
