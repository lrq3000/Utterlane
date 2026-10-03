package com.translander.asr

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun defaultAndUnknownSelectionKeepExistingModel() {
        assertEquals("parakeet-v3", ModelCatalog.DEFAULT.id)
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.find(null))
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.find("unknown"))
        assertEquals(5, ModelCatalog.models.size)
    }
    @Test fun alternativesHavePinnedArtifactsAndTruthfulSizes() {
        val alternatives = ModelCatalog.models.filter { it.backend == ModelBackend.CRISP }
        assertEquals(4, alternatives.size)
        for (model in alternatives) {
            assertEquals(1, model.artifacts.size)
            val artifact = model.artifacts.single()
            assertTrue(artifact.sha256!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue(artifact.url.startsWith("https://huggingface.co/cstr/"))
            assertTrue(artifact.bytes == 402226496L || artifact.bytes == 674342400L)
            assertEquals("model.gguf", artifact.localName)
        }
    }
    @Test fun artifactVerifierRejectsTruncatedAndWrongContent() {
        val directory = java.nio.file.Files.createTempDirectory("model-integrity").toFile()
        try {
            val file = java.io.File(directory, "model")
            file.writeText("abc")
            val good = ModelArtifact("https://example.invalid/model", "model", 3,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            assertTrue(ArtifactVerifier.valid(file, good))
            assertFalse(ArtifactVerifier.valid(file, good.copy(bytes = 4)))
            assertFalse(ArtifactVerifier.valid(file, good.copy(sha256 = "0".repeat(64))))
        } finally { directory.deleteRecursively() }
    }
}
