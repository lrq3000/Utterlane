package io.github.lrq3000.utterlane.transcribe

import android.content.Intent
import android.net.Uri
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ImportScreenState(val receipt: ImportReceipt? = null, val busy: Boolean = false,
    val loaded: Boolean = false, val question: ImportRetentionRequest? = null, val problem: Problem? = null) {
    enum class Problem { FILE, DUPLICATE, STORAGE }
}

/** One copy producer per confirmed selection, independent of Activity instances. Only user actions may start a producer or request the foreground service. */
internal class ImportBatches(private val app: UtterlaneApp, private val store: ImportBatchStore) {
    private val sessions = mutableMapOf<String, Session>()
    private val summaries = MutableStateFlow<List<ImportReceipt>>(emptyList())
    val pending = summaries.asStateFlow()
    private val resolver get() = app.contentResolver
    private val queue get() = app.transcriptionQueue
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private class DuplicateSelection : Exception()
    @Synchronized fun refresh() {
        store.releaseUnused { resolver.releasePersistableUriPermission(Uri.parse(it), read) }
        summaries.value = store.ids().mapNotNull(store::get)
    }
    private fun take(uriString: String, existing: MutableSet<Uri>) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return
        store.takeGrant(uriString, { uri in existing }) {
            resolver.takePersistableUriPermission(uri, read)
            existing.add(uri)
        }
    }
    private fun grants() = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri }
    fun session(id: String): Session = sessions.getOrPut(id) { Session(id) }
    suspend fun requireRecoverableForQuit() {
        val unfinished = sessions.values.mapNotNull { it.state.value.receipt }.filterNot { it.done }.map { it.id }
        if (unfinished.isEmpty()) return
        withContext(Dispatchers.IO) {
            val retained = grants()
            if (unfinished.any { id -> store.uncopiedSources(id).map(Uri::parse).any { uri ->
                    when (uri.scheme) {
                        "content" -> uri !in retained
                        "file" -> uri.path?.let { java.io.File(it).canRead() } != true
                        else -> true
                    }
                } }) throw QuitNeedsImportGrant()
        }
    }

    inner class Session(private val id: String) {
        private val mutable = MutableStateFlow(ImportScreenState())
        val state = mutable.asStateFlow()
        private var work: Job? = null
        @Volatile private var stopAfterCopy = false

        fun attach() {
            if (state.value.loaded || work?.isActive == true) return
            work = app.applicationScope.launch {
                try {
                    queue.initialized()
                    val item = withContext(Dispatchers.IO) { store.get(id) }
                    mutable.value = ImportScreenState(item, loaded = true, problem = if (item?.deferred == true) ImportScreenState.Problem.FILE else null)
                } catch (_: Exception) { mutable.value = ImportScreenState(problem = ImportScreenState.Problem.STORAGE) }
            }
        }
        fun start(uris: List<Uri>, picker: Boolean, speakers: Boolean) = process {
            if (store.get(id) == null) {
                val sources = uris.map(Uri::toString)
                store.create(id, sources, speakers)
                if (picker) synchronized(store) { grants().let { held -> sources.forEach { take(it, held) } } }
            }
        }
        fun resume() = process(retry = state.value.receipt?.jobId) {}
        fun choose(choice: ImportRetentionChoice, remember: Boolean) {
            val category = state.value.question?.category ?: return
            process(retry = state.value.receipt?.jobId) {
                store.answer(id, category, choice)
                if (remember) app.settingsRepository.rememberImportRetention(category, choice)
            }
        }
        fun skip() {
            val item = state.value.receipt ?: return
            process { if (!queue.resumeStaged(item)) store.advance(item, accepted = false) }
        }
        fun replace(uri: Uri, expectedJob: String) {
            val item = state.value.receipt?.takeIf { !it.done && it.jobId == expectedJob } ?: return
            process {
                if (!queue.resumeStaged(item)) {
                    if (!store.replace(item, uri.toString())) throw DuplicateSelection()
                    synchronized(store) { take(uri.toString(), grants()) }
                }
            }
        }
        fun stop() {
            stopAfterCopy = true
            if (work?.isActive != true) forget()
        }
        fun forget() {
            // Serialize cleanup after any publication in progress. This drops only the remaining receipt and its unused grants, never accepted queue jobs.
            app.applicationScope.launch {
                work?.join()
                withContext(Dispatchers.IO) { store.forget(id); refresh() }
                ImportRecoveryNotice(app).clearBatch(id)
                sessions.remove(id, this@Session)
            }
        }
        fun detachDraft() {
            if (!state.value.busy && state.value.receipt == null) sessions.remove(id, this)
        }
        private fun process(retry: String? = null, prepare: suspend () -> Unit) {
            if (work?.isActive == true || stopAfterCopy) return
            if (!queue.beginStaging()) return
            ImportRecoveryNotice(app).clearBatch(id)
            mutable.value = state.value.copy(busy = true, question = null, problem = null)
            work = app.applicationScope.launch {
                try {
                    queue.initialized()
                    mutable.value = withContext(Dispatchers.IO) {
                        prepare()
                        val actions = DialogAudioActions(app)
                        var question: ImportRetentionRequest? = null
                        val last = store.runPass(id, retry, { stopAfterCopy || queue.isQuitting }) { item ->
                            refresh()
                            mutable.value = ImportScreenState(item, busy = true, loaded = true)
                            if (queue.resumeStaged(item)) return@runPass true
                            val source = try { actions.inspect(Uri.parse(checkNotNull(item.uri)), null) }
                                catch (e: Exception) { throw ImportSourceFailure(e) }
                            val preferences = app.settingsRepository.importRetentionPreferences.first()
                            val decision = ImportRetentionPolicy.decide(source.metadata.durationMs, source.metadata.sourceBytes, preferences)
                            val choice = when (decision) {
                                is ImportRetentionDecision.Use -> decision.choice
                                is ImportRetentionDecision.Ask -> item.choices[decision.category] ?: run {
                                    question = ImportRetentionRequest(decision.category, source.metadata)
                                    return@runPass false
                                }
                            }
                            queue.enqueue(item, source, choice)
                            true
                        }
                        refresh()
                        if (!stopAfterCopy && (last.deferred || question != null)) ImportRecoveryNotice(app).batch(last)
                        ImportScreenState(last, loaded = true, question = question,
                            problem = if (last.deferred && question == null) ImportScreenState.Problem.FILE else null)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: DuplicateSelection) { mutable.value = state.value.copy(busy = false, problem = ImportScreenState.Problem.DUPLICATE) }
                catch (e: Exception) {
                    val item = withContext(Dispatchers.IO) { runCatching { store.get(id) }.getOrNull() }
                    mutable.value = ImportScreenState(item, loaded = true, problem = ImportScreenState.Problem.STORAGE)
                    if (item != null) ImportRecoveryNotice(app).batch(item)
                } finally {
                    queue.endStaging()
                    if (stopAfterCopy) forget()
                }
            }
        }
    }
}

internal class QuitNeedsImportGrant : IllegalStateException("An unfinished import still needs its transient read grant")
