package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class CustomModelManifestTest {
    @Test fun preservesPrimaryCompanionsAndIntegrityAcrossReload() {
        val root = Files.createTempDirectory("custom-model").toFile()
        try {
            val dir = File(root, "models/custom-123").apply { mkdirs() }
            File(dir, "speech.gguf").writeText("primary")
            File(dir, "tokenizer.bin").writeText("companion")
            val model = CustomModelManifest.create(dir, "custom-123", "speech.gguf", "tokenizer.bin")
            CustomModelManifest.save(dir, model)
            val restored = CustomModelManifest.load(root, model.id)!!
            assertEquals(model, restored)
            assertEquals("speech.gguf", restored.primaryFile)
            assertEquals("tokenizer.bin", restored.codecFile)
            assertEquals(2, restored.artifacts.size)
            assertTrue(restored.artifacts.all { ArtifactVerifier.valid(File(dir, it.localName), it) })
            File(dir, "speech.gguf").writeText("changed")
            assertFalse(ArtifactVerifier.valid(File(dir, "speech.gguf"), restored.artifacts.first { it.localName == "speech.gguf" }))
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsPathsAndMissingPrimary() {
        val root = Files.createTempDirectory("custom-model").toFile()
        try {
            assertNull(CustomModelManifest.load(root, "../../outside"))
            for (name in listOf("../model.gguf", "a/b", "a\\b", ".", "manifest.properties", "")) {
                assertFalse(name, CustomModelManifest.validFileName(name))
            }
            assertTrue(CustomModelManifest.validFileName("My model.gguf"))
            assertThrows(IllegalArgumentException::class.java) { CustomModelManifest.create(root, "custom-123", "missing.gguf") }
            File(root, "speech.gguf").writeText("primary")
            assertThrows(IllegalArgumentException::class.java) { CustomModelManifest.create(root, "custom-123", "speech.gguf", "missing.gguf") }
        } finally { root.deleteRecursively() }
    }
}
