package io.github.lrq3000.utterlane.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lrq3000.utterlane.R

private const val REPOSITORY = "github.com/lrq3000/Utterlane"

@Composable
internal fun AboutSettings() {
    val context = LocalContext.current
    val version = remember(context) {
        // Use the installed package rather than a copied release string; debug and store variants can differ.
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    SettingsSection(title = stringResource(R.string.app_name)) {
        ListItem(headlineContent = { Text(stringResource(R.string.settings_about_version, version ?: stringResource(R.string.settings_about_version_unknown))) },
            supportingContent = { Text(stringResource(R.string.settings_about_open_source)) })
        ListItem(headlineContent = { Text(stringResource(R.string.settings_about_author), style = MaterialTheme.typography.bodyMedium) },
            supportingContent = { Text("Stephen Karl Larroque") })
        ListItem(headlineContent = { Text(stringResource(R.string.settings_about_license), style = MaterialTheme.typography.bodyMedium) },
            supportingContent = { Text("Apache-2.0") })
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_about_repository)) },
            supportingContent = { Text(REPOSITORY) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
            modifier = Modifier.testTag("settings_about_github").clickable {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$REPOSITORY"))) }
                catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.error_no_browser, Toast.LENGTH_LONG).show() }
            })
        ListItem(headlineContent = { Text(stringResource(R.string.settings_about_origin)) },
            supportingContent = { Text(stringResource(R.string.settings_about_on_device)) })
    }
}
