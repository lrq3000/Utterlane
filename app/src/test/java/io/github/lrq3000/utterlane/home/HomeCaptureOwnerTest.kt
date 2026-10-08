package io.github.lrq3000.utterlane.home

import org.junit.Assert.*
import org.junit.Test

/** Callback ordering is deterministic here; no microphone, clock or model is needed. */
class HomeCaptureOwnerTest {
    private class Driver(val events: HomeCaptureOwner.Events<String>) : HomeCaptureOwner.Driver {
        var starts = 0
        var stops = 0
        var interruptions = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun interrupt() { interruptions++ }
    }
    private class Fixture {
        val drivers = mutableListOf<Driver>()
        val released = mutableListOf<String>()
        val results = mutableListOf<String>()
        val owner = HomeCaptureOwner<String>(
            factory = { events -> Driver(events).also(drivers::add) },
            onAccepted = { released.add("previous") }, onResult = results::add)
        val driver get() = drivers.last()
    }

    @Test fun `busy rejection preserves previous result and permits another attempt`() {
        val f = Fixture()
        assertTrue(f.owner.start())
        f.driver.events.rejected("Microphone in use")
        assertTrue(f.released.isEmpty())
        assertEquals("Microphone in use", f.owner.state.value.message)
        assertFalse(f.owner.state.value.active)
        assertTrue(f.owner.start())
    }

    @Test fun `rapid stop waits for recorder ready and happens exactly once`() {
        val f = Fixture()
        f.owner.start()
        f.owner.stop(); f.owner.stop()
        assertEquals(0, f.driver.stops)
        f.driver.events.ready(); f.driver.events.ready()
        assertEquals(1, f.driver.stops)
        assertEquals(HomeCapturePhase.STOPPING, f.owner.state.value.phase)
        assertTrue("Readiness only delivers Stop; it is not replacement input", f.released.isEmpty())
    }

    @Test fun `completed processing cannot restart until native cleanup closes`() {
        val f = Fixture()
        f.owner.start(); f.driver.events.ready(); f.owner.stop()
        f.driver.events.captureEnded()
        f.driver.events.result("full result")
        assertFalse(f.owner.start())
        assertEquals(listOf("full result"), f.results)
        f.driver.events.closed()
        assertTrue(f.owner.start())
    }

    @Test fun `late callbacks from previous session cannot alter a new session`() {
        val f = Fixture()
        f.owner.start()
        val old = f.driver
        old.events.ready(); old.events.result("first"); old.events.closed()
        f.owner.start()
        assertFalse(old.events.input(spooledSamples = 1600, preview = "stale text"))
        old.events.ready(); old.events.result("stale"); old.events.closed(); old.events.rejected("late error")
        assertEquals(HomeCapturePhase.STARTING, f.owner.state.value.phase)
        assertEquals(listOf("first"), f.results)
        assertEquals(1, f.released.size)
    }

    @Test fun `interruption preserves ownership and waits for cleanup`() {
        val f = Fixture()
        f.owner.start(); f.driver.events.ready()
        f.owner.interrupt(); f.owner.interrupt()
        assertEquals(1, f.driver.interruptions)
        assertTrue(f.owner.state.value.active)
        f.driver.events.closed()
        assertFalse(f.owner.state.value.active)
        assertTrue("Ready then cancellation without input must preserve the old owner", f.released.isEmpty())
    }

    @Test fun `failure with recoverable audio before ready still adopts the result`() {
        val f = Fixture()
        f.owner.start()
        f.driver.events.result("recoverable input")
        f.driver.events.closed()
        assertEquals(listOf("previous"), f.released)
        assertEquals(listOf("recoverable input"), f.results)
    }

    @Test fun `service launch failure preserves old result`() {
        val owner = HomeCaptureOwner<String>(factory = { error("Start denied") }, onAccepted = { fail("discarded result") }, onResult = {})
        assertTrue(owner.start())
        assertEquals("Start denied", owner.state.value.message)
        assertFalse(owner.state.value.active)
    }

    @Test fun `duplicate completion and late ready do not replace a result twice`() {
        val f = Fixture()
        f.owner.start()
        f.driver.events.result("result")
        f.driver.events.ready()
        f.driver.events.result("duplicate")
        assertEquals(listOf("result"), f.results)
        assertEquals(HomeCapturePhase.PROCESSING, f.owner.state.value.phase)
    }

    @Test fun `late ready after capture ended cannot reenable recording`() {
        val f = Fixture()
        f.owner.start(); f.owner.stop()
        f.driver.events.captureEnded(); f.driver.events.ready()
        assertEquals(HomeCapturePhase.PROCESSING, f.owner.state.value.phase)
        assertEquals(0, f.driver.stops)
    }

    @Test fun `ready then empty completion leaves the prior owner intact`() {
        val f = Fixture()
        f.owner.start(); f.driver.events.ready()
        assertFalse(f.driver.events.input(spooledSamples = 0, preview = " \n"))
        f.driver.events.captureEnded(); f.driver.events.rejected("No audio"); f.driver.events.closed()
        assertTrue(f.released.isEmpty())
        assertTrue(f.results.isEmpty())
    }

    @Test fun `durably spooled live samples accept once without restarting or stopping capture`() {
        val f = Fixture()
        f.owner.start(); f.driver.events.ready()
        assertTrue(f.released.isEmpty())
        assertTrue(f.driver.events.input(spooledSamples = 1))
        assertTrue(f.driver.events.input(spooledSamples = 3200))
        assertEquals(listOf("previous"), f.released)
        assertEquals(HomeCapturePhase.RECORDING, f.owner.state.value.phase)
        assertEquals(1, f.driver.starts)
        assertEquals(0, f.driver.stops)
        assertTrue(f.results.isEmpty())
    }

    @Test fun `useful live text can accept before ready without opening another capture`() {
        val f = Fixture()
        f.owner.start()
        assertTrue(f.driver.events.input(preview = "Useful text"))
        f.driver.events.ready()
        assertEquals(listOf("previous"), f.released)
        assertEquals(1, f.driver.starts)
        assertEquals(0, f.driver.stops)
    }

    @Test fun `spooled tail after stop or interruption is still authoritative input`() {
        val f = Fixture()
        f.owner.start(); f.owner.stop(); f.driver.events.ready(); f.owner.interrupt()
        assertTrue(f.driver.events.input(spooledSamples = 1))
        assertEquals(listOf("previous"), f.released)
        assertEquals(HomeCapturePhase.STOPPING, f.owner.state.value.phase)
        assertEquals(1, f.driver.stops)
        assertEquals(1, f.driver.starts)
    }

    @Test fun `completed sessions cannot republish late live input`() {
        val f = Fixture()
        f.owner.start(); f.driver.events.result("finished")
        assertFalse(f.driver.events.input(spooledSamples = 1, preview = "late"))
        f.driver.events.closed()
        assertFalse(f.driver.events.input(spooledSamples = 1))
        assertEquals(listOf("previous"), f.released)
    }
}
