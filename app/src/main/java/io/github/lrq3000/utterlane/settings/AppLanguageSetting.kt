package io.github.lrq3000.utterlane.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.lrq3000.utterlane.R
import java.util.Locale

@Composable
fun AppLanguageSetting() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val selected = AppLanguage.selected(context)
    val system = stringResource(R.string.app_language_system)
    fun name(tag: String): String = if (tag.isEmpty()) system else Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
    ListItem(modifier = Modifier.clickable { open = true }, headlineContent = { Text(stringResource(R.string.app_language)) }, supportingContent = { Text(name(selected)) },
        trailingContent = { TextButton(onClick = { open = true }) { Text(stringResource(R.string.history_change)) } })
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.app_language)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            (listOf("") + AppLanguageCatalog.tags).forEach { tag ->
                TextButton(onClick = {
                    open = false
                    if (tag != selected) {
                        AppLanguage.set(context, tag)
                        if (Build.VERSION.SDK_INT < 33) context.activity()?.recreate()
                    }
                }) { Text((if (tag == selected) "✓ " else "") + name(tag)) }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } })
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
