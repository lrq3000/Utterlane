package io.github.lrq3000.utterlane

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.archive.*
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run in an isolated model-free QA installation. No model/native fixture needed.
 * The optional real-volume case accepts -e archivalTreeUri <persisted tree URI>. */
@RunWith(AndroidJUnit4::class)
class ManagedArchivalAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private fun folder(id: String = "root") = DocumentsContract.buildTreeDocumentUri("${app.packageName}.archive-test", id)
    private fun control(method: String, argument: String? = null) = app.contentResolver.call(folder(), "fixture.$method", argument, null)
    private fun targetRecords(journal: ArchivalJobStore, id: String) = mutableListOf<ArchivalCleanup>().apply { journal.forEachCleanup(id, ::add) }
    private fun settle(journal: ArchivalJobStore) { while (journal.maintain()) { } }
    private fun cleanup(journal: ArchivalJobStore, provider: SafArtifacts) {
        settle(journal); journal.beginCleanupPass(); settle(journal)
        while (true) {
            val target = journal.nextCleanup() ?: break
            ManagedArtifactCleanup(journal, provider).run(target) { true }
        }
    }

    private fun documentExists(uri: String): Boolean {
        val target = Uri.parse(uri)
        val id = DocumentsContract.getDocumentId(target)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(target, DocumentsContract.getTreeDocumentId(target))
        // Query the still-accessible parent. Querying a deleted child URI can
        // throw "not a descendant" before reaching the provider's queryDocument.
        return checkNotNull(app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null))
            .use { rows ->
                check(!rows.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
                check(rows.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty())
                var found = false
                while (rows.moveToNext()) if (rows.getString(0) == id) found = true
                found
            }
    }

    @Test fun safWorkerWritesFullMarkdownAndLinkedCleanupPreservesUnrelatedDocument() = runBlocking {
        exerciseFolder(folder())
    }

    @Test fun connectionDeadlineIsRetryableAndCannotStrandARunningJob() = runBlocking {
        val root = File(app.cacheDir, "archive-connect-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "source").apply { writeText("words") }
        val journal = ArchivalJobStore(File(root, "jobs"))
        journal.enqueue(ArchivalRequest(ArtifactOwner(ArtifactKind.TRANSCRIPT, "owner"), ArchivalOperation.SAVE_MARKDOWN,
            source, folder().toString(), "text.md", "text/markdown"))
        val job = checkNotNull(journal.claim())
        val client = ArchiveWorkerClient(app, connectionTimeoutMs = 0)
        try {
            try { client.execute(job) { fail("No target may be created before handshake"); false }; fail("Expected connection failure") }
            catch (expected: ArchiveWorkerFailure) {
                assertEquals(false, expected.creationStarted)
                journal.notCreated(job.id, job.generation)
                journal.pause(job.id, job.generation, expected.message.orEmpty())
            }
            assertEquals(ArchivalState.PAUSED, journal.job(job.id)?.state)
            assertTrue(targetRecords(journal, job.id).isEmpty())
            assertEquals("words", source.readText())
        } finally { withContext(Dispatchers.Main) { client.stopAndKill() }; journal.releaseExecution(job.id, job.generation); journal.close(); root.deleteRecursively() }
    }

    @Test fun realPersistedFolderRetainsGrantAndProducesRemovableMarkdown() = runBlocking {
        val argument = InstrumentationRegistry.getArguments().getString("archivalTreeUri")
        assumeTrue("Supply archivalTreeUri after selecting a real folder", argument != null)
        val uri = Uri.parse(argument)
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
        exerciseFolder(uri)
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission })
    }

    private suspend fun exerciseFolder(folder: Uri) {
        val root = File(app.cacheDir, "archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "complete.txt").apply {
            bufferedWriter().use { out -> repeat(20000) { out.write("Speaker 1: héllo 世界\n\nSpeaker 2: faithful words.\n") } }
        }
        val journal = ArchivalJobStore(File(root, "journal"))
        val texts = TranscriptHistory(File(root, "history"), journal)
        val text = texts.save(source, "Actual model", pinned = true)
        texts.planArchive(text.id, ArchivePlan(folder.toString(), journal.currentEpoch()))
        val job = checkNotNull(journal.claim())
        val client = ArchiveWorkerClient(app)
        val provider = SafArtifacts(app)
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val unrelated = checkNotNull(DocumentsContract.createDocument(app.contentResolver, parent, "text/plain", "unrelated-${UUID.randomUUID()}.txt"))
        try {
            client.execute(job) { uri -> withContext(Dispatchers.IO) { journal.target(job.id, job.generation, uri) } }
            journal.ready(job.id, job.generation)
            assertTrue(texts.publishArchive(job))
            val artifacts = mutableListOf<PublishedArtifact>().apply { journal.forEachPublished(ArtifactOwner(ArtifactKind.TRANSCRIPT, text.id), ::add) }
            val saved = artifacts.single().uri
            assertEquals("text/markdown", job.request.mimeType)
            assertTrue(job.targetName.endsWith(".md"))
            val reportedMime = app.contentResolver.getType(Uri.parse(saved))
            val platformMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("md")
            // API-28 Downloads derives raw-document MIME from its extension map,
            // which can lack md, rather than remembering createDocument's MIME.
            // Keep the test-provider assertion strict; validate this platform
            // fallback only for the real Downloads provider, plus the full body.
            val expected = if (folder.authority == "com.android.providers.downloads.documents")
                setOf("text/markdown", platformMime ?: "application/octet-stream") else setOf("text/markdown")
            android.util.Log.i("ArchivalQA", "requested=${job.request.mimeType}, platformMd=$platformMime, provider=$reportedMime")
            assertTrue("Unexpected provider MIME: $reportedMime", reportedMime in expected)
            app.contentResolver.openInputStream(Uri.parse(saved))!!.bufferedReader().use { reader ->
                assertEquals("# Transcript", reader.readLine()); assertEquals("", reader.readLine())
                assertTrue(reader.readLine().startsWith("Date: ")); assertEquals("Model: Actual model", reader.readLine()); assertEquals("", reader.readLine())
                source.bufferedReader().use { original -> while (true) { val line = original.readLine() ?: break; assertEquals(line, reader.readLine()) } }
                assertNull(reader.readLine())
            }
            texts.prune(HistoryRetention.NONE)
            assertNotNull(texts.find(text.id)) // Pin protects linked Markdown.
            texts.delete(text.id)
            cleanup(journal, provider)
            assertTrue(targetRecords(journal, job.id).isEmpty())
            assertFalse("Linked Delete must remove the actual provider document", documentExists(saved))
            // Exercise ordinary expiry on the actual selected provider too, not
            // just pin protection and an explicit Delete against the test provider.
            val expiring = texts.save(source, "Expiry model", attempt = "expiry-${UUID.randomUUID()}")
            texts.planArchive(expiring.id, ArchivePlan(folder.toString(), journal.currentEpoch()))
            val expiryJob = checkNotNull(journal.claim())
            try {
                client.execute(expiryJob) { uri -> withContext(Dispatchers.IO) { journal.target(expiryJob.id, expiryJob.generation, uri) } }
                journal.ready(expiryJob.id, expiryJob.generation)
                assertTrue(texts.publishArchive(expiryJob))
                val expiredUri = mutableListOf<PublishedArtifact>().apply {
                    journal.forEachPublished(ArtifactOwner(ArtifactKind.TRANSCRIPT, expiring.id), ::add)
                }.single().uri
                assertTrue(documentExists(expiredUri))
                texts.prune(HistoryRetention.NONE)
                assertNull(texts.find(expiring.id))
                cleanup(journal, provider)
                assertFalse("Pruning must remove the actual linked provider document", documentExists(expiredUri))
            } finally {
                texts.delete(expiring.id)
                journal.releaseExecution(expiryJob.id, expiryJob.generation)
                cleanup(journal, provider)
            }
            app.contentResolver.query(unrelated, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { assertTrue(it.moveToFirst()) }
        } finally {
            withContext(Dispatchers.Main) { client.stopAndKill() }
            texts.delete(text.id)
            journal.releaseExecution(job.id, job.generation)
            cleanup(journal, provider)
            DocumentsContract.deleteDocument(app.contentResolver, unrelated)
            journal.close(); root.deleteRecursively()
        }
    }

    @Test fun revokedProviderFailurePreservesPrivateInputAndRetryUsesFreshGeneration() = runBlocking {
        val root = File(app.cacheDir, "archive-denied-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(root, "input.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val journal = ArchivalJobStore(File(root, "jobs"))
        journal.enqueue(ArchivalRequest(ArtifactOwner(ArtifactKind.AUDIO, "owner"), ArchivalOperation.COPY_AUDIO,
            input, folder().toString(), "audio.wav", "audio/wav"))
        val client = ArchiveWorkerClient(app)
        val first = checkNotNull(journal.claim())
        try {
            control("deny", "true")
            try {
                client.execute(first) { uri -> journal.target(first.id, first.generation, uri) }
                fail("Permission denial should fail the job")
            } catch (expected: java.io.IOException) {
                assertEquals(false, (expected as ArchiveWorkerFailure).creationStarted)
                journal.notCreated(first.id, first.generation)
                journal.pause(first.id, first.generation, expected.message.orEmpty())
                journal.releaseExecution(first.id, first.generation)
            }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), input.readBytes())
            assertEquals(ArchivalState.PAUSED, journal.job(first.id)?.state)
            assertTrue(targetRecords(journal, first.id).isEmpty())
            control("deny", "false")
            journal.retry(first.id)
            val retry = checkNotNull(journal.claim())
            assertTrue(retry.generation > first.generation)
            client.execute(retry) { uri -> journal.target(retry.id, retry.generation, uri) }
            assertTrue(journal.ready(retry.id, retry.generation))
        } finally {
            control("deny", "false")
            withContext(Dispatchers.Main) { client.stopAndKill() }
            journal.cancelAll()
            journal.releaseExecution(first.id, journal.job(first.id)?.generation ?: first.generation)
            cleanup(journal, SafArtifacts(app))
            journal.close(); root.deleteRecursively()
        }
    }

    @Test fun supervisorForceStopKillsHungWorkerAndKeepsParentAndInputAlive() = runBlocking {
        assumeTrue("Use a clean QA archival queue", !app.archival.state.value.hasWork)
        val onboarding = OnboardingRepository(app)
        val previousOnboarding = onboarding.progress.first()
        onboarding.complete()
        val screen = launchNativeActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        control("block")
        val owned = withContext(Dispatchers.IO) {
            app.recordingHistory.importAudioOwned(byteArrayOf(1, 2, 3, 4).inputStream(), "wav", AudioImportMetadata(), ImportRetentionChoice.KEEP)
        }
        val producer = app.archival.producer(manual = true)
        val owner = ArtifactOwner(ArtifactKind.AUDIO, owned.entry.id)
        val parentPid = Process.myPid()
        var jobId: String? = null
        try {
            withContext(Dispatchers.IO) {
                app.recordingHistory.planArchive(owned.entry.id, ArchivePlan(folder("blocked").toString(), producer.epoch))
                jobId = app.archival.journal.enqueue(ArchivalRequest(owner, ArchivalOperation.COPY_AUDIO, owned.entry.part(0),
                    folder("blocked").toString(), "audio.wav", owned.entry.mimeType), producer.epoch).id
            }
            withTimeout(15000) { while ((control("status")?.getInt("blocked") ?: 0) == 0) delay(50) }
            val pid = app.getSystemService(ActivityManager::class.java).runningAppProcesses.first { it.processName == "${app.packageName}:archive" }.pid
            withContext(Dispatchers.Main) { app.archival.forceStopAll() }
            withTimeout(5000) {
                while (app.archival.journal.job(checkNotNull(jobId))?.state?.let { it != ArchivalState.CANCELLED } == true ||
                    app.getSystemService(ActivityManager::class.java).runningAppProcesses.any { it.pid == pid } ||
                    app.archival.state.value.producers != 0) delay(50)
            }
            assertEquals(parentPid, Process.myPid())
            assertTrue(owned.entry.part(0).isFile)
            assertEquals(0, app.archival.state.value.producers)
        } finally {
            control("unblock")
            producer.close()
            withContext(Dispatchers.IO) { app.recordingHistory.delete(owned.entry.id); owned.lease.close() }
            withContext(Dispatchers.Main) { app.archival.retry() }
            withTimeout(15000) { while (jobId?.let { targetRecords(app.archival.journal, it).isNotEmpty() } == true) delay(50) }
            instrumentation.runOnMainSync { screen.finish() }
            onboarding.update { previousOnboarding }
        }
    }
}
