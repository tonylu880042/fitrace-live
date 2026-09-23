package com.fitrace.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaceCalculationEngineTest {

    @Test
    fun `zeroing makes race distance relative to the gun`() {
        val engine = RaceCalculationEngine()
        // 機台上一場已經累積了 1234m，歸零後競賽距離必須從 0 起算
        engine.arm(1234.0)
        assertEquals(0.0, engine.update(1234.0, 0f, 0, 1_000).raceDistanceM, 1e-9)
        assertEquals(66.0, engine.update(1300.0, 12f, 170, 2_000).raceDistanceM, 1e-9)
    }

    @Test
    fun `finish time is interpolated between the straddling samples`() {
        assertEquals(
            1200L,
            RaceCalculationEngine.interpolateFinish(1000, 4990.0, 1400, 5010.0, 5000.0),
        )
        assertNull(RaceCalculationEngine.interpolateFinish(1000, 4000.0, 1400, 4500.0, 5000.0))
        assertNull(RaceCalculationEngine.interpolateFinish(1000, 5000.0, 1400, 5100.0, 5000.0))
    }

    @Test
    fun `first crossing wins and later samples cannot overwrite it`() {
        val engine = RaceCalculationEngine()
        engine.arm(0.0)
        engine.update(4990.0, 18f, 180, 1_000)
        val crossing = engine.update(5010.0, 18f, 180, 1_400)
        assertEquals(1200L, crossing.finishTimeMs)
        val after = engine.update(5100.0, 18f, 180, 1_800)
        assertEquals(1200L, after.finishTimeMs)
    }

    @Test
    fun `race stops at the line - distance and pace freeze, stop signal fires once`() {
        val engine = RaceCalculationEngine()
        engine.arm(0.0)
        assertEquals(false, engine.update(4990.0, 18f, 180, 1_000).justFinished)

        // 撞線那一筆實際跑到 5010m：成績記為 5000m，並發出唯一一次停機訊號
        val crossing = engine.update(5010.0, 18f, 180, 1_400)
        assertEquals(true, crossing.justFinished)
        assertEquals(5000.0, crossing.raceDistanceM, 1e-9)

        // 皮帶減速期間持續回報：距離、配速都不再變動，也不會重複觸發停機
        val slowing = engine.update(5030.0, 6f, 120, 1_800)
        assertEquals(false, slowing.justFinished)
        assertEquals(5000.0, slowing.raceDistanceM, 1e-9)
        assertEquals(crossing.pace, slowing.pace)
    }

    @Test
    fun `finish line follows the distance announced by the server`() {
        val engine = RaceCalculationEngine()
        engine.arm(0.0, raceDistanceM = 400.0)
        engine.update(390.0, 18f, 180, 1_000)
        val crossing = engine.update(410.0, 18f, 180, 1_400)
        assertEquals(1200L, crossing.finishTimeMs)
        assertEquals(400.0, crossing.raceDistanceM, 1e-9)
    }

    @Test
    fun `odometer reset does not wipe out the race distance`() {
        val engine = RaceCalculationEngine()
        engine.arm(500.0)
        assertEquals(300.0, engine.update(800.0, 12f, 170, 1_000).raceDistanceM, 1e-9)
        // FitOS 服務重啟，機台總刻度歸零：已跑的 300m 必須留著，並從該處繼續累計
        assertEquals(300.0, engine.update(0.0, 0f, 0, 2_000).raceDistanceM, 1e-9)
        assertEquals(350.0, engine.update(50.0, 12f, 170, 3_000).raceDistanceM, 1e-9)
    }

    @Test
    fun `pace smoothing rides through motor noise`() {
        val engine = RaceCalculationEngine()
        engine.arm(0.0)
        // 單一筆脈衝雜訊不應讓配速暴衝
        repeat(4) { engine.update(it * 5.0, 15f, 180, 1_000L + it * 400) }
        val spiked = engine.update(25.0, 30f, 180, 2_600)
        assertEquals(18f, spiked.smoothedSpeedKmh, 0.01f)
    }

    @Test
    fun `pace format matches the wire protocol`() {
        assertEquals("04'00\"", RaceCalculationEngine.formatPace(15f))
        assertEquals("--'--\"", RaceCalculationEngine.formatPace(0f))
    }
}
