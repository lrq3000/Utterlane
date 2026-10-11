package io.github.lrq3000.utterlane.archive

data class ArchiveSummary(val queued: Int = 0, val running: Int = 0, val paused: Int = 0,
    val cleanup: Int = 0, val readyCleanup: Int = 0, val maintenance: Boolean = false)
data class PublishedArtifact(val id: String, val owner: ArtifactOwner, val destination: String,
    val operation: ArchivalOperation, val part: Int, val generation: Long, val uri: String,
    val representation: String = AudioRepresentation.ORIGINAL)
data class ArchiveStorageDiagnostics(val cachedJobs: Int, val cachedOwners: Int, val executionRecords: Int,
    val jobReads: Long, val indexVisits: Long)
