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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
        internal suspend fun openDownload(call: Call, publish: (Call) -> Unit): InputStream {
            val owner = currentCoroutineContext()
            // A normal completion callback runs too late: the IO coroutine cannot
            // complete until execute/read unblocks. Observe the *cancelling* phase,
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
                val opened = call.execute()
                response = opened
                owner.ensureActive()
                check(opened.isSuccessful) { "Download failed: ${opened.code}" }
                val body = opened.body ?: error("Empty model response")
                return object : java.io.FilterInputStream(body.byteStream()) {
                    override fun close() {
                        // execute() returning only transfers ownership to the body;
                        // keep cancellation armed through the final read and close.
                        try { super.close() }
                        finally { try { opened.close() } finally { cancellation?.dispose() } }
                    }
                }
            } catch (failure: Throwable) {
                try { response?.close() } finally { cancellation?.dispose() }
                throw failure
            }
        }
    }
    enum class ErrorType { NETWORK, CHECKSUM_MISMATCH, MISSING_FILE, FOLDER_ACCESS, STORAGE, UNKNOWN }
    sealed class DownloadState {
        data object NotStarted : DownloadState()
        data class Downloading(val progress: Int) : DownloadState()
        data class Copying(val progress: Int) : DownloadState()
        data object Extracting : DownloadState()
        data object Ready : DownloadState()
        data class Error(val type: ErrorType, val details: String? = null) : DownloadState()
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
    @Volatile private var transferJob: Job? = null
    @Volatile private var activeCall: Call? = null
    val isTransferring: Boolean get() = transferJob != null
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
        transfer(false, onProgress) { artifact ->
            val request = Request.Builder().url(artifact.url).build()
            openDownload(downloadClient.newCall(request)) { activeCall = it }
        }
    }
    /** Imports accept the catalog's remote or local filename; unknown checkpoints are not silently substituted. */
    suspend fun importFromFolder(uri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, uri) ?: error("Cannot access selected folder")
        transfer(true) { artifact ->
            val remote = artifact.url.substringAfterLast('/').substringBefore('?')
            val source = folder.findFile(remote) ?: folder.findFile(artifact.localName) ?: throw java.io.FileNotFoundException(remote)
            context.contentResolver.openInputStream(source.uri) ?: error("Cannot read ${source.name}")
        }
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
            transferJob = currentCoroutineContext()[Job]
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
            staging.deleteRecursively(); transferJob = null; checkModelStatus(); operation.unlock()
        }
    }
    private suspend fun transfer(importing: Boolean, onProgress: (Int) -> Unit = {}, open: suspend (ModelArtifact) -> InputStream) = withContext(Dispatchers.IO) {
        initializeSelection()
        check(operation.tryLock()) { "A model transfer is already running" }
        val model = selected.value
        val target = directory(model)
        var staging: File? = null
        try {
            // Size alone is insufficient: retry must replace same-sized corrupt files.
            if (installedVerified(model)) { _downloadState.value = DownloadState.Ready; return@withContext }
            transferJob = currentCoroutineContext()[Job]
            _downloadState.value = if (importing) DownloadState.Copying(0) else DownloadState.Downloading(0)
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "Cannot create model storage" }
            val temporary = File(target.parentFile, ".${model.id}-${java.util.UUID.randomUUID()}")
            staging = temporary
            check(temporary.mkdir()) { "Cannot create model staging directory" }
            var completedBytes = 0L
            for (artifact in model.artifacts) {
                Log.i(TAG, "${if (importing) "Importing" else "Downloading"} ${model.id}: ${artifact.localName}")
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var last = -1
                open(artifact).use { input -> File(temporary, artifact.localName).outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= artifact.bytes) { "Unexpected model size" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        val percent = ((completedBytes + bytes) * 100 / model.downloadBytes).toInt().coerceIn(0, 99)
                        if (percent != last) {
                            last = percent
                            _downloadState.value = if (importing) DownloadState.Copying(percent) else DownloadState.Downloading(percent)
                            onProgress(percent)
                        }
                    }
                } }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (bytes != artifact.bytes || (artifact.sha256 != null && actualHash != artifact.sha256)) throw SecurityException("Model integrity verification failed: ${artifact.localName}")
                completedBytes += bytes
            }
            currentCoroutineContext().ensureActive()
            if (target.exists()) check(target.deleteRecursively()) { "Cannot replace incomplete model" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
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
            }, e.message)
        } finally {
            // OkHttp cancellation commonly arrives as IOException; ensureActive()
            // can then throw from the generic catch rather than the cancellation catch.
            try {
                try { if (!currentCoroutineContext().isActive) checkModelStatus() }
                finally { staging?.deleteRecursively() }
            } finally {
                // Even a storage/status cleanup exception must release transfer ownership.
                activeCall = null; transferJob = null; operation.unlock()
            }
        }
    }
    fun cancelTransfer() { activeCall?.cancel(); transferJob?.cancel() }
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        check(operation.tryLock()) { "A model transfer is running" }
        try {
            val model = selected.value
            check(directory().deleteRecursively()) { "Cannot delete model" }
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
