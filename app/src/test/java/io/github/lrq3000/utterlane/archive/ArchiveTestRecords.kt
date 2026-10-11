package io.github.lrq3000.utterlane.archive

internal fun targets(store: ArchivalJobStore, id: String) = mutableListOf<ArchivalCleanup>().apply { store.forEachCleanup(id, ::add) }
internal fun publications(store: ArchivalJobStore, owner: ArtifactOwner) = mutableListOf<PublishedArtifact>().apply { store.forEachPublished(owner, ::add) }
internal fun settle(store: ArchivalJobStore) { while (store.maintain()) { } }
