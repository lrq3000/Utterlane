package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Use operation completion/catch paths, never timers or inferred share delivery.
 * Application context lets completed exports report after their Activity leaves. */
internal object ActionFeedback {
    private val main = Handler(Looper.getMainLooper())
    fun show(context: Context, resource: Int) = show(context, context.getString(resource))
    fun show(context: Context, message: String) {
        val app = context.applicationContext
        main.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
    }
}
