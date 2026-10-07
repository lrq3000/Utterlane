package io.github.lrq3000.utterlane.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import kotlinx.coroutines.launch

@Composable
fun VisualRefreshSetting(repository: SettingsRepository) {
    val rate by repository.visualRefreshRate.collectAsStateWithLifecycle(initialValue = VisualRefreshRate.DEFAULT)
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(stringResource(R.string.visual_refresh_title)) },
        supportingContent = { Text(stringResource(R.string.visual_refresh_description)) },
        trailingContent = { Text(stringResource(R.string.visual_refresh_rate, rate)) },
        modifier = Modifier.clickable { choosing = true })
    if (choosing) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.visual_refresh_title)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            SettingsRepository.VISUAL_REFRESH_RATES.forEach { hz ->
                TextButton(onClick = { scope.launch { repository.setVisualRefreshRate(hz) }; choosing = false }) {
                    RadioButton(selected = hz == rate, onClick = null)
                    Text(stringResource(R.string.visual_refresh_rate, hz))
                }
            }
        } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.overlay_cancel)) } })
}
