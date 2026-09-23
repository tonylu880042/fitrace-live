package com.fitrace.runner

/** 跑步機皮帶狀態（與廠商無關）。 */
enum class BeltStatus { IDLE, RUNNING, PAUSED }

/** 一筆跑步機即時數據（與廠商無關）。 */
data class TreadmillReading(
    val speedKmh: Float,
    /** 機台總累計刻度距離；各廠商在服務重啟後可能歸零，見 RaceCalculationEngine 的回退保護 */
    val totalDistanceMeters: Double,
    val incline: Float,
    val cadence: Int,
    val timestampMs: Long,
    val status: BeltStatus,
)

/**
 * 跑步機介接。比賽邏輯只依賴這個介面；支援新品牌的 Android 主控台只需要多寫一個實作。
 * 目前的實作：[FitOSTreadmill]（FitOS AIDL，§2.1）。
 */
interface Treadmill {
    fun connect()
    fun disconnect()
    fun setTargetSpeed(kmh: Float)
    fun currentReading(): TreadmillReading?
}
