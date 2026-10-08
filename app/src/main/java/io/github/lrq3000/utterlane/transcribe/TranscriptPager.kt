package io.github.lrq3000.utterlane.transcribe

import androidx.paging.*
import io.github.lrq3000.utterlane.asr.TranscriptDocument
import io.github.lrq3000.utterlane.asr.TranscriptStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TranscriptChunk(val index: Int, val text: String)

/** One retained, bounded page generation. Placeholders preserve global positions
 * for scrolling/scrollbar seeking while only a few chunks of text live in RAM. */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptPager(scope: CoroutineScope) {
    private val selected = MutableStateFlow<TranscriptStore?>(null)
    @Volatile private var source: TranscriptPagingSource? = null
    @Volatile private var bytes = 0L
    val pages = selected.flatMapLatest { store ->
        if (store == null) flowOf(PagingData.empty<TranscriptChunk>())
        else Pager(PagingConfig(pageSize = 3, initialLoadSize = 3, prefetchDistance = 1,
            maxSize = 9, enablePlaceholders = true, jumpThreshold = 9)) {
            TranscriptPagingSource(store).also { source = it }
        }.flow
    }.cachedIn(scope)

    fun show(store: TranscriptStore?) {
        source?.invalidate(); source = null
        bytes = store?.bytes ?: 0
        selected.value = store
    }

    fun refresh() {
        val current = selected.value?.bytes ?: 0
        if (current != bytes) { bytes = current; source?.invalidate() }
    }
}

private class TranscriptPagingSource(private val store: TranscriptStore) : PagingSource<Int, TranscriptChunk>() {
    private val document = TranscriptDocument(store.file, store.bytes)
    // A scrollbar seek must load near its destination, not append every chunk
    // between the current viewport and the end of a long recording.
    override val jumpingSupported: Boolean = true
    override fun getRefreshKey(state: PagingState<Int, TranscriptChunk>): Int? = state.anchorPosition
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TranscriptChunk> = withContext(Dispatchers.IO) {
        try {
            val key = params.key ?: 0
            val start = (if (params is LoadParams.Prepend) key - params.loadSize + 1 else key)
                .coerceIn(0, document.chunkCount)
            val end = minOf(document.chunkCount.toLong(), start.toLong() + params.loadSize).toInt()
            val chunks = store.acquire().use { (start until end).map { TranscriptChunk(it, document.read(it)) } }
            LoadResult.Page(chunks, if (start > 0) start - 1 else null,
                if (end < document.chunkCount) end else null, itemsBefore = start,
                itemsAfter = document.chunkCount - end)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { LoadResult.Error(error) }
    }
}
