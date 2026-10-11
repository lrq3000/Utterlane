package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Test

class ImportRetentionPolicyTest {
    private val enabled = ImportRetentionPreferences(audioHistoryEnabled = true)

    @Test fun strictDurationBoundaryAndDurationTakesPriorityOverSize() {
        assertEquals(ImportRetentionDecision.Use(ImportRetentionChoice.KEEP), decide(600000, 52428800))
        assertEquals(ImportRetentionDecision.Ask(ImportRetentionCategory.DURATION), decide(600001, 52428801))
        assertEquals(ImportRetentionDecision.Ask(ImportRetentionCategory.DURATION), decide(600001, null))
    }

    @Test fun strictSizeBoundaryForShortOrUnknownDuration() {
        for (duration in listOf(null, 0L, 599999L, 600000L)) {
            assertEquals(ImportRetentionDecision.Use(ImportRetentionChoice.KEEP), decide(duration, 52428800))
            assertEquals(ImportRetentionDecision.Ask(ImportRetentionCategory.SIZE), decide(duration, 52428801))
        }
    }

    @Test fun missingAndInvalidMetadataDoNotInventALargeSource() {
        for (duration in listOf(null, -1L)) for (size in listOf(null, -1L, 0L))
            assertEquals(ImportRetentionDecision.Use(ImportRetentionChoice.KEEP), decide(duration, size))
    }

    @Test fun disabledHistoryNeverAsksEvenWithRememberedKeep() {
        val preferences = ImportRetentionPreferences(false, ImportRetentionChoice.KEEP, ImportRetentionChoice.KEEP)
        for (duration in listOf(null, 600001L))
            assertEquals(ImportRetentionDecision.Use(ImportRetentionChoice.DISCARD), decide(duration, Long.MAX_VALUE, preferences))
    }

    @Test fun rememberedCategoriesAreIndependentAndNeverAskASecondQuestion() {
        for (choice in ImportRetentionChoice.entries) {
            val duration = enabled.copy(durationChoice = choice)
            assertEquals(ImportRetentionDecision.Use(choice), decide(600001, Long.MAX_VALUE, duration))
            assertEquals(ImportRetentionDecision.Ask(ImportRetentionCategory.SIZE), decide(null, Long.MAX_VALUE, duration))
            val size = enabled.copy(sizeChoice = choice)
            assertEquals(ImportRetentionDecision.Use(choice), decide(null, Long.MAX_VALUE, size))
            assertEquals(ImportRetentionDecision.Ask(ImportRetentionCategory.DURATION), decide(600001, Long.MAX_VALUE, size))
            // Remembered DISCARD only applies to its warning category.
            assertEquals(ImportRetentionDecision.Use(ImportRetentionChoice.KEEP), decide(100, 100, size))
        }
    }

    private fun decide(duration: Long?, size: Long?, preferences: ImportRetentionPreferences = enabled) =
        ImportRetentionPolicy.decide(duration, size, preferences)
}
