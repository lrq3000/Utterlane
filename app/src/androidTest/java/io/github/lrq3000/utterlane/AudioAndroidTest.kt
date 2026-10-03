package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.AudioSegmenter
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real codec/capture/native-ASR evidence on an adb target, not mocked Android calls. */
@RunWith(AndroidJUnit4::class)
class AudioAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp

    @Before fun prepareFixtures() {
        // AGP's connected-test installer may recreate app data. Fixtures live on
        // shared test storage; copy them into private model storage for each run.
        for (permission in listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.RECORD_AUDIO")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} $permission")).use { it.readBytes() }
        }
        val directory = File(app.filesDir, "parakeet-v3").apply { mkdirs() }
        for (name in listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")) {
            val destination = File(directory, name)
            if (!destination.exists()) {
                val fixture = File("/sdcard/Download/parakeet-qa", name)
                assertTrue("Missing model fixture $fixture", fixture.isFile)
                fixture.copyTo(destination)
            }
        }
        app.modelManager.checkModelStatus()
    }

    @Test fun twoHourFileDecodesWithBoundedAudioMemory(): Unit = runBlocking {
        val file = File(app.cacheDir, "qa-two-hours.wav")
        try {
            val block = ShortArray(3200) { 1000 }
            WavFile(file).use { wav -> repeat(36000) { wav.append(block) } }
            var end = 0L
            var windows = 0
            var peakJava = 0L
            val segmenter = AudioSegmenter { window ->
                assertTrue(window.samples.size <= 192000)
                assertEquals(end, window.ownedStart)
                end = window.ownedEnd
                windows++
                val runtime = Runtime.getRuntime()
                peakJava = maxOf(peakJava, runtime.totalMemory() - runtime.freeMemory())
            }
            AudioDecoder(app).decode(file.absolutePath, { segmenter.accept(it) })
            segmenter.finish()
            assertEquals(7200L * 16000, end)
            assertEquals(720, windows)
            assertTrue("Java peak was $peakJava", peakJava < 64 * 1024 * 1024)
            android.util.Log.i("AudioAndroidTest", "Two-hour decode: samples=$end windows=$windows peakJava=$peakJava")
        } finally { file.delete() }
    }

    @Test fun shortSpokenFileUsesRealParakeet(): Unit = runBlocking {
        assertTrue("Install Parakeet v3 test model first", app.modelManager.isModelReady())
        val source = File("/sdcard/Download/speech-source.wav")
        assertTrue("Push the spoken fixture first", source.isFile)
        var emitted = 0
        val session = app.recognizerManager.createSession { emitted++ }
        AudioDecoder(app).decode(source.absolutePath, { session.accept(it) })
        session.finish()
        val text = session.store.readForTransfer()!!
        assertTrue(text.lowercase().contains("country"))
        assertTrue(emitted > 0)
        android.util.Log.i("AudioAndroidTest", "Real Parakeet short result: $text")
    }

    @Test fun microphoneCreatesHistoryOnlyWhenEnabled() = runBlocking {
        val previous = app.settingsRepository.historyRetention.first()
        try {
            for (policy in listOf(HistoryRetention.HOUR, HistoryRetention.NONE)) {
                app.settingsRepository.setHistoryRetention(policy)
                app.recordingHistory.prune(policy)
                val ready = CountDownLatch(1)
                val complete = CountDownLatch(1)
                val result = AtomicReference<TranscriptStore?>()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                lateinit var microphone: MicrophoneSession
                instrumentation.runOnMainSync {
                    microphone = MicrophoneSession(app, scope, onText = { _, _ -> },
                        onComplete = { store, _ -> result.set(store); complete.countDown() }, onReady = { ready.countDown() })
                    microphone.start()
                }
                try {
                    assertTrue("Microphone initialization timed out", ready.await(60, TimeUnit.SECONDS))
                    Thread.sleep(1500)
                    microphone.stop()
                    assertTrue("Microphone stop/drain timed out", complete.await(60, TimeUnit.SECONDS))
                    assertNotNull(result.get())
                    val entries = app.recordingHistory.list()
                    if (policy == HistoryRetention.HOUR) {
                        assertTrue(entries.isNotEmpty())
                        assertTrue(entries.first().samples > 0)
                        assertTrue(entries.first().part(0).length() > 44)
                    } else {
                        assertTrue(entries.isEmpty())
                        assertFalse(File(app.filesDir, "microphone-history").walkTopDown().any { it.extension == "wav" })
                    }
                    android.util.Log.i("AudioAndroidTest", "Microphone policy ${policy.key}: completed, entries=${entries.size}")
                } finally { microphone.cancel(); scope.cancel() }
            }
        } finally { app.settingsRepository.setHistoryRetention(previous) }
    }

    @Test fun openingHistoryUsesPersistedPolicyNotPlaceholder(): Unit = runBlocking {
        app.settingsRepository.setHistoryRetention(HistoryRetention.HOUR)
        val saved = app.recordingHistory.begin(HistoryRetention.HOUR)!!
        saved.append(ShortArray(16000) { 1000 })
        saved.finish(false)
        app.startActivity(android.content.Intent(app, io.github.lrq3000.utterlane.settings.SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        clickText(app.getString(R.string.history_title))
        Thread.sleep(500)
        assertTrue("Opening history deleted a fresh recording", saved.entry.directory.exists())
        assertTrue(app.recordingHistory.list().any { it.id == saved.entry.id })
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test fun savedHistoryPlaysAndRetranscribesThroughUi(): Unit = runBlocking {
        app.settingsRepository.setHistoryRetention(HistoryRetention.HOUR)
        val recording = app.recordingHistory.begin(HistoryRetention.HOUR)!!
        AudioDecoder(app).decode("/sdcard/Download/speech-source.wav", { recording.append(it) })
        recording.finish(false)
        app.startActivity(android.content.Intent(app, io.github.lrq3000.utterlane.settings.SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        clickText(app.getString(R.string.history_title))
        clickText(app.getString(R.string.history_play))
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        var playing = false
        repeat(30) { if (!playing) { playing = audio.isMusicActive; if (!playing) Thread.sleep(100) } }
        assertTrue("Saved WAV did not start playback", playing)
        clickText(app.getString(R.string.history_retranscribe))
        var recognized = false
        repeat(200) { if (!recognized) { recognized = findTextContaining("country") != null; if (!recognized) Thread.sleep(100) } }
        assertTrue("History retranscription did not display the spoken text", recognized)
        assertTrue(recording.entry.directory.exists())
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test fun finalResultOwnershipOutlivesCancelledCaller(): Unit = runBlocking {
        val directory = File(app.cacheDir, "transcripts").apply { mkdirs() }
        val store = TranscriptStore(File.createTempFile("delivery-test-", ".txt", directory))
        store.append("recover after the caller closes")
        val started = CountDownLatch(1)
        val allowDelivery = CountDownLatch(1)
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        caller.launch {
            io.github.lrq3000.utterlane.service.TranscriptFinalization.deliver(app, store) {
                withContext(Dispatchers.IO) { started.countDown(); allowDelivery.await(10, TimeUnit.SECONDS) }
                false // The caller disappeared before its final result could be delivered.
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            caller.cancel()
            allowDelivery.countDown()
            var recovered = false
            repeat(100) {
                if (!recovered) {
                    recovered = app.getSharedPreferences("transcript_recovery", 0).getString("path", null) == store.file.absolutePath
                    if (!recovered) Thread.sleep(50)
                }
            }
            assertTrue("Caller cancellation lost its recovery result", recovered)
            assertEquals("recover after the caller closes", store.file.readText())
            io.github.lrq3000.utterlane.asr.CacheArtifacts.deleteWhenReleased(store.file)
            assertFalse("Delivery stranded an active transcript owner", store.file.exists())
        } finally { allowDelivery.countDown(); caller.cancel(); store.dispose() }
    }

    @Test fun busyAndCancelledMicrophoneSessionsReleaseOwnership(): Unit = runBlocking {
        app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val ready = CountDownLatch(1)
        val busy = CountDownLatch(1)
        val busyError = AtomicReference<String?>()
        lateinit var first: MicrophoneSession
        instrumentation.runOnMainSync {
            first = MicrophoneSession(app, firstScope, onText = { _, _ -> }, onComplete = { _, _ -> }, onReady = { ready.countDown() })
            first.start()
        }
        assertTrue(ready.await(60, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            MicrophoneSession(app, firstScope, onText = { _, _ -> }, onComplete = { _, error ->
                busyError.set(error?.message)
                busy.countDown()
            }).start()
        }
        assertTrue(busy.await(5, TimeUnit.SECONDS))
        assertEquals(app.getString(R.string.stream_busy), busyError.get())
        first.cancel()
        withTimeout(10000) { firstScope.coroutineContext[Job]!!.children.forEach { it.join() } }
        firstScope.cancel()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val nextReady = CountDownLatch(1)
        val done = CountDownLatch(1)
        lateinit var next: MicrophoneSession
        instrumentation.runOnMainSync {
            next = MicrophoneSession(app, nextScope, onText = { _, _ -> }, onComplete = { _, _ -> done.countDown() }, onReady = { nextReady.countDown() })
            next.start()
        }
        try {
            assertTrue("Cancelled session still owns microphone", nextReady.await(10, TimeUnit.SECONDS))
            next.stop()
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally { next.cancel(); nextScope.cancel() }
    }

    @Test fun historyWriteFailureWarnsButDoesNotStopLiveCapture(): Unit = runBlocking {
        app.settingsRepository.setHistoryRetention(HistoryRetention.HOUR)
        val root = File(app.filesDir, "microphone-history")
        app.recordingHistory.initialize()
        val before = root.listFiles()!!.map { it.name }.toSet()
        val warning = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val done = CountDownLatch(1)
        val directory = AtomicReference<File?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var microphone: MicrophoneSession
        instrumentation.runOnMainSync {
            microphone = MicrophoneSession(app, scope, onText = { _, _ -> },
                onComplete = { _, _ -> done.countDown() }, onCaptureEnded = { ended.countDown() },
                onWarning = { warning.countDown() }, onReady = {
                    // Deny creation of the first WAV while leaving transcript and
                    // capture storage intact: a real Android writer failure.
                    val created = root.listFiles()!!.single { it.name !in before }
                    directory.set(created)
                    check(created.setWritable(false))
                })
            microphone.start()
        }
        try {
            assertTrue("Writer failure was not reported immediately", warning.await(15, TimeUnit.SECONDS))
            assertFalse("Optional history failure stopped live capture", ended.await(1, TimeUnit.SECONDS))
            directory.get()?.setWritable(true)
            microphone.stop()
            assertTrue(done.await(60, TimeUnit.SECONDS))
        } finally {
            directory.get()?.setWritable(true)
            microphone.cancel(); scope.cancel()
        }
    }

    private fun findTextContaining(text: String): android.view.accessibility.AccessibilityNodeInfo? {
        // Traverse Compose semantics instead of depending on virtual-node text search.
        val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString()?.contains(text, true) == true) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }
    private fun clickText(text: String) {
        repeat(50) {
            val node = findTextContaining(text)
            if (node != null && (if (node.isClickable) node else node.parent)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
            Thread.sleep(100)
        }
        fail("UI action was unavailable: $text")
    }
}
