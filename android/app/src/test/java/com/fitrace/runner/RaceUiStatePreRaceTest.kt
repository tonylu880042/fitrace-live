package com.fitrace.runner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceUiStatePreRaceTest {

    private val now = 100_000L
    private val joined = RaceUiState(screen = Screen.RACE, roomId = "ROOM_1")

    @Test
    fun `lobby and profile screens never count as pre-race`() {
        assertFalse(RaceUiState(screen = Screen.LOBBY).inPreRace(now))
        assertFalse(RaceUiState(screen = Screen.PROFILE).inPreRace(now))
        // 大廳即使殘留 roomId 也不算
        assertFalse(joined.copy(screen = Screen.LOBBY).inPreRace(now))
    }

    @Test
    fun `race screen without a room is not pre-race`() {
        assertFalse(RaceUiState(screen = Screen.RACE).inPreRace(now))
    }

    @Test
    fun `joined and waiting for the gun is pre-race`() {
        assertTrue(joined.inPreRace(now))
    }

    @Test
    fun `countdown is pre-race`() {
        assertTrue(joined.copy(startAtServerTime = now + 5_000).inPreRace(now))
        assertTrue(joined.copy(startAtServerTime = now + 1).inPreRace(now))
    }

    @Test
    fun `from the gun onwards it is no longer pre-race`() {
        assertFalse(joined.copy(startAtServerTime = now).inPreRace(now))
        assertFalse(joined.copy(startAtServerTime = now - 60_000).inPreRace(now))
    }

    @Test
    fun `finished runner is not pre-race`() {
        assertFalse(joined.copy(startAtServerTime = now - 60_000, finishTimeMs = 55_000).inPreRace(now))
    }

    @Test
    fun `closed race is not pre-race`() {
        assertFalse(joined.copy(startAtServerTime = now - 60_000, closed = true).inPreRace(now))
        assertFalse(joined.copy(closed = true).inPreRace(now))
        assertFalse(joined.copy(startAtServerTime = now + 5_000, closed = true, dnf = true).inPreRace(now))
    }
}
