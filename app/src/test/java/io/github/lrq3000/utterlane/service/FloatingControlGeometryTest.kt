package io.github.lrq3000.utterlane.service

import org.junit.Assert.*
import org.junit.Test

class FloatingControlGeometryTest {
    private val portrait = FloatingControlGeometry(400, 800, 20, 30, 10, 40)

    @Test fun startRelativePositionHonorsAsymmetricInsetsInBothDirections() {
        assertEquals(20 to 30, portrait.clampPosition(-20, -30, 80, 80, rtl = false))
        assertEquals(10 to 30, portrait.clampPosition(-20, -30, 80, 80, rtl = true))
        assertEquals(310 to 680, portrait.clampPosition(999, 999, 80, 80, rtl = false))
        assertEquals(300 to 680, portrait.clampPosition(999, 999, 80, 80, rtl = true))
    }

    @Test fun rotationAndResizeClampTheEntireWindowIncludingPadding() {
        val landscape = FloatingControlGeometry(800, 400, 30, 0, 40, 20)
        assertEquals(310 to 220, landscape.clampPosition(310, 680, 160, 160, false))
        assertEquals(230 to 600, portrait.clampPosition(310, 680, 160, 160, false))
    }

    @Test fun densityIndependentPreferenceFitsSmallAndTransientDisplays() {
        assertEquals(168, portrait.diameterPx(84, 2f, 16, 16))
        assertEquals(88, portrait.diameterPx(-1, 2f, 16, 16))
        assertEquals(288, portrait.diameterPx(999, 2f, 16, 16))
        assertEquals(14, FloatingControlGeometry(30, 40).diameterPx(84, 2f, 16, 16))
        assertEquals(1, FloatingControlGeometry(0, 0).diameterPx(84, 2f, 16, 16))
        assertEquals(0 to 0, FloatingControlGeometry(0, 0).clampPosition(10, 10, 100, 100, true))
    }
}
