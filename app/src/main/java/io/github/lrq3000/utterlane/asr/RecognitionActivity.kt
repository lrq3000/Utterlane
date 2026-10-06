package io.github.lrq3000.utterlane.asr

/** Content-free status; elapsed times count only awake, interactive operation time. */
data class RecognitionActivity(
    val requestId: Int = 0,
    val stage: String = "idle",
    val elapsedMillis: Long = 0,
    val sinceProgressMillis: Long = 0,
    val completedUnits: Long = 0,
    val active: Boolean = false,
    val message: String? = null
)
