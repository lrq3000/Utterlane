package io.github.lrq3000.utterlane.asr

object DiarizationModel {
    // NVIDIA's official Q8_0 artifact; verified against the HF LFS SHA-256.
    val definition = ModelDefinition("nemotron-diarization", "NVIDIA Nemotron-3-Diarization · Q8_0", ModelBackend.CRISP, listOf(
        ModelArtifact("https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/main/Nemotron-3-Diarization.q8_0.gguf",
            "model.gguf", 107012128, "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1")))
}
