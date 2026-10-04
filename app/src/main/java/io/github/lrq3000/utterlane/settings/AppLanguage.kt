package io.github.lrq3000.utterlane.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import java.util.Collections
import java.util.WeakHashMap

/** Platform-owned on Android 13+; synchronous startup preference on older OSes. */
object AppLanguage {
    private val contexts = Collections.newSetFromMap(WeakHashMap<Context, Boolean>())
    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        AppLanguageCatalog.normalize(context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language)
    } else AppLanguageCatalog.normalize(context.getSharedPreferences("app-language", Context.MODE_PRIVATE).getString("locale", ""))

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        return context.createConfigurationContext(configuration(context)).also { contexts.add(it) }
    }

    private fun configuration(context: Context): Configuration = Configuration(context.resources.configuration).apply {
        val tag = selected(context)
        // Resources.getSystem is not the application's overridden configuration.
        // This also restores the full system fallback list on selecting System.
        setLocales(if (tag.isEmpty()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(tag))
    }

    fun set(context: Context, tag: String) {
        require(tag.isEmpty() || AppLanguageCatalog.normalize(tag) == tag)
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        else {
            context.getSharedPreferences("app-language", Context.MODE_PRIVATE).edit().putString("locale", tag).apply()
            refresh(context)
        }
    }

    @Suppress("DEPRECATION")
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        // Service/IME contexts outlive Settings. Updating these weakly-held
        // resources makes the next overlay/notification use the chosen language.
        for (target in contexts.toList() + context.applicationContext) {
            target.resources.updateConfiguration(configuration(target), target.resources.displayMetrics)
        }
    }
}

open class LocalizedActivity : ComponentActivity() {
    private var applied = ""
    override fun attachBaseContext(newBase: Context) {
        applied = AppLanguage.selected(newBase)
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && applied != AppLanguage.selected(this)) recreate()
    }
}
