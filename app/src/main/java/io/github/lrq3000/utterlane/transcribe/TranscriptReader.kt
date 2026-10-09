package io.github.lrq3000.utterlane.transcribe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.lrq3000.utterlane.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlin.math.floor

@Composable
internal fun TranscriptReader(model: TranscriptionDialogModel, state: TranscriptionDialogState, modifier: Modifier,
    footer: @Composable () -> Unit = {}) {
    val pages = model.document.pages.collectAsLazyPagingItems()
    val list = rememberLazyListState()
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    // Conflate thumb moves instead of queuing a scroll coroutine for every pixel.
    val seeks = remember { Channel<Float>(Channel.CONFLATED) }
    DisposableEffect(Unit) { onDispose { seeks.close() } }
    LaunchedEffect(pages.itemCount) {
        for (fraction in seeks) {
            val count = pages.itemCount
            if (count == 0) continue
            val position = fraction.coerceIn(0f, 1f) * count
            val index = floor(position).toInt().coerceIn(0, count - 1)
            list.scrollToItem(index)
            // The first seek can land on a short placeholder. Wait for the real
            // chunk, then remeasure at that index before applying its pixel offset.
            // Otherwise a long-jump thumb stops several paragraphs short of EOF.
            snapshotFlow { pages.peek(index) != null }.first { it }
            list.scrollToItem(index)
            val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            list.scrollToItem(index, ((position - index).coerceIn(0f, 1f) * height).toInt())
        }
    }
    val thumb by remember {
        derivedStateOf {
            val layout = list.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            val last = layout.visibleItemsInfo.lastOrNull()
            if (first == null || last == null || layout.totalItemsCount == 0) 0f to 1f
            else {
                val count = layout.totalItemsCount.toFloat()
                val start = (first.index + ((layout.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)) / count
                val end = (last.index + ((layout.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)) / count
                start to (end - start).coerceIn(0f, 1f)
            }
        }
    }
    val scrollbarLabel = stringResource(R.string.dialog_transcript_scrollbar)
    // The footer is a sibling of the viewport, not part of its scrolling content.
    // Keeping this reader/list at the same composition position preserves its
    // anchor when progress collapses and extra space becomes available below.
    Column(modifier.clip(shape).background(colors.surface).border(1.dp, colors.outline.copy(alpha = 0.65f), shape)) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("transcript_viewport")) {
            if (state.transcriptBytes == 0L) {
                Text(stringResource(if (state.running || state.importing) R.string.dialog_waiting_text else R.string.dialog_empty_text),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            } else {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(end = 18.dp).testTag("transcript_reader"), state = list,
                        contentPadding = PaddingValues(12.dp)) {
                        items(pages.itemCount, key = { it }) { index ->
                            val chunk = pages[index]
                            if (chunk == null) Spacer(Modifier.height(128.dp))
                            else if (chunk.text.isNotEmpty()) Text(chunk.text, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().testTag("transcript_chunk_${chunk.index}"))
                        }
                    }
                }
                Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(18.dp).padding(vertical = 8.dp)
                    .testTag("transcript_scrollbar").semantics {
                        contentDescription = scrollbarLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(thumb.first, 0f..1f)
                        setProgress { seeks.trySend(it).isSuccess }
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            seeks.trySend(down.position.y / size.height.coerceAtLeast(1)); down.consume()
                            verticalDrag(down.id) { change ->
                                seeks.trySend(change.position.y / size.height.coerceAtLeast(1)); change.consume()
                            }
                        }
                    }) {
                    val width = 5.dp.toPx()
                    val left = (size.width - width) / 2
                    val height = (size.height * thumb.second).coerceIn(minOf(32.dp.toPx(), size.height), size.height)
                    val top = (size.height * thumb.first).coerceIn(0f, (size.height - height).coerceAtLeast(0f))
                    drawRoundRect(colors.outlineVariant, Offset(left, 0f), Size(width, size.height), CornerRadius(width))
                    drawRoundRect(colors.primary.copy(alpha = 0.7f), Offset(left, top), Size(width, height), CornerRadius(width))
                }
            }
            val error = pages.loadState.refresh is LoadState.Error || pages.loadState.append is LoadState.Error || pages.loadState.prepend is LoadState.Error
            if (error) TextButton(onClick = pages::retry, modifier = Modifier.align(Alignment.BottomCenter).background(colors.surface)) {
                Text(stringResource(R.string.dialog_text_retry))
            }
        }
        footer()
    }
}
