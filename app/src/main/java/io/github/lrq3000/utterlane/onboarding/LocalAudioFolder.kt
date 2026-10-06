package io.github.lrq3000.utterlane.onboarding

/** FileObserver can watch local paths, not arbitrary DocumentsProvider URIs. */
object LocalAudioFolder {
    private val volumeId = Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")

    /** Android's Downloads shortcut can return a raw local tree rather than an
     * external-storage primary: ID. Never accept raw paths outside Downloads. */
    fun resolveDownloads(documentId: String, downloadsRoot: String): String? {
        if (!documentId.startsWith("raw:")) return null
        val path = documentId.removePrefix("raw:")
        val root = downloadsRoot.trimEnd('/')
        if (path == root) return root
        if (!path.startsWith("$root/")) return null
        return resolve("primary:${path.substring(root.length + 1)}", root)
    }

    fun resolve(documentId: String, primaryRoot: String): String? {
        val separator = documentId.indexOf(':')
        if (separator < 1) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        if (relative.startsWith('/') || relative.any { it == '\\' || it == ':' || it.code < 32 }) return null
        val parts = relative.split('/')
        if (parts.any { it == ".." || it == "." } || (relative.isNotEmpty() && parts.any { it.isEmpty() })) return null
        val root = when {
            volume == "primary" -> primaryRoot.trimEnd('/')
            volumeId.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}
