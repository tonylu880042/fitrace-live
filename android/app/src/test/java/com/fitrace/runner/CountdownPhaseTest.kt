package com.fitrace.runner

import com.fitrace.runner.CountdownPhase.Counting
import com.fitrace.runner.CountdownPhase.Go
import com.fitrace.runner.CountdownPhase.None
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountdownPhaseTest {

    private val start = 100_000L

    @Test
    fun `no start signal means no countdown`() {
        assertEquals(None, countdownPhase(null, start))
    }

    @Test
    fun `seconds left round up like the top bar T-n`() {
        assertEquals(Counting(5), countdownPhase(start, start - 5_000))
        assertEquals(Counting(5), countdownPhase(start, start - 4_001))
        assertEquals(Counting(4), countdownPhase(start, start - 4_000))
        assertEquals(Counting(1), countdownPhase(start, start - 1))
    }

    @Test
    fun `go lasts 1200ms from the gun then disappears`() {
        assertEquals(Go, countdownPhase(start, start))
        assertEquals(Go, countdownPhase(start, start + 1_199))
        assertEquals(None, countdownPhase(start, start + 1_200))
        assertEquals(None, countdownPhase(start, start + 60_000))
    }

    @Test
    fun `a full countdown beeps five short and one long, each exactly once`() {
        // 以 50ms 的刷新頻率走完整段倒數，模擬 Hud 的 LaunchedEffect(phase)：只在階段改變時呼叫
        val beeps = mutableListOf<CountdownBeep>()
        var prev: CountdownPhase = None
        for (now in (start - 5_020)..(start + 2_000) step 50) {
            val next = countdownPhase(start, now)
            if (next == prev) continue
            countdownBeep(prev, next)?.let(beeps::add)
            prev = next
        }
        assertEquals(List(5) { CountdownBeep.SHORT } + CountdownBeep.LONG, beeps)
    }

    @Test
    fun `reconnecting after the gun never beeps GO`() {
        // 伺服器重連時重送過去的 startAt：直接落在 Go（1.2 秒內）或 None
        assertNull(countdownBeep(None, Go))
        assertNull(countdownBeep(None, None))
        assertNull(countdownBeep(Go, None))
    }

    @Test
    fun `clock skew flash of T-6 stays silent`() {
        assertNull(countdownBeep(None, Counting(6)))
        assertEquals(CountdownBeep.SHORT, countdownBeep(Counting(6), Counting(5)))
    }

    @Test
    fun `same phase never replays`() {
        assertNull(countdownBeep(Counting(3), Counting(3)))
        assertNull(countdownBeep(Go, Go))
    }

    @Test
    fun `spoken words for the countdown and the gun`() {
        val start = 100_000L
        val words = (start - 5_000..start step 1_000).map { countdownWord(countdownPhase(start, it)) }
        assertEquals(listOf("Five", "Four", "Three", "Two", "One", "Go!"), words)
        assertEquals("12", countdownWord(Counting(12)))
    }
}
