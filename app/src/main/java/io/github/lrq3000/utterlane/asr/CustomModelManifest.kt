package io.github.lrq3000.utterlane.asr

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** Private import receipts are data, never executable model code or external paths. */
object CustomModelManifest {
    private const val MANIFEST = "manifest.properties"
    private val identity = Regex("custom-[a-zA-Z0-9-]+")
    fun validFileName(name: String): Boolean = name.isNotBlank() && name.length <= 200 &&
        name != MANIFEST && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }

    fun create(directory: File, id: String, primary: String, codec: String? = null): ModelDefinition {
        require(identity.matches(id) && validFileName(primary) && File(directory, primary).isFile)
        val files = directory.listFiles().orEmpty().filter { it.name != MANIFEST }.sortedBy { it.name }
        require(files.isNotEmpty() && files.size <= 64 && files.all { it.isFile && validFileName(it.name) && it.length() > 0 })
        require(codec == null || (codec != primary && validFileName(codec) && files.any { it.name == codec }))
        val artifacts = files.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            ModelArtifact("", file.name, file.length(), digest.digest().joinToString("") { "%02x".format(it) })
        }
        return ModelDefinition(id, primary, ModelBackend.CRISP, artifacts, primary, codec)
    }

    fun save(directory: File, model: ModelDefinition) {
        val props = Properties().apply {
            setProperty("version", "1"); setProperty("id", model.id); setProperty("primary", model.primaryFile)
            model.codecFile?.let { setProperty("codec", it) }
            setProperty("count", model.artifacts.size.toString())
            model.artifacts.forEachIndexed { i, file ->
                setProperty("$i.name", file.localName); setProperty("$i.size", file.bytes.toString()); setProperty("$i.sha256", file.sha256!!)
            }
        }
        File(directory, MANIFEST).outputStream().use { props.store(it, "Utterlane custom speech model") }
    }

    fun load(filesDir: File, id: String?): ModelDefinition? {
        if (id == null || !identity.matches(id)) return null
        return try {
            val props = Properties()
            File(filesDir, "models/$id/$MANIFEST").inputStream().use { props.load(it) }
            require(props.getProperty("version") == "1" && props.getProperty("id") == id)
            val count = props.getProperty("count").toInt().also { require(it in 1..64) }
            val primary = props.getProperty("primary").also { require(validFileName(it)) }
            val artifacts = (0 until count).map { i ->
                val name = props.getProperty("$i.name").also { require(validFileName(it)) }
                val size = props.getProperty("$i.size").toLong().also { require(it > 0) }
                val hash = props.getProperty("$i.sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                ModelArtifact("", name, size, hash)
            }
            require(artifacts.any { it.localName == primary } && artifacts.map { it.localName }.toSet().size == count)
            val codec = props.getProperty("codec")
            require(codec == null || (codec != primary && validFileName(codec) && artifacts.any { it.localName == codec }))
            ModelDefinition(id, primary, ModelBackend.CRISP, artifacts, primary, codec)
        } catch (_: Exception) { null }
    }
}
