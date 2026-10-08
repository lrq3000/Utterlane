package io.github.lrq3000.utterlane.history

import android.content.Context
import android.content.Intent
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeDestination

/** Compatibility entry for Settings/notifications. Keeping all three primary
 * destinations in Home's retained host preserves both paging windows and scroll
 * positions through tab changes, Record and recreation without global caches. */
class HistoryActivity : HomeActivity() {
    companion object {
        private const val EXTRA_TRANSCRIPTS = "history_transcripts"
        fun intent(context: Context, transcripts: Boolean = false, internal: Boolean = true): Intent =
            Intent(context, HistoryActivity::class.java).putExtra(EXTRA_TRANSCRIPTS, transcripts)
                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, internal)
    }
    override val historyEntryDestination: HomeDestination
        get() = if (intent.getBooleanExtra(EXTRA_TRANSCRIPTS, false)) HomeDestination.TRANSCRIPTS else HomeDestination.AUDIO
}
