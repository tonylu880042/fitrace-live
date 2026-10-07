package com.fitrace.runner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RunnerJoinedEventTest {

    @Test
    fun `parse runner joined event from server json`() {
        val jsonString = """
            {
                "type": "RUNNER_JOINED",
                "roomId": "R0001",
                "runnerId": "R_ELIUD",
                "name": "Eliud Kipchoge",
                "country": "KE",
                "bib": "341",
                "avatarUrl": "https://example.com/eliud.jpg",
                "lane": 3,
                "deviceId": "TREADMILL CONSOLE #03",
                "fieldSize": 6,
                "capacity": 8,
                "tier": "WORLD CLASS TIER",
                "bio": "Marathon World Record Holder · 5,000M Olympic Finalist",
                "pr5k": "14:15.0",
                "targetPace": "02:50.4",
                "vo2Max": 84.2,
                "serverTime": 1700000000000
            }
        """.trimIndent()

        val msg = JSONObject(jsonString)
        assertEquals("RUNNER_JOINED", msg.getString("type"))

        val event = RunnerJoinedEvent(
            roomId = msg.optString("roomId"),
            runnerId = msg.optString("runnerId"),
            name = msg.optString("name"),
            country = if (msg.has("country") && !msg.isNull("country")) msg.getString("country") else null,
            bib = if (msg.has("bib") && !msg.isNull("bib")) msg.getString("bib") else null,
            avatarUrl = if (msg.has("avatarUrl") && !msg.isNull("avatarUrl")) msg.getString("avatarUrl") else null,
            lane = if (msg.has("lane") && !msg.isNull("lane")) msg.getInt("lane") else null,
            deviceId = if (msg.has("deviceId") && !msg.isNull("deviceId")) msg.getString("deviceId") else null,
            fieldSize = msg.optInt("fieldSize", 0),
            capacity = if (msg.has("capacity") && !msg.isNull("capacity")) msg.getInt("capacity") else null,
            tier = if (msg.has("tier") && !msg.isNull("tier")) msg.getString("tier") else null,
            bio = if (msg.has("bio") && !msg.isNull("bio")) msg.getString("bio") else null,
            pr5k = if (msg.has("pr5k") && !msg.isNull("pr5k")) msg.getString("pr5k") else null,
            targetPace = if (msg.has("targetPace") && !msg.isNull("targetPace")) msg.getString("targetPace") else null,
            vo2Max = if (msg.has("vo2Max") && !msg.isNull("vo2Max")) msg.getDouble("vo2Max").toFloat() else null,
            serverTime = msg.optLong("serverTime", 0L),
        )

        assertEquals("R0001", event.roomId)
        assertEquals("R_ELIUD", event.runnerId)
        assertEquals("Eliud Kipchoge", event.name)
        assertEquals("KE", event.country)
        assertEquals("341", event.bib)
        assertEquals(3, event.lane)
        assertEquals("TREADMILL CONSOLE #03", event.deviceId)
        assertEquals(6, event.fieldSize)
        assertEquals(8, event.capacity)
        assertEquals("WORLD CLASS TIER", event.tier)
        assertEquals("14:15.0", event.pr5k)
        assertEquals("02:50.4", event.targetPace)
        assertEquals(84.2f, event.vo2Max ?: 0f, 0.01f)
    }

    @Test
    fun `parse runner joined event with minimal optional fields`() {
        val jsonString = """
            {
                "type": "RUNNER_JOINED",
                "roomId": "R0002",
                "runnerId": "RUNNER_99",
                "name": "Jane Doe",
                "fieldSize": 2,
                "serverTime": 1700000000000
            }
        """.trimIndent()

        val msg = JSONObject(jsonString)
        val event = RunnerJoinedEvent(
            roomId = msg.optString("roomId"),
            runnerId = msg.optString("runnerId"),
            name = msg.optString("name"),
            country = if (msg.has("country") && !msg.isNull("country")) msg.getString("country") else null,
            bib = if (msg.has("bib") && !msg.isNull("bib")) msg.getString("bib") else null,
            avatarUrl = if (msg.has("avatarUrl") && !msg.isNull("avatarUrl")) msg.getString("avatarUrl") else null,
            lane = if (msg.has("lane") && !msg.isNull("lane")) msg.getInt("lane") else null,
            deviceId = if (msg.has("deviceId") && !msg.isNull("deviceId")) msg.getString("deviceId") else null,
            fieldSize = msg.optInt("fieldSize", 0),
            capacity = if (msg.has("capacity") && !msg.isNull("capacity")) msg.getInt("capacity") else null,
            tier = if (msg.has("tier") && !msg.isNull("tier")) msg.getString("tier") else null,
            bio = if (msg.has("bio") && !msg.isNull("bio")) msg.getString("bio") else null,
            pr5k = if (msg.has("pr5k") && !msg.isNull("pr5k")) msg.getString("pr5k") else null,
            targetPace = if (msg.has("targetPace") && !msg.isNull("targetPace")) msg.getString("targetPace") else null,
            vo2Max = if (msg.has("vo2Max") && !msg.isNull("vo2Max")) msg.getDouble("vo2Max").toFloat() else null,
            serverTime = msg.optLong("serverTime", 0L),
        )

        assertEquals("R0002", event.roomId)
        assertEquals("RUNNER_99", event.runnerId)
        assertEquals("Jane Doe", event.name)
        assertNull(event.country)
        assertNull(event.lane)
        assertNull(event.capacity)
        assertNull(event.vo2Max)
        assertEquals(2, event.fieldSize)
    }
}
