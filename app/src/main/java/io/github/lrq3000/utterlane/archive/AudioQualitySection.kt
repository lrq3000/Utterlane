package io.github.lrq3000.utterlane.archive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun AudioQualitySection() {
    val repository = UtterlaneApp.instance.settingsRepository
    val settings by repository.archival.collectAsState(ArchivalSettings())
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by remember(settings.quality) { mutableStateOf(settings.quality) }
    val scope = rememberCoroutineScope()
    val supported by produceState<Set<AudioQuality>>(emptySet()) {
        value = withContext(Dispatchers.IO) {
            buildSet {
                for (format in SavedAudioFormat.entries) for (rate in AudioQuality.SAMPLE_RATES)
                    for (channels in 1..2) for (bitrate in AudioQuality.BIT_RATES) {
                        val quality = AudioQuality(true, format, rate, channels, bitrate)
                        if (quality.validTuple() && (!quality.encoding || AudioEncoderCapabilities.encoder(quality) != null)) add(quality)
                    }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.compression_quality) + if (expanded) " ▴" else " ▾") }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.compression_toggle), Modifier.weight(1f))
                Switch(checked = settings.quality.compress, onCheckedChange = { enabled ->
                    scope.launch { repository.setAudioQuality(settings.quality.copy(compress = enabled)) }
                })
            }
            Text(stringResource(R.string.compression_native_help), style = MaterialTheme.typography.bodySmall)
            if (settings.quality.compress) {
                Text(stringResource(R.string.compression_help), style = MaterialTheme.typography.bodySmall)
                Choice(stringResource(R.string.compression_format), when (draft.format) {
                    SavedAudioFormat.M4A -> "M4A / AAC-LC"
                    SavedAudioFormat.THREE_GP -> "3GP / AMR"
                    SavedAudioFormat.WAV -> stringResource(R.string.compression_wav)
                }, SavedAudioFormat.entries.map { format ->
                    val q = if (format == SavedAudioFormat.THREE_GP) draft.copy(format = format, channels = 1, sampleRate = 16000) else draft.copy(format = format)
                    val label = when (format) {
                        SavedAudioFormat.M4A -> "M4A / AAC-LC"
                        SavedAudioFormat.THREE_GP -> "3GP / AMR"
                        SavedAudioFormat.WAV -> stringResource(R.string.compression_wav)
                    }
                    Triple(label, supported.any { it.format == format }, { draft = q })
                })
                Choice(stringResource(R.string.compression_rate), "${draft.sampleRate} Hz", AudioQuality.SAMPLE_RATES.map { rate ->
                    val q = draft.copy(sampleRate = rate)
                    Triple("$rate Hz", q in supported, { draft = q })
                })
                Choice(stringResource(R.string.compression_channels), stringResource(if (draft.channels == 1) R.string.compression_mono else R.string.compression_stereo), (1..2).map { channels ->
                    val q = draft.copy(channels = channels)
                    Triple(if (channels == 1) stringResource(R.string.compression_mono) else stringResource(R.string.compression_stereo), q in supported, { draft = q })
                })
                if (draft.format == SavedAudioFormat.M4A) Choice(stringResource(R.string.compression_bitrate), "${draft.bitRate / 1000} kbps", AudioQuality.BIT_RATES.map { rate ->
                    val q = draft.copy(bitRate = rate)
                    Triple("${rate / 1000} kbps", q in supported, { draft = q })
                }) else if (draft.format == SavedAudioFormat.THREE_GP) Text("AMR: ${draft.effectiveBitRate} bps")
                if (draft !in supported) Text(stringResource(R.string.compression_unsupported), style = MaterialTheme.typography.bodySmall)
                Button(enabled = draft in supported && draft != settings.quality, onClick = { scope.launch { repository.setAudioQuality(draft) } }) {
                    Text(stringResource(R.string.compression_apply))
                }
            }
            Text(stringResource(R.string.compression_snapshot_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun Choice(label: String, value: String, choices: List<Triple<String, Boolean, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $value") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (name, enabled, select) -> DropdownMenuItem(text = { Text(name) }, enabled = enabled,
                onClick = { select(); open = false }) }
        }
    }
}
