package io.github.lrq3000.utterlane.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneEffectsTest {
    private class Handle : MicrophoneEffectHandle {
        override var enabled = true
        override var hasControl = true
        var releases = 0
        var writes = 0
        override fun setEnabled(value: Boolean): Int { writes++; enabled = value; return 0 }
        override fun close() { releases++ }
    }
    private class Factory : MicrophoneEffectFactory {
        val handles = EffectKind.entries.associateWith { Handle() }
        var missing: EffectKind? = null
        override fun available(kind: EffectKind) = kind != missing
        override fun create(kind: EffectKind, sessionId: Int): MicrophoneEffectHandle = handles.getValue(kind)
    }

    @Test fun agcOnlyAppliesAndRefreshDoesNotRepeatSuccessfulWrites() {
        val factory = Factory()
        val effects = MicrophoneEffects(42, InputPreprocessingPolicy.AGC_ONLY, factory)
        val state = effects.refresh()
        assertEquals(listOf(false, false, true), state.map { it.actual })
        val writes = factory.handles.values.sumOf { it.writes }
        effects.refresh()
        assertEquals(writes, factory.handles.values.sumOf { it.writes })
        effects.close(); effects.close()
        assertTrue(factory.handles.values.all { it.releases == 1 })
    }

    @Test fun missingAndUncontrolledEffectsDoNotPreventOthersAndControlCanArriveLater() {
        val factory = Factory().apply { missing = EffectKind.AEC; handles.getValue(EffectKind.NS).hasControl = false }
        val effects = MicrophoneEffects(42, InputPreprocessingPolicy.AGC_ONLY, factory)
        val state = effects.refresh()
        assertFalse(state.single { it.kind == EffectKind.AEC }.available)
        assertTrue(state.single { it.kind == EffectKind.NS }.actual!!)
        assertEquals(0, factory.handles.getValue(EffectKind.NS).writes)
        factory.handles.getValue(EffectKind.NS).hasControl = true
        assertFalse(effects.refresh().single { it.kind == EffectKind.NS }.actual!!)
        effects.close()
    }

    @Test fun systemDefaultDoesNotToggleEffects() {
        val factory = Factory()
        MicrophoneEffects(42, InputPreprocessingPolicy.SYSTEM_DEFAULT, factory).use { it.refresh() }
        assertTrue(factory.handles.values.all { it.writes == 0 && it.releases == 1 })
    }
}
