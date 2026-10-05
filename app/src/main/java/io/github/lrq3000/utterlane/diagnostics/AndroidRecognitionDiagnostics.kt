package io.github.lrq3000.utterlane.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File

object AndroidRecognitionDiagnostics {
    fun create(context: Context): LocalRecognitionDiagnostics {
        val app = context.applicationContext
        val log = BoundedDiagnosticLog(DiagnosticRingFiles({ File(app.cacheDir, "recognition-diagnostics") }), metadata = {
            // Invoked lazily by the I/O writer only after an opted-in record arrives.
            // PackageManager IPC/device metadata never runs on the native callback path.
            @Suppress("DEPRECATION")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            DiagnosticEnvironment(info.versionName ?: "unknown", code,
                if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release",
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        }, onFailure = {
            // No exception text/stack: platform I/O exceptions may contain private paths.
            Log.w("RecognitionDiagnostics", "Local diagnostic storage unavailable; records may be dropped")
        })
        return LocalRecognitionDiagnostics(log)
    }
}
