package io.github.lrq3000.utterlane.settings

/** Persist the diameter in dp; a density/display change must not rewrite the user's choice. */
object FloatingButtonSize {
    const val DEFAULT_DP = 56
    const val MIN_DP = 44
    const val MAX_DP = 144

    fun bounded(dp: Int): Int = dp.coerceIn(MIN_DP, MAX_DP)

    fun preset(dp: Int): String? = when (dp) {
        44 -> SettingsRepository.BUTTON_SIZE_SMALL
        56 -> SettingsRepository.BUTTON_SIZE_MEDIUM
        72 -> SettingsRepository.BUTTON_SIZE_LARGE
        else -> null
    }

    fun diameter(preset: String?, customDp: Int?): Int = when (preset) {
        SettingsRepository.BUTTON_SIZE_SMALL -> 44
        SettingsRepository.BUTTON_SIZE_LARGE -> 72
        "custom" -> bounded(customDp ?: DEFAULT_DP)
        else -> DEFAULT_DP
    }
}
