package io.github.lrq3000.utterlane.archive

/** Recovery accepts only a previously journaled UUID/generation target. */
object PendingArtifactName {
    private val identity = Regex(".+__[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}_[1-9][0-9]*\\.[A-Za-z0-9]+")
    fun requireValid(name: String) { require(identity.matches(name)) { "Not a journaled pending artifact name" } }
    fun resolve(name: String, children: Sequence<Pair<String, String>>): String? {
        requireValid(name)
        var found: String? = null
        for ((display, uri) in children) if (display == name) {
            check(found == null || found == uri) { "Ambiguous pending artifact; cleanup preserved for retry" }
            found = uri
        }
        return found
    }
}
