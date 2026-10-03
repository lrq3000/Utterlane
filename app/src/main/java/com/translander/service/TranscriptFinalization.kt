package com.translander.service

import android.content.Context
import android.util.Log
import com.translander.TranslanderApp
import com.translander.asr.TranscriptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Final-result ownership outlives an activity/service destroyed during delivery. */
object TranscriptFinalization {
    fun deliver(context: Context, store: TranscriptStore?, send: suspend () -> Boolean) {
        TranslanderApp.instance.applicationScope.launch {
            var delivered = false
            try { delivered = send() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e("TranscriptFinalization", "Result delivery failed", e) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (store != null) {
                        if (delivered || store.file.length() == 0L) store.dispose()
                        else {
                            store.keepForRecovery()
                            withContext(Dispatchers.Main) { TranscriptRecovery.show(context, store) }
                        }
                    }
                }
            }
        }
    }
}
