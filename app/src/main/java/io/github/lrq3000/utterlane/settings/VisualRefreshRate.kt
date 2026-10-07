package io.github.lrq3000.utterlane.settings

/** One policy for preferences, initial UI state and presentation timers. */
object VisualRefreshRate {
    const val DEFAULT = 60
    val supported = listOf(1, 2, 5, 10, 20, 30, 60, 90, 200)
    private val allowed = supported.toSet()

    fun fromStored(value: Int?): Int = value?.takeIf { it in allowed } ?: DEFAULT
    fun requireValid(value: Int): Int = value.also { require(it in allowed) { "Unsupported visual refresh rate: $it" } }
    fun intervalMillis(value: Int): Long = 1000L / requireValid(value)
}
