package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun defaultAndUnknownSelectionUseUltraQ8() {
        assertEquals("parakeet-ultra-q8_0", ModelCatalog.DEFAULT.id)
        assertEquals(ModelBackend.CRISP, ModelCatalog.DEFAULT.backend)
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.find(null))
        assertEquals(ModelCatalog.DEFAULT, ModelCatalog.find("unknown"))
        assertEquals(6, ModelCatalog.models.size)
    }
    @Test fun explicitSelectionsStillResolveToTheirOwnModels() {
        for (model in ModelCatalog.models) {
            assertSame(model, ModelCatalog.find(model.id))
        }
        assertEquals(ModelBackend.SHERPA, ModelCatalog.find("parakeet-v3").backend)
    }
    @Test fun storagePathsStayStableWhenTheDefaultChanges() {
        assertEquals("parakeet-v3", ModelCatalog.find("parakeet-v3").relativeDirectory)
        assertEquals("models/parakeet-ultra-q8_0", ModelCatalog.DEFAULT.relativeDirectory)
        for (model in ModelCatalog.models.filter { it.backend == ModelBackend.CRISP }) {
            assertEquals("models/${model.id}", model.relativeDirectory)
        }
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
    @Test fun compactReduxHasSeparateIdentityBackendAndPinnedSmallArtifact() {
        val compact = ModelCatalog.find("parakeet-redux-tq1-q8-native")
        assertEquals("parakeet-redux-tq1-q8-native", compact.id)
        assertEquals(ModelBackend.TRANSCRIBE_CPP, compact.backend)
        assertEquals("models/${compact.id}", compact.relativeDirectory)
        val file = compact.artifacts.single()
        assertEquals(159121504L, file.bytes)
        assertEquals("74f43ba852479e86e29df92cdbc89aa8215c7e8070f711be424ff466415b6184", file.sha256)
        assertTrue(file.url.contains("/87cbc354ce32bc9fe144b5b7bcdd9c68538907a9/"))
        assertTrue(file.url.endsWith("parakeet-redux-0.6b-TQ1_Q8_0.gguf"))
        assertEquals(4, ModelCatalog.models.count { it.backend == ModelBackend.CRISP })
        assertEquals(ModelBackend.SHERPA, ModelCatalog.PARAKEET_V3.backend)
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
