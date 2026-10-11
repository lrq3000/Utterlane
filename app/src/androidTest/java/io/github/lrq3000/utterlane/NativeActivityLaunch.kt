package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Wait for this launch's actual RESUMED callback, then let each test assert its
 * own visible controls/state. API-28 startActivitySync can time out waiting for
 * its idle completion even with a resumed dialog and a polling Main looper. */
internal suspend fun launchNativeActivity(intent: Intent): Activity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val request = UUID.randomUUID().toString()
    val launched = CompletableDeferred<Activity>()
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    var created: Activity? = null
    var delivered = false
    val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity.intent.getStringExtra("native_qa_launch") == request) {
            created = activity
            if (stage == Stage.RESUMED) launched.complete(activity)
        }
    }
    try {
        instrumentation.runOnMainSync {
            monitor.addLifecycleCallback(callback)
            instrumentation.targetContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("native_qa_launch", request))
        }
        return withTimeout(10000) { launched.await() }.also { delivered = true }
    } finally {
        instrumentation.runOnMainSync {
            monitor.removeLifecycleCallback(callback)
            if (!delivered) created?.finish()
        }
    }
}
