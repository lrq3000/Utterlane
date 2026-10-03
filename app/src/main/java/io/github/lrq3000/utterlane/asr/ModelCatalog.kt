package io.github.lrq3000.utterlane.asr

enum class ModelBackend { SHERPA, CRISP }
data class ModelArtifact(val url: String, val localName: String, val bytes: Long, val sha256: String?)
data class ModelDefinition(val id: String, val name: String, val backend: ModelBackend, val artifacts: List<ModelArtifact>) {
    val downloadBytes: Long get() = artifacts.sumOf { it.bytes }
}

/** Immutable, verified upstream artifacts. Names and sizes refer to this runtime's files. */
object ModelCatalog {
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    val DEFAULT = ModelDefinition("parakeet-v3", "NVIDIA Parakeet v3 · ONNX INT8", ModelBackend.SHERPA, listOf(
        ModelArtifact("$BASE/encoder.int8.onnx", "encoder.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        ModelArtifact("$BASE/decoder.int8.onnx", "decoder.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        ModelArtifact("$BASE/joiner.int8.onnx", "joiner.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        ModelArtifact("$BASE/tokens.txt", "tokens.txt", 93939, null)))
    private fun gguf(family: String, quant: String, size: Long, hash: String) = ModelDefinition(
        "parakeet-$family-$quant", "Moondream Parakeet ${family.replaceFirstChar { it.uppercase() }} · ${quant.uppercase()}", ModelBackend.CRISP,
        listOf(ModelArtifact("https://huggingface.co/cstr/parakeet-$family-GGUF/resolve/main/parakeet-$family-$quant.gguf", "model.gguf", size, hash)))
    val models = listOf(DEFAULT,
        gguf("ultra", "q8_0", 674342400, "ebf1186c3dc7e77f71877a5380a73e39d5c0aaf5cb55e65e56b077b1b2aacef1"),
        gguf("redux", "q8_0", 674342400, "606135796d55fd64b5baeb21966b3fcb469b61e6950ba2e0f9269c49cc734ff2"),
        gguf("ultra", "q4_k", 402226496, "09bb4a91da4c14f158ad01829b9bb3d81eedc85156d884e9a9c483dfe09238c6"),
        gguf("redux", "q4_k", 402226496, "c07c7a0c76aa573334df88afc3a0c3bed45f90a35f09c22979e811078be30e6d"))
    private val byId = models.associateBy { it.id }
    fun find(id: String?): ModelDefinition = byId[id] ?: DEFAULT
}
