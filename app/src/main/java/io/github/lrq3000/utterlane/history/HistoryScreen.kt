package io.github.lrq3000.utterlane.history

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import io.github.lrq3000.utterlane.home.HomeHeader
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.launch
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

/** D's history content is shared by Home and the legacy Activity. The host owns
 * navigation/insets; this screen retains its cached pager and saveable list state. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HistoryScreen(model: HistoryViewModel, transcripts: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val rows = model.pages.collectAsLazyPagingItems()
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val labels = remember(context, locale) { HistoryLabels(context) }
    val listState = rememberLazyListState()
    val title = stringResource(if (transcripts) R.string.transcript_history_title else R.string.history_title)
    Surface(Modifier.fillMaxSize().testTag("history_screen").semantics {
        testTagsAsResourceId = true; paneTitle = title
    }, color = MaterialTheme.colorScheme.background) {
        Column {
            HomeHeader(title, onBack, onSettings = {
                context.startActivity(Intent(context, SettingsActivity::class.java)
                    .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
            })
            if (rows.itemCount > 0 && rows.loadState.refresh is LoadState.Error) {
                HistoryLoadStatus(rows.loadState.refresh, rows::retry)
            }
            if (rows.itemCount == 0) Box(Modifier.weight(1f).fillMaxWidth(),
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
            } else Surface(Modifier.weight(1f, fill = false).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                LazyColumn(Modifier.fillMaxWidth().testTag("history_list"), state = listState) {
                    item(key = "history_prepend") { HistoryLoadStatus(rows.loadState.prepend, rows::retry) }
                    items(count = rows.itemCount, key = rows.itemKey { it.id }) { index ->
                        val entry = rows[index] ?: return@items
                        HistoryEntryRow(entry, transcripts, labels, onOpen = {
                            context.startActivity(Intent(context, TranscribeActivity::class.java)
                                .putExtra(if (transcripts) TranscribeActivity.EXTRA_TRANSCRIPT_ID else TranscribeActivity.EXTRA_AUDIO_ID, entry.id)
                                .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
                        })
                        if (index < rows.itemCount - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item(key = "history_append") { HistoryLoadStatus(rows.loadState.append, rows::retry) }
                }
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

/** One full-row action. The small pin is an indicator; save/unpin stays in detail.
 * Only the preview ellipsizes. Metadata can wrap at large accessibility fonts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryEntryRow(entry: HistoryRow, transcripts: Boolean, labels: HistoryLabels, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val day = remember(entry.cursor, labels) { labels.day(entry.cursor.created) }
    val time = remember(entry.cursor, labels) { labels.time(entry.cursor.created) }
    val duration = remember(entry.durationMs, labels) { labels.duration(entry.durationMs) }
    val pinState = stringResource(when {
        entry.retention.pinned -> R.string.history_pinned
        entry.retention.holdForLaunch != null -> R.string.history_unpin_immediate
        else -> R.string.history_not_pinned
    })
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .testTag("history_entry_${entry.id}")
        .semantics { stateDescription = pinState }
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.history_open), onClick = onOpen)
        .padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(when { entry.recovery -> Icons.Default.Restore; transcripts -> Icons.Default.Description; else -> Icons.Outlined.GraphicEq },
            contentDescription = if (entry.recovery) stringResource(R.string.history_recovery_item) else null,
            tint = colors.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (transcripts) entry.detail else stringResource(R.string.history_audio_recording),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(day)
                    Text("· $time")
                    val unknown = stringResource(R.string.audio_duration_unknown)
                    Text("· $duration", Modifier.semantics {
                        if (entry.durationMs <= 0) contentDescription = unknown
                    })
                    if (entry.speakerLabels) Text("· ${stringResource(R.string.home_speakers)}")
                    // Announced once as the row's retention state, with no extra
                    // focus target or click handler on this decorative indicator.
                    if (entry.retention.pinned) Icon(Icons.Default.PushPin, null,
                        Modifier.size(14.dp).align(Alignment.CenterVertically), tint = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}
