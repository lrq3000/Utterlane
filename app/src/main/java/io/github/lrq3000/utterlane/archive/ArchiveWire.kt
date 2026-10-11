package io.github.lrq3000.utterlane.archive

import android.os.Bundle
import java.io.File

class ArchiveWorkerFailure(message: String, val creationStarted: Boolean? = null) : java.io.IOException(message)

/** Small versioned descriptors. Audio/text bytes travel only through an owned PFD. */
internal object ArchiveWire {
    const val HELLO = 1
    const val RUN = 2
    const val TARGET = 3
    const val ACK = 4
    const val DONE = 5
    const val ERROR = 6
    const val STOP = 7
    const val DELETE = 8
    const val FIND = 9
    fun job(job: ArchivalJob) = Bundle().apply {
        putString("job", job.id); putLong("generation", job.generation)
        putString("kind", job.request.owner.kind.name); putString("owner", job.request.owner.id)
        putString("operation", job.request.operation.name); putString("folder", job.request.destination)
        putString("name", job.request.displayName); putString("mime", job.request.mimeType)
        putString("representation", job.request.representation); putString("sourceRepresentation", job.request.sourceRepresentation)
        if (job.request.operation == ArchivalOperation.ENCODE_AUDIO) putString("sourcePath", job.request.source.absolutePath)
        putLong("created", job.request.created); putString("model", job.request.model.take(512))
    }
    fun job(data: Bundle) = ArchivalJob(requireNotNull(data.getString("job")),
        ArchivalRequest(ArtifactOwner(ArtifactKind.valueOf(requireNotNull(data.getString("kind"))), requireNotNull(data.getString("owner"))),
            ArchivalOperation.valueOf(requireNotNull(data.getString("operation"))), File("descriptor"),
            requireNotNull(data.getString("folder")), requireNotNull(data.getString("name")), requireNotNull(data.getString("mime")),
            created = data.getLong("created"), model = data.getString("model").orEmpty(),
            representation = data.getString("representation") ?: AudioRepresentation.ORIGINAL,
            sourceRepresentation = data.getString("sourceRepresentation") ?: AudioRepresentation.ORIGINAL), generation = data.getLong("generation"))
}
