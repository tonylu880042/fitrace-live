package com.fitrace.runner

import com.fitrace.runner.TensionEvent.BECAME_LEADER
import com.fitrace.runner.TensionEvent.OVERTAKE
import com.fitrace.runner.TensionEvent.OVERTAKEN
import com.fitrace.runner.TensionState.CATCHING
import com.fitrace.runner.TensionState.CLOSING_IN
import com.fitrace.runner.TensionState.LEADING
import com.fitrace.runner.TensionState.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceTensionTest {

    private val tick = 400L // 榜單廣播間隔
    private val t0 = 1_000_000L

    /** 自己第 [rank] 名、後面有人以 4 m/s 追來，差距 [behindM] 公尺。 */
    private fun chased(behindM: Double, rank: Int = 3, field: Int = 6, behindId: String = "B") = Standing(
        rank = rank, fieldSize = field,
        aheadId = if (rank > 1) "A" else null, aheadRank = if (rank > 1) rank - 1 else null,
        aheadGapM = if (rank > 1) 100.0 else null, aheadGapMs = if (rank > 1) 30_000 else null,
        behindId = behindId, behindRank = rank + 1, behindGapM = behindM,
        behindGapMs = (behindM / 4.0 * 1000).toLong(),
    )

    /** 自己以 4 m/s 追前一名，差距 [aheadM] 公尺；後面的人很遠。 */
    private fun chasing(aheadM: Double, rank: Int = 3, field: Int = 6) = Standing(
        rank = rank, fieldSize = field,
        aheadId = "A", aheadRank = rank - 1, aheadGapM = aheadM, aheadGapMs = (aheadM / 4.0 * 1000).toLong(),
        behindId = "B", behindRank = rank + 1, behindGapM = 100.0, behindGapMs = 30_000,
    )

    private fun plain(rank: Int, field: Int = 6) = Standing(rank = rank, fieldSize = field)

    /** 依序餵入榜單，每筆間隔 400ms，回傳每一步的結果。 */
    private fun run(
        standings: List<Standing?>, start: Tension = Tension(), from: Long = t0, active: Boolean = true,
    ): List<Tension> {
        var t = start
        return standings.mapIndexed { i, s -> RaceTension.next(t, s, active, from + i * tick).also { t = it } }
    }

    private fun changes(steps: List<Tension>) = steps.zipWithNext().count { (a, b) -> a.state != b.state }

    /* ── 重播判斷（Step 0） ── */

    @Test
    fun `same start time is a replay of the current race`() {
        assertTrue(isReplayOfCurrentRace(500_000L, 500_000L))
    }

    @Test
    fun `first schedule and a new start time are not replays`() {
        assertFalse(isReplayOfCurrentRace(null, 500_000L))
        assertFalse(isReplayOfCurrentRace(500_000L, 560_000L))
    }

    /* ── 榜單擷取（Step 1） ── */

    @Test
    fun `standing picks the neighbours and their time gaps`() {
        fun e(rank: Int, id: String, d: Double, ahead: Long?) =
            RaceClient.Entry(rank, id, id, d, "05'00\"", "RUNNING", null, ahead)
        val board = listOf(e(3, "C", 90.0, 2_500), e(1, "A", 120.0, null), e(2, "ME", 100.0, 5_000))
        val s = standingOf(board, "ME")!!
        assertEquals(2, s.rank)
        assertEquals(3, s.fieldSize)
        assertEquals(1, s.aheadRank)
        assertEquals(20.0, s.aheadGapM!!, 1e-9)
        assertEquals(5_000L, s.aheadGapMs)
        assertEquals(3, s.behindRank)
        assertEquals(10.0, s.behindGapM!!, 1e-9)
        assertEquals(2_500L, s.behindGapMs) // 後一名自己的 gapToAheadMs
        assertNull(standingOf(board, "NOBODY"))
    }

    @Test
    fun `leader has no one ahead and last place has no one behind`() {
        val board = listOf(
            RaceClient.Entry(1, "ME", "ME", 50.0, "", "RUNNING", null, null),
            RaceClient.Entry(2, "X", "X", 40.0, "", "RUNNING", 2_000, 2_000),
        )
        assertNull(standingOf(board, "ME")!!.aheadRank)
        assertNull(standingOf(board, "X")!!.behindRank)
    }

    /* ── 只在比賽中作用 ── */

    @Test
    fun `inactive before the start and after the finish`() {
        val steps = run(List(20) { chased(8.0 - it * 0.4, rank = if (it % 2 == 0) 1 else 2) }, active = false)
        steps.forEach {
            assertEquals(NONE, it.state)
            assertNull(it.event)
            assertNull(it.popup)
        }
    }

    @Test
    fun `finishing clears the state and ignores later rank changes`() {
        val racing = run(listOf(plain(1), plain(1)))
        assertEquals(LEADING, racing.last().state)
        val after = run(listOf(plain(3), plain(4)), start = racing.last(), from = t0 + 5_000, active = false)
        after.forEach { assertEquals(NONE, it.state); assertNull(it.event) }
        // 非比賽中不記名次，所以重新作用時第一筆不比
        val again = RaceTension.next(after.last(), plain(2), true, t0 + 10_000)
        assertNull(again.event)
    }

    /* ── 一次性事件 ── */

    @Test
    fun `rank improvement is an overtake, worsening is overtaken`() {
        val steps = run(listOf(plain(4), plain(3), plain(3), plain(3), plain(3), plain(4)))
        assertNull(steps[0].event)
        assertEquals(OVERTAKE, steps[1].event)
        assertEquals(OVERTAKE, steps[1].popup?.event)
        assertEquals(4, steps[1].popup?.fromRank)
        assertEquals(3, steps[1].popup?.toRank)
        assertNull(steps[2].event)
        assertEquals(OVERTAKEN, steps[5].event)
        assertEquals(OVERTAKEN, steps[5].popup?.event)
    }

    @Test
    fun `moving into first place is became leader`() {
        val steps = run(listOf(plain(2), plain(1)))
        assertEquals(BECAME_LEADER, steps[1].event)
        assertEquals(BECAME_LEADER, steps[1].popup?.event)
    }

    @Test
    fun `first leaderboard after joining is only a baseline`() {
        val first = RaceTension.next(Tension(), plain(5), true, t0)
        assertNull(first.event)
        assertNull(first.popup)
    }

    @Test
    fun `first leaderboard after reconnecting is only a baseline`() {
        val before = run(listOf(plain(5), plain(5)))
        val reconnected = RaceTension.onReconnect(before.last())
        // 斷線期間掉到第 6：不算被超越
        val after = RaceTension.next(reconnected, plain(6), true, t0 + 10_000)
        assertNull(after.event)
        assertEquals(before.last().popup, after.popup)
        // 之後的名次變化照常
        assertEquals(OVERTAKE, RaceTension.next(after, plain(5), true, t0 + 10_400).event)
    }

    @Test
    fun `rank change while the field size changes is ignored`() {
        // 有人報名／退出時名次跟著位移，不是超越
        val steps = run(listOf(plain(4, field = 6), plain(5, field = 7), plain(4, field = 6)))
        steps.forEach { assertNull(it.event) }
    }

    @Test
    fun `two-digit fields`() {
        val steps = run(listOf(plain(12, field = 12), plain(11, field = 12), plain(11, field = 12), plain(11, field = 12), plain(12, field = 12)))
        assertEquals(OVERTAKE, steps[1].event)
        assertEquals(12, steps[1].popup?.fromRank)
        assertEquals(11, steps[1].popup?.toRank)
        assertEquals(OVERTAKEN, steps[4].event)
        assertEquals(11, steps[4].popup?.fromRank)
        assertEquals(12, steps[4].popup?.toRank)

        val lead = run(listOf(plain(10, field = 10), plain(10, field = 10)))
        assertEquals(NONE, lead.last().state)
        val chasedAt10 = run(List(12) { chased(14.0 - it * 0.5, rank = 10, field = 11) })
        assertEquals(CLOSING_IN, chasedAt10.last().state)
    }

    /* ── 持續狀態與遲滯 ── */

    @Test
    fun `leading needs rank one in a field larger than one`() {
        assertEquals(LEADING, run(listOf(plain(1, field = 2))).last().state)
        assertEquals(NONE, run(listOf(plain(1, field = 1))).last().state)
        assertEquals(NONE, run(listOf(plain(2, field = 2))).last().state)
    }

    @Test
    fun `closing in enters at 3 seconds only while the gap shrinks`() {
        // 4 m/s 的人每 400ms 近 0.4m：從 20m（5 秒）一路追到 8m（2 秒）
        val gaps = (0 until 31).map { 20.0 - it * 0.4 }
        val steps = run(gaps.map { chased(it) })
        gaps.zip(steps).forEach { (g, t) ->
            if (g / 4.0 > 3.0) assertEquals("gap ${g}m", NONE, t.state)
        }
        val firstOn = steps.indexOfFirst { it.state == CLOSING_IN }
        assertTrue(firstOn >= 0)
        assertTrue(gaps[firstOn] / 4.0 <= 3.0)
        // 跨過 3 秒門檻後一筆內就進入（連續縮小早已成立）
        assertTrue(gaps[firstOn] / 4.0 > 3.0 - 0.4 / 4.0 * 2)
        assertEquals(CLOSING_IN, steps[firstOn].popup?.state)
    }

    @Test
    fun `a close but steady chaser is not closing in`() {
        val steps = run(List(30) { chased(8.0) }) // 2 秒，但差距不變
        steps.forEach { assertEquals(NONE, it.state) }
    }

    @Test
    fun `hysteresis keeps the state between 3 and 4 seconds and exits above 4`() {
        // 追到 2.8 秒
        val closing = (0 until 25).map { 20.0 - it * 0.4 } // 20m → 10.4m（2.6 秒）
        val steps1 = run(closing.map { chased(it) })
        assertEquals(CLOSING_IN, steps1.last().state)
        // 對方放慢：差距回到 3.0~4.0 秒之間仍維持
        val back = listOf(12.8, 13.6, 14.4, 15.2, 16.0) // 3.2s → 4.0s
        val steps2 = run(back.map { chased(it) }, start = steps1.last(), from = t0 + 25 * tick)
        steps2.forEach { assertEquals(CLOSING_IN, it.state) }
        // 超過 4 秒就退出
        val out = RaceTension.next(steps2.last(), chased(16.4), true, t0 + 30 * tick)
        assertEquals(NONE, out.state)
        // 退出後回到 3.5 秒（即使在縮小）也不會再進入：要 ≤ 3.0 秒
        val reenter = run((0 until 10).map { chased(16.0 - it * 0.4) }, start = out, from = t0 + 31 * tick)
        reenter.forEach { t ->
            val gapS = (t.behind.samples.last().second) / 4.0
            if (gapS > 3.0) assertEquals(NONE, t.state)
        }
    }

    @Test
    fun `state exits after the gap stops shrinking for about 3 seconds`() {
        val closing = (0 until 31).map { 20.0 - it * 0.4 } // 到 8m（2 秒）
        val steps1 = run(closing.map { chased(it) })
        assertEquals(CLOSING_IN, steps1.last().state)
        // 差距停在 8m：約 2 秒的歷史窗口追平後再過 3 秒退出，不會更早
        val steady = run(List(20) { chased(8.0) }, start = steps1.last(), from = t0 + 31 * tick)
        val offAt = steady.indexOfFirst { it.state == NONE }
        assertTrue("exits eventually", offAt >= 0)
        val heldMs = (offAt + 1) * tick
        assertTrue("held $heldMs ms", heldMs in 3_000L..6_000L)
        steady.drop(offAt).forEach { assertEquals(NONE, it.state) }
    }

    @Test
    fun `alternating 2 m jitter on a steady gap never flickers`() {
        val steps = run(List(150) { chased(10.0 + if (it % 2 == 0) 2.0 else -2.0) })
        steps.forEach { assertEquals(NONE, it.state) }
    }

    @Test
    fun `random 2 m jitter on a closing chase stays on without flicker`() {
        val rnd = java.util.Random(42)
        // 4 m/s 的人以 1 m/s 的速度追近，從 13m 到 1m，加 ±2m 抖動
        val gaps = (0 until 30).map { 13.0 - it * 0.4 + (rnd.nextDouble() * 4 - 2) }
        val steps = run(gaps.map { chased(it.coerceAtLeast(0.5)) })
        val firstOn = steps.indexOfFirst { it.state == CLOSING_IN }
        assertTrue(firstOn >= 0)
        steps.drop(firstOn).forEach { assertEquals(CLOSING_IN, it.state) }
    }

    @Test
    fun `random jitter never toggles the state faster than once per second`() {
        val rnd = java.util.Random(7)
        val steps = run(List(300) { chased(9.0 + (rnd.nextDouble() * 4 - 2)) })
        var lastChange = Long.MIN_VALUE / 2
        steps.zipWithNext().forEachIndexed { i, (a, b) ->
            if (a.state != b.state) {
                val at = t0 + (i + 1) * tick
                assertTrue("toggled after ${at - lastChange}ms", at - lastChange >= 1_000)
                lastChange = at
            }
        }
        assertTrue("changes=${changes(steps)}", changes(steps) <= 10)
    }

    @Test
    fun `catching the runner ahead`() {
        val steps = run((0 until 31).map { chasing(20.0 - it * 0.4) })
        val firstOn = steps.indexOfFirst { it.state == CATCHING }
        assertTrue(firstOn >= 0)
        assertEquals(CATCHING, steps[firstOn].popup?.state)
        steps.drop(firstOn).forEach { assertEquals(CATCHING, it.state) }
    }

    @Test
    fun `rank toggling at the front changes state at most once per second`() {
        val steps = run(List(25) { plain(if (it % 2 == 0) 1 else 2) })
        var lastChange = Long.MIN_VALUE / 2
        steps.zipWithNext().forEachIndexed { i, (a, b) ->
            if (a.state != b.state) {
                val at = t0 + (i + 1) * tick
                assertTrue(at - lastChange >= 1_000)
                lastChange = at
            }
        }
    }

    /* ── 優先序 ── */

    @Test
    fun `closing in beats catching beats leading`() {
        // 前後都在逼近
        val both = (0 until 31).map {
            Standing(
                rank = 3, fieldSize = 6,
                aheadId = "A", aheadRank = 2, aheadGapM = 20.0 - it * 0.4, aheadGapMs = ((20.0 - it * 0.4) / 4 * 1000).toLong(),
                behindId = "B", behindRank = 4, behindGapM = 20.0 - it * 0.4, behindGapMs = ((20.0 - it * 0.4) / 4 * 1000).toLong(),
            )
        }
        assertEquals(CLOSING_IN, run(both).last().state)
        // 領先者被追近
        assertEquals(CLOSING_IN, run((0 until 31).map { chased(20.0 - it * 0.4, rank = 1) }).last().state)
    }

    @Test
    fun `an event wins the popup over a state entered at the same time`() {
        // 被追近中、同時被超越（名次變差）
        val closing = run((0 until 25).map { chased(20.0 - it * 0.4) })
        assertEquals(CLOSING_IN, closing.last().state)
        val passed = RaceTension.next(closing.last(), plain(4), true, t0 + 25 * tick)
        assertEquals(OVERTAKEN, passed.popup?.event)
        // 成為領先：事件卡，而不是 LEADING 狀態卡
        val lead = run(listOf(plain(2), plain(1)))
        assertEquals(LEADING, lead.last().state)
        assertEquals(BECAME_LEADER, lead.last().popup?.event)
        assertNull(lead.last().popup?.state)
    }

    /* ── 冷卻 ── */

    @Test
    fun `same popup at most once per 15 seconds`() {
        // 4 → 3 超越，接著 3 → 4 → 3 再超越：第二次在冷卻內，不彈
        val steps = run(listOf(plain(4), plain(3), plain(3), plain(3), plain(4), plain(4), plain(4), plain(3)))
        assertEquals(OVERTAKE, steps[1].popup?.event)
        val seqAfterFirst = steps[1].popup!!.seq
        assertEquals(OVERTAKEN, steps[4].popup?.event)
        assertEquals(OVERTAKE, steps[7].event) // 事件照常回報
        assertEquals(OVERTAKEN, steps[7].popup?.event) // 但卡片沒換
        assertEquals(seqAfterFirst + 1, steps[7].popup!!.seq)
        // 15 秒後可以再彈
        val later = RaceTension.next(RaceTension.next(steps.last(), plain(4), true, t0 + 16_000), plain(3), true, t0 + 17_000)
        assertEquals(OVERTAKE, later.popup?.event)
        assertEquals(t0 + 17_000, later.popup?.atMs)
    }

    @Test
    fun `re-entering a state within the cooldown keeps the chip but does not pop`() {
        val closing = (0 until 31).map { 20.0 - it * 0.4 }
        val first = run(closing.map { chased(it) })
        val popped = first.last().popup!!
        assertEquals(CLOSING_IN, popped.state)
        // 拉開到 5 秒退出，再追回來
        val away = run(listOf(chased(20.0), chased(20.0), chased(20.0)), start = first.last(), from = t0 + 31 * tick)
        assertEquals(NONE, away.last().state)
        val again = run(closing.map { chased(it) }, start = away.last(), from = t0 + 34 * tick)
        assertEquals(CLOSING_IN, again.last().state) // 晶片照常反映
        assertEquals(popped, again.last().popup) // 冷卻內不再彈中央卡
    }

    @Test
    fun `two popups are at least a second apart`() {
        // 並肩跑：超越後 400ms 又被超越
        val steps = run(listOf(plain(4), plain(3), plain(4)))
        assertEquals(OVERTAKE, steps[1].popup?.event)
        assertEquals(OVERTAKEN, steps[2].event)
        assertEquals(OVERTAKE, steps[2].popup?.event)
    }

    @Test
    fun `became leader and leading share a cooldown`() {
        val steps = run(listOf(plain(2), plain(1), plain(1), plain(1), plain(2), plain(2), plain(2), plain(1)))
        assertEquals(BECAME_LEADER, steps[1].popup?.event)
        val seq = steps[1].popup!!.seq
        // 掉到第 2 再回到第 1：事件與 LEADING 都在冷卻內
        assertEquals(OVERTAKEN, steps[4].popup?.event)
        assertEquals(seq + 1, steps[4].popup!!.seq)
        assertEquals(BECAME_LEADER, steps[7].event)
        assertEquals(LEADING, steps[7].state)
        assertEquals(steps[4].popup, steps[7].popup)
    }
}
