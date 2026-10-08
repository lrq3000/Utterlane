package io.github.lrq3000.utterlane.history

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import io.github.lrq3000.utterlane.ui.BrandHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun HistoryRetention.label(): Int = when (this) {
    HistoryRetention.NONE -> R.string.history_immediate
    HistoryRetention.HOUR -> R.string.history_hour
    HistoryRetention.SIX_HOURS -> R.string.history_six_hours
    HistoryRetention.DAY -> R.string.history_day
    HistoryRetention.WEEK -> R.string.history_week
    HistoryRetention.MONTH -> R.string.history_month
    HistoryRetention.THREE_MONTHS -> R.string.history_three_months
    HistoryRetention.FOREVER -> R.string.history_forever
}

@Composable
fun HistorySettings() {
    val app = UtterlaneApp.instance
    val settings = app.settingsRepository
    val scope = rememberCoroutineScope()
    val audioEnabled by settings.audioHistoryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val audioRetention by settings.audioHistoryRetention.collectAsStateWithLifecycle(initialValue = HistoryRetention.HOUR)
    val textEnabled by settings.transcriptHistoryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val textRetention by settings.transcriptHistoryRetention.collectAsStateWithLifecycle(initialValue = HistoryRetention.DAY)
    val context = LocalContext.current
    HistoryPolicySetting(stringResource(R.string.history_auto_audio), audioEnabled, audioRetention,
        enabled = { scope.launch { settings.setAudioHistoryEnabled(it) } }, duration = { scope.launch { settings.setAudioHistoryRetention(it) } })
    HistoryPolicySetting(stringResource(R.string.history_auto_text), textEnabled, textRetention,
        enabled = { scope.launch { settings.setTranscriptHistoryEnabled(it) } }, duration = { scope.launch { settings.setTranscriptHistoryRetention(it) } })
    Text(stringResource(R.string.history_policy_description), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { context.startActivity(HistoryActivity.intent(context)) }) { Text(stringResource(R.string.history_title)) }
    TextButton(onClick = { context.startActivity(HistoryActivity.intent(context, transcripts = true)) }) { Text(stringResource(R.string.transcript_history_title)) }
    TextButton(onClick = { io.github.lrq3000.utterlane.service.TranscriptRecovery.open(app) }) { Text(stringResource(R.string.stream_recover)) }
}

@Composable
private fun HistoryPolicySetting(title: String, selected: Boolean, retention: HistoryRetention,
    enabled: (Boolean) -> Unit, duration: (HistoryRetention) -> Unit) {
    var choose by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked = selected, onCheckedChange = enabled) })
    ListItem(headlineContent = { Text(stringResource(R.string.history_prune_after)) },
        supportingContent = { Text(stringResource(retention.label())) },
        trailingContent = { TextButton(onClick = { choose = true }) { Text(stringResource(R.string.history_change)) } })
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HistoryRetention.entries.forEach { choice ->
                TextButton(onClick = { duration(choice); choose = false }) {
                    RadioButton(selected = retention == choice, onClick = null)
                    Text(stringResource(choice.label()))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.transcribe_close)) } })
}

/** Reuse formatters for visible labels in the current Activity locale. Creation
 * dates remain independent of unpin time; payload IO stays in the paging source. */
