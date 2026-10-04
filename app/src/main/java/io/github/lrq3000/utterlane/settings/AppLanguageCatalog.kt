package io.github.lrq3000.utterlane.settings

object AppLanguageCatalog {
    // UI translations, not the selected speech model's language capabilities.
    val tags = listOf("en", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hr", "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv", "uk")
    private val supported = tags.toHashSet()
    fun normalize(tag: String?): String = tag?.takeIf { it in supported }.orEmpty()
}
