package com.fitrace.runner

import kotlin.math.max

/**
 * 本地計算引擎（§2.2）：起跑歸零、配速換算與濾波、終點線性插值、完賽凍結。
 *
 * 純計算、不碰 Android API，因此可以用一般 JVM 單元測試驗證。
 */
class RaceCalculationEngine {

    data class Sample(
        val raceDistanceM: Double,
        val smoothedSpeedKmh: Float,
        val pace: String,
        val cadence: Int,
        val finishTimeMs: Long?,
        /** 只有跨越終點的那一筆為 true：呼叫端據此讓跑步機停下，且只做一次 */
        val justFinished: Boolean = false,
    )

    private var raceDistanceM = DEFAULT_RACE_DISTANCE_M
    private var baseDistance: Double? = null
    private val speedWindow = ArrayDeque<Float>()
    private var prevSample: Pair<Long, Double>? = null
    private var lastDistance = 0.0
    private var finalSample: Sample? = null

    val finishTimeMs: Long? get() = finalSample?.finishTimeMs

    /** 發令槍響：記錄機台當下的總刻度作為基準（§2.2-1），並採用伺服器宣告的賽事距離。 */
    fun arm(totalDistanceMeters: Double, raceDistanceM: Double = DEFAULT_RACE_DISTANCE_M) {
        this.raceDistanceM = raceDistanceM
        baseDistance = totalDistanceMeters
        speedWindow.clear()
        prevSample = null
        lastDistance = 0.0
        finalSample = null
    }

    val isArmed: Boolean get() = baseDistance != null

    /** 離開房間：回到尚未發令的狀態，下一場由新的 arm() 重新開始。 */
    fun reset() {
        baseDistance = null
        speedWindow.clear()
        prevSample = null
        lastDistance = 0.0
        finalSample = null
    }

    /**
     * @param serverTimeMs 已用時鐘偏差校正過的伺服器基準時間（§3.1）
     */
    fun update(
        totalDistanceMeters: Double,
        speedKmh: Float,
        cadence: Int,
        serverTimeMs: Long,
    ): Sample {
        // 完賽後比賽對這位選手已結束：成績、距離、配速全部凍結在撞線那一刻
        finalSample?.let { return it.copy(justFinished = false) }

        val base = baseDistance ?: totalDistanceMeters.also { baseDistance = it }
        var distance = max(0.0, totalDistanceMeters - base)
        // FitOS 服務重啟會讓機台總刻度歸零。已跑距離不可能倒退，偵測到回退就重設基準
        // 讓成績從原處接續，否則選手整場比賽的距離會歸零。
        if (distance < lastDistance) {
            baseDistance = totalDistanceMeters - lastDistance
            distance = lastDistance
        }
        lastDistance = distance

        speedWindow.addLast(speedKmh)
        if (speedWindow.size > SMA_WINDOW) speedWindow.removeFirst()
        val smoothed = speedWindow.average().toFloat()

        val finish = prevSample?.let { (t1, d1) ->
            interpolateFinish(t1, d1, serverTimeMs, distance, raceDistanceM)
        }
        prevSample = serverTimeMs to distance

        if (finish != null) {
            // 撞線那一筆通常略超過終點（取樣間隔內多跑的距離），成績上一律記為賽事距離
            return Sample(raceDistanceM, smoothed, formatPace(smoothed), cadence, finish, justFinished = true)
                .also { finalSample = it }
        }
        return Sample(distance, smoothed, formatPace(smoothed), cadence, null)
    }

    companion object {
        const val SMA_WINDOW = 5
        const val DEFAULT_RACE_DISTANCE_M = 5000.0

        /** 終點線性插值（§2.2-3）。兩個取樣點未跨越終點時回傳 null。 */
        fun interpolateFinish(
            t1: Long, d1: Double, t2: Long, d2: Double, target: Double,
        ): Long? {
            if (d1 >= target || d2 < target || d2 <= d1) return null
            val frac = (target - d1) / (d2 - d1)
            return t1 + Math.round(frac * (t2 - t1))
        }

        /** 時速換算配速字串（§2.2-2），格式與伺服器協議一致：`04'03"`。 */
        fun formatPace(speedKmh: Float): String {
            if (speedKmh <= 0.1f) return "--'--\""
            val secs = Math.round(3600.0 / speedKmh).toInt()
            return "%02d'%02d\"".format(secs / 60, secs % 60)
        }
    }
}