private class HistoryLabels(private val context: Context) {
    private val locale = context.resources.configuration.locales[0]
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    private val times = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    fun day(created: Long): String = when (Instant.ofEpochMilli(created).atZone(zone).toLocalDate()) {
        today -> context.getString(R.string.history_today)
        today.minusDays(1) -> context.getString(R.string.history_yesterday)
        else -> dates.format(Date(created))
    }
    fun time(created: Long): String = times.format(Date(created))
    fun duration(milliseconds: Long): String {
        if (milliseconds <= 0) return "—"
        if (milliseconds < 1000) return context.getString(R.string.history_under_second)
        val seconds = milliseconds / 1000
        return if (seconds < 3600) String.format(locale, "%d:%02d", seconds / 60, seconds % 60)
        else String.format(locale, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}

/** Full-screen B layout, sharing bounded bidirectional paging for both histories. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HistoryScreen(model: HistoryViewModel, transcripts: Boolean, onBack: () -> Unit) {
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = model.pages.collectAsLazyPagingItems()
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val labels = remember(context, locale) { HistoryLabels(context) }
    var message by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    fun action(block: suspend () -> Unit) { scope.launch {
        message = null
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: context.getString(R.string.history_load_failed) }
    } }
    val title = stringResource(if (transcripts) R.string.transcript_history_title else R.string.history_title)
    Surface(Modifier.fillMaxSize().testTag("history_screen").semantics {
        testTagsAsResourceId = true; paneTitle = title
    }, color = MaterialTheme.colorScheme.surface) {
        Column {
            BrandHeader(title, onBack)
            message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (rows.itemCount > 0 && rows.loadState.refresh is LoadState.Error) {
                HistoryLoadStatus(rows.loadState.refresh, rows::retry)
            }
            if (rows.itemCount == 0) Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                contentAlignment = Alignment.Center) {
                if (rows.loadState.refresh is LoadState.NotLoading) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (transcripts) Icons.Default.Description else Icons.Default.Mic, null,
                            Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(if (transcripts) R.string.transcript_history_empty else R.string.history_empty),
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                } else HistoryLoadStatus(rows.loadState.refresh, rows::retry)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp).testTag("history_list"), state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                item(key = "history_prepend") { HistoryLoadStatus(rows.loadState.prepend, rows::retry) }
                items(count = rows.itemCount, key = rows.itemKey { it.id }) { index ->
                    val entry = rows[index] ?: return@items
                    val day = remember(entry.cursor, labels) { labels.day(entry.cursor.created) }
                    // Peek avoids prefetching a neighbor merely to draw a heading.
                    // A dropped leading page can be restored by scrolling upward.
                    if (index == 0 || rows.peek(index - 1)?.let { labels.day(it.cursor.created) } != day) {
                        Text(day, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HistoryEntryRow(entry, transcripts, labels, shaded = index % 2 == 0, onOpen = {
                        context.startActivity(Intent(context, TranscribeActivity::class.java)
                            .putExtra(if (transcripts) TranscribeActivity.EXTRA_TRANSCRIPT_ID else TranscribeActivity.EXTRA_AUDIO_ID, entry.id)
                            .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
                    }, onPin = { pinned -> action {
                        // Persisted preferences, not Compose placeholders,
                        // decide the Immediate-unpin launch safeguard.
                        val duration = if (transcripts) app.settingsRepository.transcriptHistoryRetention.first() else app.settingsRepository.audioHistoryRetention.first()
                        if (transcripts) app.transcriptHistory.setPinned(entry.id, pinned, duration, app.historyCleanup.launchToken)
                        else app.recordingHistory.setPinned(entry.id, pinned, duration, app.historyCleanup.launchToken)
                    } })
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                item(key = "history_append") { HistoryLoadStatus(rows.loadState.append, rows::retry) }
            }
        }
    }
}

/** Append/prepend failures keep the loaded rows available and provide a retry. */
@Composable
private fun HistoryLoadStatus(state: LoadState, retry: () -> Unit) {
    if (state is LoadState.NotLoading) return
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state is LoadState.Loading) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.history_loading), style = MaterialTheme.typography.bodyMedium)
        } else if (state is LoadState.Error) {
            Text(state.error.localizedMessage ?: stringResource(R.string.history_load_failed),
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            TextButton(onClick = retry) { Text(stringResource(R.string.history_retry)) }
        }
    }
}

/** Design B: the row is navigation; its pin is a separately consuming toggle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryEntryRow(entry: HistoryRow, transcripts: Boolean, labels: HistoryLabels, shaded: Boolean,
    onOpen: () -> Unit, onPin: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val time = remember(entry.cursor, labels) { labels.time(entry.cursor.created) }
    val detail = remember(entry, transcripts, labels) { if (transcripts) entry.detail else labels.duration(entry.durationMs) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(8.dp))
        .background(if (shaded) colors.surfaceVariant.copy(alpha = 0.45f) else Color.Transparent)
        .testTag("history_entry_${entry.id}")
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.history_open), onClick = onOpen)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (transcripts) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                    Text(entry.model.orEmpty(), Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Icon(when { entry.recovery -> Icons.Default.Restore; entry.imported -> Icons.Default.Description; else -> Icons.Default.Mic },
                contentDescription = if (entry.recovery) stringResource(R.string.history_recovery_item) else null,
                tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            // FlowRow preserves the compact one-line layout normally, but wraps
            // duration below time instead of clipping them at large font scales.
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(detail, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
        }
        Spacer(Modifier.width(8.dp))
        // Retention information remains available to accessibility services; it
        // does not consume a visible caption row or a persistent feedback banner.
        val pinState = stringResource(when {
            entry.retention.pinned -> R.string.history_pinned
            entry.retention.holdForLaunch != null -> R.string.history_unpin_immediate
            else -> R.string.history_not_pinned
        })
        IconToggleButton(checked = entry.retention.pinned, onCheckedChange = onPin,
            modifier = Modifier.align(if (transcripts) Alignment.Top else Alignment.CenterVertically)
                .size(48.dp).background(if (entry.retention.pinned) colors.primaryContainer else colors.surface, RoundedCornerShape(12.dp))
                .testTag("history_pin_${entry.id}").semantics { stateDescription = pinState }) {
            Icon(if (entry.retention.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(if (entry.retention.pinned) R.string.history_unpin else R.string.history_pin),
                tint = if (entry.retention.pinned) colors.primary else colors.onSurfaceVariant)
        }
    }
}
