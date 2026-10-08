package io.github.lrq3000.utterlane.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HistoryRow(val cursor: HistoryCursor, val detail: String,
    val retention: RetentionMark, val model: String? = null, val durationMs: Long = 0,
    val recovery: Boolean = false, val imported: Boolean = false, val speakerLabels: Boolean = false) {
    val id get() = cursor.id

    companion object {
        fun from(entry: TranscriptEntry, preview: String) =
            // Result metadata belongs to this transcript, never to the current
            // speaker setting or a subsequently retranscribed source recording.
            HistoryRow(entry.cursor, preview, entry.retention, model = entry.model,
                durationMs = entry.durationMs, speakerLabels = entry.speakerLabels)

        fun from(entry: HistoryEntry) =
            HistoryRow(entry.cursor, "", entry.retention, durationMs = entry.durationMs,
                recovery = entry.needsRecovery, imported = entry.sourceName != null,
                speakerLabels = entry.speakerLabels)
    }
}

/** One retained pager per destination. Loaded previews stay bounded while dropped
 * pages can be fetched again in either direction using their stable creation keys. */
internal class HistoryViewModel(app: UtterlaneApp, transcripts: Boolean) : ViewModel() {
    @Volatile private var source: HistoryPagingSource? = null
    private val revisions = if (transcripts) app.transcriptHistory.revision else app.recordingHistory.revision

    val pages = Pager(PagingConfig(pageSize = 30, initialLoadSize = 30,
        prefetchDistance = 6, maxSize = 90, enablePlaceholders = false)) {
        HistoryPagingSource(app, transcripts).also { source = it }
    }.flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch { revisions.collect { source?.invalidate() } }
    }

    class Factory(private val app: UtterlaneApp, private val transcripts: Boolean) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(app, transcripts) as T
    }
}

/** File previews are read on IO. Locale-independent rows let the UI reformat its
 * few visible labels on configuration changes without losing the paging window. */
private class HistoryPagingSource(private val app: UtterlaneApp, private val transcripts: Boolean) :
    PagingSource<HistoryCursor, HistoryRow>() {
    private val revisions = if (transcripts) app.transcriptHistory.revision else app.recordingHistory.revision

    override fun getRefreshKey(state: PagingState<HistoryCursor, HistoryRow>): HistoryCursor? =
        state.anchorPosition?.let { state.closestItemToPosition(it)?.cursor }

    override suspend fun load(params: LoadParams<HistoryCursor>): LoadResult<HistoryCursor, HistoryRow> =
        withContext(Dispatchers.IO) {
            val version = revisions.value
            try {
                val direction = when (params) {
                    is LoadParams.Append -> HistoryDirection.APPEND
                    is LoadParams.Prepend -> HistoryDirection.PREPEND
                    is LoadParams.Refresh -> HistoryDirection.REFRESH
                }
                val page = if (transcripts) {
                    val source = app.transcriptHistory.page(params.key, direction, params.loadSize)
                    HistoryPage(source.entries.map { entry ->
                        val preview = app.transcriptHistory.acquire(entry.id).use {
                            entry.file.reader(Charsets.UTF_8).use(HistoryPreview::read)
                        }
                        HistoryRow.from(entry, preview)
                    }, source.before, source.after)
                } else {
                    val source = app.recordingHistory.page(params.key, direction, params.loadSize)
                    HistoryPage(source.entries.map { entry ->
                        HistoryRow.from(entry)
                    }, source.before, source.after)
                }
                // Initialization, pruning or deletion can change the index while
                // previews are read. Never publish a mixed/stale generation.
                if (version != revisions.value) LoadResult.Invalid()
                else LoadResult.Page(page.entries, page.before, page.after)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (version != revisions.value) LoadResult.Invalid() else LoadResult.Error(error)
            }
        }
}
