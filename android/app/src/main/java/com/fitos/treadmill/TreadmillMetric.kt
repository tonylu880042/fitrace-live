package com.fitos.treadmill

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** §2.1 (1) 的欄位定義。實際規格以 FitOS 方提供者為準。 */
@Parcelize
data class TreadmillMetric(
    val speedKmh: Float,
    val totalDistanceMeters: Double,
    val incline: Float,
    val cadence: Int,
    val timestamp: Long,
    /** 0: 待機, 1: 運轉中, 2: 暫停/急停 */
    val machineStatus: Int,
) : Parcelable {
    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_RUNNING = 1
        const val STATUS_PAUSED = 2
    }
}
