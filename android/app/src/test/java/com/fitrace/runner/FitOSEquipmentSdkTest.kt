package com.fitrace.runner

import com.ucare.fitosequipmentsdk.EquipmentLimits
import com.ucare.fitosequipmentsdk.EquipmentSdkContract
import com.ucare.fitosequipmentsdk.EquipmentSnapshot
import com.ucare.fitosequipmentsdk.EquipmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitOSEquipmentSdkTest {

    @Test
    fun `official equipment snapshot builder builds valid telemetry`() {
        val snapshot = EquipmentSnapshot.Builder().apply {
            speed = "15.5"
            avgSpeed = "14.2"
            maxSpeed = "18.0"
            distance = "2.350"
            incline = "1.5"
            spm = "178"
            hr = "165"
            pace = "03:52"
            timeElapsed = "00:09:05"
            calories = "142"
            watt = "285"
            elapsedRealtimeMillis = 12345678L
        }.build()

        assertNotNull(snapshot)
        assertEquals("15.5", snapshot.speed)
        assertEquals("2.350", snapshot.distance)
        assertEquals("1.5", snapshot.incline)
        assertEquals("178", snapshot.spm)
        assertEquals("165", snapshot.hr)
        assertEquals("03:52", snapshot.pace)
        assertEquals("00:09:05", snapshot.timeElapsed)
        assertEquals("142", snapshot.calories)
        assertEquals(12345678L, snapshot.elapsedRealtimeMillis)
    }

    @Test
    fun `official equipment state reflects connected treadmill`() {
        val state = EquipmentState(
            EquipmentSdkContract.STATUS_CONNECTED,
            EquipmentSdkContract.CONNECTION_BLE,
            EquipmentSdkContract.TYPE_RUN,
            EquipmentSdkContract.RUN_TYPE_NORMAL,
            EquipmentSdkContract.CURVED_MODE_RUNNING,
            "Matrix T50 Commercial",
            201,
            "v1.2",
            "v2.0.4",
            EquipmentSdkContract.CONTROL_STATE_START,
            true, // isMetric
            true, // isBindDevice
        )

        assertEquals("connected", state.connectionStatus)
        assertEquals("Run", state.equipmentType)
        assertEquals("NORMAL_Run", state.runType)
        assertEquals(1, state.controlState) // CONTROL_STATE_START
        assertTrue(state.isMetric)
        assertTrue(state.isBindDevice)
    }

    @Test
    fun `official equipment limits have reasonable running bounds`() {
        val limits = EquipmentLimits(
            1, 32, // bike
            1, 16, // row
            1, 8,  // curved
            0, 15, // run incline
            0.8, 22.0, // run speed kmh
            1, 20, // stairmill
        )

        assertEquals(0, limits.runInclineMin)
        assertEquals(15, limits.runInclineMax)
        assertEquals(0.8, limits.runSpeedMinKmh, 0.001)
        assertEquals(22.0, limits.runSpeedMaxKmh, 0.001)
    }

    @Test
    fun `metric and imperial conversions match official guide spec`() {
        // Metric case: speed 12.0 km/h, distance 5.0 km
        val metricSpeedStr = "12.0"
        val metricDistanceStr = "5.0"
        val metricSpeedKmh = metricSpeedStr.toDoubleOrNull() ?: 0.0
        val metricDistanceMeters = (metricDistanceStr.toDoubleOrNull() ?: 0.0) * 1000.0

        assertEquals(12.0, metricSpeedKmh, 0.001)
        assertEquals(5000.0, metricDistanceMeters, 0.001)

        // Imperial case: speed 7.5 mph, distance 3.107 mi (5k)
        val imperialSpeedStr = "7.5"
        val imperialDistanceStr = "3.107"
        val imperialSpeedKmh = (imperialSpeedStr.toDoubleOrNull() ?: 0.0) * 1.60934
        val imperialDistanceMeters = (imperialDistanceStr.toDoubleOrNull() ?: 0.0) * 1609.344

        assertEquals(12.07, imperialSpeedKmh, 0.01)
        assertEquals(5000.23, imperialDistanceMeters, 0.1)
    }
}
