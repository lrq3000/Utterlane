package io.github.lrq3000.utterlane.archive

import java.io.File
import java.util.Properties
import java.util.UUID

/** Shared dispatch boundary, kept free of Android codecs so descriptor recovery
 * can be verified independently of device codec availability. */
internal object EncodingInput {
    /** New conversions publish quality, routing, source identity and output identity
     * in one replacement. A pre-fix bare config is completed from its durable
     * original descriptor, preserving its quality and all spool/cancellation state. */
    fun prepareConversion(original: OriginalAudioSource, requested: AudioQuality): RollingPcmJournal {
        val config = File(original.directory, "rolling.properties")
        if (!config.exists()) return RollingPcmJournal.create(original.directory, requested, original = original)
        val before = ArchiveDisk.read(config)
        val repaired = Properties().apply { putAll(before) }
        completeConversion(repaired, original)
        val quality = AudioQuality.read(repaired)
        require(quality.validTuple() && quality.encoding && repaired.getProperty("partFrames").toLong() > 0)
        if (before != repaired) AudioMetadata.write(config, repaired)
        return RollingPcmJournal.open(original.directory)
    }
    private fun fields(original: OriginalAudioSource): Map<String, String> = buildMap {
        put("importSource", original.sourceName ?: "audio-0.wav")
        put("sourceRepresentation", original.representation)
        if (original.nativePcm) put("importPcmParts", original.parts.toString())
    }
    internal fun completeConversion(config: Properties, original: OriginalAudioSource) {
        fields(original).forEach { (key, value) ->
            require(config.getProperty(key).let { it == null || it == value }) { "Conversion descriptor has a different $key" }
            config.setProperty(key, value)
        }
        if (!original.nativePcm) require(config.getProperty("importPcmParts", "0") == "0")
        if (config.getProperty("representation") == null) config.setProperty("representation", UUID.randomUUID().toString())
    }
    fun dispatch(directory: File, live: (RollingPcmJournal) -> Unit,
        imported: (File, RollingPcmJournal, Int) -> Unit) {
        val spool = RollingPcmJournal.open(directory)
        val config = ArchiveDisk.read(spool.config)
        val original = OriginalAudioSource.open(directory)
        val input = config.getProperty("importSource")
        if (input != null) {
            require(File(input).name == input && input != "." && input != "..")
            original?.let {
                fields(it).forEach { (key, value) -> check(config.getProperty(key) == value) { "Incomplete conversion descriptor: $key" } }
                if (!it.nativePcm) check(config.getProperty("importPcmParts", "0") == "0")
            }
            imported(File(directory, input), spool, config.getProperty("importPcmParts", "0").toInt())
        } else {
            check(original == null && !config.containsKey("sourceRepresentation") && !config.containsKey("importPcmParts")) {
                "Incomplete conversion descriptor; replan from the retained original source"
            }
            live(spool)
        }
    }
}
