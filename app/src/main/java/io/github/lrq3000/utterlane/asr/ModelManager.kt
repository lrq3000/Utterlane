package io.github.lrq3000.utterlane.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Catalog-based private storage. Only verified, fully published artifacts are loadable. */
class ModelManager(private val context: Context, private val client: OkHttpClient? = null, private val fixedModel: ModelDefinition? = null) {
    companion object {
        private const val TAG = "ModelManager"

        internal fun clientForDownload(client: OkHttpClient, options: RuntimeOptions): OkHttpClient {
            options.requireValid()
            // Clone configuration, not infrastructure: keep injected interceptors,
            // dispatcher, connection pool and cancellation behavior for all artifacts.
            return client.newBuilder()
                .connectTimeout(options.downloadConnectSeconds, TimeUnit.SECONDS)
                .readTimeout(options.downloadReadSeconds, TimeUnit.SECONDS)
                .build()
        }

        @OptIn(InternalCoroutinesApi::class)
        internal suspend fun openDownload(call: Call, publish: (Call) -> Unit = {}): DownloadBody {
            val owner = currentCoroutineContext()
            // A normal completion callback runs too late: the IO coroutine cannot
            // complete until a body read unblocks. Observe the *cancelling* phase,
            // capturing this call before publication and handling already-cancelled
            // owners immediately. Never resolve activeCall from the callback.
            val cancellation = owner[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) call.cancel()
            }
            var response: Response? = null
            try {
                owner.ensureActive()
                publish(call)
                owner.ensureActive()
                val opened = awaitHeaders(call)
                response = opened
                owner.ensureActive()
                val body = opened.body ?: error("Empty model response")
                return DownloadBody(opened, body.byteStream(), cancellation)
            } catch (failure: Throwable) {
                try { response?.close() } finally { cancellation?.dispose() }
                throw failure
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun awaitHeaders(call: Call): Response = suspendCancellableCoroutine { continuation ->
            // Call.cancel interrupts sockets, not arbitrary OS DNS resolvers.
            // Suspend the owner while OkHttp resolves; retain openDownload's
            // owner hook after delivery to protect subsequent blocking body reads.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) = continuation.resumeWithException(error)
                override fun onResponse(call: Call, response: Response) {
                    // A cancelled owner may never receive the successful callback.
                    continuation.resume(response, onCancellation = { response.close() })
                }
            })
        }
    }

    internal class DownloadBody(private val response: Response, input: InputStream, private val cancellation: DisposableHandle?) :
        java.io.FilterInputStream(input) {
        val code: Int get() = response.code
        fun header(name: String): String? = response.header(name)
        override fun close() {
            // Receiving headers only transfers ownership to the body; keep cancellation armed through the final read.
            try { response.close() } finally { cancellation?.dispose() }
        }
    }
    enum class ErrorType { NETWORK, CHECKSUM_MISMATCH, MISSING_FILE, FOLDER_ACCESS, STORAGE, UNKNOWN }
    sealed class DownloadState {
        data object NotStarted : DownloadState()
        data class Downloading(val progress: Int, val connecting: Boolean = false) : DownloadState()
        data class Copying(val progress: Int) : DownloadState()
        data object Extracting : DownloadState()
        data object Ready : DownloadState()
        data class Error(val type: ErrorType, val details: String? = null, val savedProgress: Int = 0) : DownloadState()
    }
    private val settings = SettingsRepository(context)
    private val operation = Mutex()
    private val _selected = MutableStateFlow(fixedModel ?: ModelCatalog.DEFAULT)
    val selected: StateFlow<ModelDefinition> = _selected.asStateFlow()
    private val _customModels = MutableStateFlow<List<ModelDefinition>>(emptyList())
    val customModels: StateFlow<List<ModelDefinition>> = _customModels.asStateFlow()
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    @Volatile private var initialized = false
    private class Transfer(val owner: Job) { @Volatile var discard = false }
    @Volatile private var activeTransfer: Transfer? = null
    val isTransferring: Boolean get() = activeTransfer != null
    private val httpClient by lazy { client ?: OkHttpClient() }
    private val verified = mutableMapOf<String, List<Pair<Long, Long>>>()

    init { checkModelStatus() }
    suspend fun initializeSelection() {
        if (initialized) return
        operation.withLock {
            if (!initialized) {
                if (fixedModel != null) { initialized = true; checkModelStatus(); return@withLock }
                _customModels.value = File(context.filesDir, "models").listFiles().orEmpty()
                    .mapNotNull { CustomModelManifest.load(context.filesDir, it.name) }
                val id = settings.selectedModelId.first()
                _selected.value = CustomModelManifest.load(context.filesDir, id) ?: ModelCatalog.find(id)
                initialized = true
                checkModelStatus()
            }
        }
    }
    suspend fun select(model: ModelDefinition) = withContext(Dispatchers.IO) {
        check(fixedModel == null) { "Auxiliary model selection is fixed" }
        check(operation.tryLock()) { "A model transfer is already running" }
        try { settings.setSelectedModelId(model.id); _selected.value = model; initialized = true; checkModelStatus() }
        finally { operation.unlock() }
    }
    fun directory(model: ModelDefinition = selected.value): File = File(context.filesDir, model.relativeDirectory)
    fun getModelPath(): String = directory().absolutePath
    fun isModelReady(model: ModelDefinition = selected.value): Boolean = model.artifacts.all {
        val file = File(directory(model), it.localName); file.isFile && file.length() == it.bytes
    }
    fun checkModelStatus() { _downloadState.value = if (isModelReady()) DownloadState.Ready else DownloadState.NotStarted }

    /** An entry-point resume cannot overwrite a transfer or turn rejected weights into Ready using sizes alone. */
    fun refreshModelStatus() {
        if (!operation.tryLock()) return
        try {
            if (_downloadState.value !is DownloadState.Error) checkModelStatus()
        } finally { operation.unlock() }
    }
    suspend fun ensureVerified(): Boolean = withContext(Dispatchers.IO) {
        initializeSelection()
        if (!isModelReady()) return@withContext false
        operation.withLock {
            val model = selected.value
            installedVerified(model).also { if (!it) _downloadState.value = DownloadState.Error(ErrorType.CHECKSUM_MISMATCH) }
        }
    }
    private fun installedVerified(model: ModelDefinition): Boolean {
        if (!isModelReady(model)) return false
        val signature = model.artifacts.map { File(directory(model), it.localName).let { f -> f.length() to f.lastModified() } }
        if (verified[model.id] != signature) {
            if (!model.artifacts.all { ArtifactVerifier.valid(File(directory(model), it.localName), it) }) return false
            verified[model.id] = signature
        }
        return true
    }
    suspend fun downloadModel(onProgress: (Int) -> Unit = {}) {
        // Read once per download, not once per artifact: a settings edit during a
        // multi-file transfer cannot silently change its timeout policy halfway through.
        val downloadClient = clientForDownload(httpClient, settings.runtimeOptions.first())
        transfer(onProgress = onProgress, downloadClient = downloadClient)
    }
    /** Imports accept the catalog's remote or local filename; unknown checkpoints are not silently substituted. */
    suspend fun importFromFolder(uri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, uri) ?: error("Cannot access selected folder")
        transfer(importSource = { artifact ->
            val remote = artifact.url.substringAfterLast('/').substringBefore('?')
            val source = folder.findFile(remote) ?: folder.findFile(artifact.localName) ?: throw java.io.FileNotFoundException(remote)
            context.contentResolver.openInputStream(source.uri) ?: error("Cannot read ${source.name}")
        })
    }

    /** Stage a complete local bundle. The prior model remains usable on failure. */
    suspend fun importCustom(uris: List<Uri>, primaryUri: Uri, codecUri: Uri? = null): ModelDefinition = withContext(Dispatchers.IO) {
        require(uris.size in 1..64 && primaryUri in uris)
        require(codecUri == null || (codecUri in uris && codecUri != primaryUri))
        initializeSelection()
        check(operation.tryLock()) { "A model transfer is already running" }
        val id = "custom-${java.util.UUID.randomUUID()}"
        val parent = File(context.filesDir, "models")
        val staging = File(parent, ".$id")
        try {
            activeTransfer = Transfer(currentCoroutineContext().job)
            _downloadState.value = DownloadState.Copying(0)
            check(parent.mkdirs() || parent.isDirectory)
            check(staging.mkdir())
            val names = HashSet<String>()
            var primary: String? = null
            var codec: String? = null
            uris.forEachIndexed { index, uri ->
                val name = DocumentFile.fromSingleUri(context, uri)?.name ?: error("Cannot read model filename")
                require(CustomModelManifest.validFileName(name) && names.add(name.lowercase(java.util.Locale.ROOT))) { "Invalid or duplicate model filename: $name" }
                if (uri == primaryUri) primary = name
                if (uri == codecUri) codec = name
                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open $name")
                input.use { source -> File(staging, name).outputStream().use { target ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(bytes); if (n < 0) break
                        target.write(bytes, 0, n)
                    }
                } }
                _downloadState.value = DownloadState.Copying((index + 1) * 99 / uris.size)
            }
            val model = CustomModelManifest.create(staging, id, requireNotNull(primary), codec)
            CustomModelManifest.save(staging, model)
            currentCoroutineContext().ensureActive()
            Files.move(staging.toPath(), File(parent, id).toPath(), StandardCopyOption.ATOMIC_MOVE)
            _customModels.value = _customModels.value + model
            Log.i(TAG, "Imported custom model: ${model.id}, ${model.artifacts.size} files")
            model
        } finally {
            staging.deleteRecursively(); activeTransfer = null; checkModelStatus(); operation.unlock()
        }
    }
    private fun checkpoint(target: File) = File(target.parentFile, ".download-${target.name}")

    private suspend fun transfer(onProgress: (Int) -> Unit = {}, downloadClient: OkHttpClient? = null,
        importSource: ((ModelArtifact) -> InputStream)? = null) = withContext(Dispatchers.IO) {
        initializeSelection()
        check(operation.tryLock()) { "A model transfer is already running" }
        val model = selected.value
        val target = directory(model)
        val importing = importSource != null
        val owner = Transfer(currentCoroutineContext().job)
        val checkpoint = checkpoint(target)
        var staging: File? = null
        var published = false
        val previousProgress = (_downloadState.value as? DownloadState.Error)?.savedProgress ?: 0
        var last = if (importing) 0 else previousProgress
        var lastConnecting = !importing
        try {
            // Size alone is insufficient: retry must replace same-sized corrupt files.
            if (installedVerified(model)) { _downloadState.value = DownloadState.Ready; return@withContext }
            activeTransfer = owner
            _downloadState.value = if (importing) DownloadState.Copying(0) else DownloadState.Downloading(last, connecting = true)
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "Cannot create model storage" }
            val download = downloadClient?.let { ModelDownloadCheckpoint(checkpoint, model).apply { prepare() } }
            val payload = download?.payload ?: File(target.parentFile, ".${model.id}-${java.util.UUID.randomUUID()}").also {
                staging = it
                check(it.mkdir()) { "Cannot create model staging directory" }
            }
            var completedBytes = 0L
            for (artifact in model.artifacts) {
                currentCoroutineContext().ensureActive()
                Log.i(TAG, "${if (importing) "Importing" else "Downloading"} ${model.id}: ${artifact.localName}")
                val progress: (Long, Boolean) -> Unit = { bytes, connecting ->
                    val percent = ((completedBytes + bytes) * 100 / model.downloadBytes).toInt().coerceIn(0, 99)
                    if (percent != last || connecting != lastConnecting) {
                        _downloadState.value = if (importing) DownloadState.Copying(percent) else DownloadState.Downloading(percent, connecting)
                        lastConnecting = connecting
                    }
                    if (percent != last) {
                        last = percent
                        onProgress(percent)
                    }
                }
                if (download != null) download.fetch(artifact, { openDownload(checkNotNull(downloadClient).newCall(it)) }, progress)
                else checkNotNull(importSource)(artifact).use { input ->
                    writeArtifact(artifact, File(payload, artifact.localName), input, 0L, { progress(it, false) }, retryShortRead = false)
                }
                completedBytes += artifact.bytes
            }
            currentCoroutineContext().ensureActive()
            if (target.exists()) check(target.deleteRecursively()) { "Cannot replace incomplete model" }
            Files.move(payload.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            published = true
            staging = null
            verified[model.id] = model.artifacts.map { File(target, it.localName).let { f -> f.length() to f.lastModified() } }
            _downloadState.value = DownloadState.Ready
            onProgress(100)
            Log.i(TAG, "Verified model ready: ${model.id}")
        } catch (e: CancellationException) { checkModelStatus(); throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Model transfer failed", e)
            _downloadState.value = DownloadState.Error(when (e) {
                is SecurityException -> ErrorType.CHECKSUM_MISMATCH
                is java.io.FileNotFoundException -> ErrorType.MISSING_FILE
                is java.io.IOException -> if (importing) ErrorType.STORAGE else ErrorType.NETWORK
                else -> ErrorType.UNKNOWN
            }, e.message, savedProgress = if (importing) 0 else last)
        } finally {
            // OkHttp cancellation commonly arrives as IOException; ensureActive()
            // can then throw from the generic catch rather than the cancellation catch.
            try {
                try { if (!currentCoroutineContext().isActive) checkModelStatus() }
                finally {
                    staging?.deleteRecursively()
                    if (published || !importing && owner.discard) checkpoint.deleteRecursively()
                }
            } finally {
                // Even a storage/status cleanup exception must release transfer ownership.
                activeTransfer = null; operation.unlock()
            }
        }
    }
    fun cancelTransfer() { activeTransfer?.let { it.discard = true; it.owner.cancel() } }
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        check(operation.tryLock()) { "A model transfer is running" }
        try {
            val model = selected.value
            check(directory().deleteRecursively()) { "Cannot delete model" }
            check(checkpoint(directory()).deleteRecursively()) { "Cannot delete model checkpoint" }
            verified.remove(model.id)
            if (model.isCustom) {
                _customModels.value = _customModels.value.filterNot { it.id == model.id }
                settings.setSelectedModelId(ModelCatalog.DEFAULT.id); _selected.value = ModelCatalog.DEFAULT
            }
            checkModelStatus()
        }
        finally { operation.unlock() }
    }
    fun getModelSize(): Long = selected.value.artifacts.sumOf { File(directory(), it.localName).length() }
    fun getFormattedModelSize(): String = "%.0f MB".format(getModelSize() / 1000000.0)
}
