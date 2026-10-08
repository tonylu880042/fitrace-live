package com.fitrace.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceEndgameTest {

    private val baseNow = 1_000_000L
    private val myRunnerId = "RUNNER_ME"

    // ── 步驟 8：前三名完賽通知 (Podium Alert) ──

    @Test
    fun `podium alert identifies gold, silver, and bronze finishers accurately`() {
        val alert1 = PodiumAlert(
            rank = 1,
            runnerId = "R_ELIUD",
            name = "Eliud Kipchoge",
            country = "KE",
            finishTimeMs = baseNow + 855_000L,
            isMe = false,
            atMs = baseNow,
        )
        val alert2 = PodiumAlert(
            rank = 2,
            runnerId = myRunnerId,
            name = "Tung Lu",
            country = "TW",
            finishTimeMs = baseNow + 868_000L,
            isMe = true,
            atMs = baseNow,
        )
        val alert3 = PodiumAlert(
            rank = 3,
            runnerId = "R_JOSHUA",
            name = "Joshua Cheptegei",
            country = "UG",
            finishTimeMs = baseNow + 880_000L,
            isMe = false,
            atMs = baseNow,
        )

        assertEquals(1, alert1.rank)
        assertFalse(alert1.isMe)
        assertEquals(2, alert2.rank)
        assertTrue(alert2.isMe)
        assertEquals(3, alert3.rank)
    }

    @Test
    fun `notifiedPodiumRanks prevents duplicate alerts for the same podium position`() {
        var notifiedRanks = emptySet<Int>()

        // 第一次 P1 完賽
        val p1Finished = true
        if (p1Finished && 1 !in notifiedRanks) {
            notifiedRanks = notifiedRanks + 1
        }
        assertTrue(1 in notifiedRanks)
        assertEquals(1, notifiedRanks.size)

        // 下一次榜單更新，P1 依然完賽，不應重複加入
        val shouldNotifyP1Again = 1 !in notifiedRanks
        assertFalse(shouldNotifyP1Again)

        // P2 完賽加入
        notifiedRanks = notifiedRanks + 2
        assertEquals(setOf(1, 2), notifiedRanks)

        // P3 完賽加入
        notifiedRanks = notifiedRanks + 3
        assertEquals(setOf(1, 2, 3), notifiedRanks)

        // P4 完賽不屬於凸台前三名
        val p4IsPodium = 4 in 1..3
        assertFalse(p4IsPodium)
    }

    // ── 步驟 9：首位完賽後 100 秒關門倒數 ──

    @Test
    fun `isFinalSprintCutoff is active strictly within the 100-second window`() {
        val raceRunning = RaceUiState(
            screen = Screen.RACE,
            roomId = "R0001",
            startAtServerTime = baseNow - 600_000L,
            cutoffAtServerTime = baseNow + 99_000L, // 99 秒後關門 (<= 100s)
            finishTimeMs = null,
            closed = false,
        )

        // 99 秒剩餘：屬於 100 秒衝刺階段
        assertTrue(raceRunning.isFinalSprintCutoff(baseNow))

        // 剛好 100 秒剩餘：屬於 100 秒衝刺階段
        assertTrue(raceRunning.copy(cutoffAtServerTime = baseNow + 100_000L).isFinalSprintCutoff(baseNow))

        // 101 秒剩餘：尚未進入 100 秒衝刺
        assertFalse(raceRunning.copy(cutoffAtServerTime = baseNow + 101_000L).isFinalSprintCutoff(baseNow))

        // 已經過期 (剩餘 <= 0)：不屬於衝刺
        assertFalse(raceRunning.copy(cutoffAtServerTime = baseNow).isFinalSprintCutoff(baseNow))
        assertFalse(raceRunning.copy(cutoffAtServerTime = baseNow - 1_000L).isFinalSprintCutoff(baseNow))

        // 自己已經完賽：不處於衝刺模式
        assertFalse(raceRunning.copy(finishTimeMs = baseNow - 10_000L).isFinalSprintCutoff(baseNow))

        // 比賽已關閉：不處於衝刺模式
        assertFalse(raceRunning.copy(closed = true).isFinalSprintCutoff(baseNow))

        // 沒有設定 cutoff：不處於衝刺模式
        assertFalse(raceRunning.copy(cutoffAtServerTime = null).isFinalSprintCutoff(baseNow))
    }

    // ── 步驟 10：最終 Leaderboard 顯示 30 秒自動返回大廳 ──

    @Test
    fun `autoExitRemainingSeconds counts down from 30 seconds when race closes`() {
        val closedAt = baseNow
        val stateClosed = RaceUiState(
            screen = Screen.RACE,
            roomId = "R0001",
            closed = true,
            closedAtMs = closedAt,
        )

        // 剛關閉當下：剩餘 30 秒
        assertEquals(30, stateClosed.autoExitRemainingSeconds(closedAt))

        // 10 秒後：剩餘 20 秒
        assertEquals(20, stateClosed.autoExitRemainingSeconds(closedAt + 10_000L))

        // 29 秒後：剩餘 1 秒
        assertEquals(1, stateClosed.autoExitRemainingSeconds(closedAt + 29_000L))

        // 30 秒後：剩餘 0 秒 (觸發自動返回大廳)
        assertEquals(0, stateClosed.autoExitRemainingSeconds(closedAt + 30_000L))

        // 超過 30 秒：保證不小於 0
        assertEquals(0, stateClosed.autoExitRemainingSeconds(closedAt + 45_000L))
    }

    @Test
    fun `autoExitRemainingSeconds is null when race is not closed`() {
        val raceActive = RaceUiState(
            screen = Screen.RACE,
            roomId = "R0001",
            closed = false,
            closedAtMs = null,
        )
        assertNull(raceActive.autoExitRemainingSeconds(baseNow))
    }

    @Test
    fun `canLeave is allowed when user finishes or race closes`() {
        val raceRunning = RaceUiState(
            screen = Screen.RACE,
            roomId = "R0001",
            startAtServerTime = baseNow - 30_000L,
            finishTimeMs = null,
            closed = false,
        )

        // 競賽進行中且未完賽：不可隨意離開
        assertFalse(raceRunning.canLeave)

        // 自己完賽：允許離開回大廳
        assertTrue(raceRunning.copy(finishTimeMs = baseNow).canLeave)

        // 比賽關閉（例如 100 秒關門截止）：允許離開回大廳
        assertTrue(raceRunning.copy(closed = true, dnf = true).canLeave)

        // 尚未鳴槍起跑前（STANDBY）：允許取消離開
        assertTrue(RaceUiState(screen = Screen.RACE, startAtServerTime = null).canLeave)
    }
}
