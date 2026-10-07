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
        } ?: if (distance >= raceDistanceM) serverTimeMs else null
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

/** 起跑倒數的畫面階段：發令前逐秒倒數，槍響後短暫顯示 GO!，之後什麼都不畫。 */
sealed interface CountdownPhase {
    data object None : CountdownPhase
    /** [secondsLeft] = ceil(剩餘毫秒 / 1000)，也就是頂列 T-n 顯示的 n。 */
    data class Counting(val secondsLeft: Int) : CountdownPhase
    data object Go : CountdownPhase
}

/** GO! 在槍響後停留的時間。 */
const val GO_PHASE_MS = 1200L

fun countdownPhase(startAt: Long?, now: Long): CountdownPhase = when {
    startAt == null -> CountdownPhase.None
    now < startAt -> CountdownPhase.Counting(((startAt - now + 999) / 1000).toInt())
    now < startAt + GO_PHASE_MS -> CountdownPhase.Go
    else -> CountdownPhase.None
}

enum class CountdownBeep { SHORT, LONG }

const val COUNTDOWN_CUE_SECONDS = 5

/**
 * 階段轉換時該響哪一聲。呼叫端只在階段「改變」時呼叫一次（例如 LaunchedEffect(phase)），
 * 因此每秒最多一聲短嗶。GO 的長嗶只在親眼看到倒數結束時響：斷線重連時伺服器會重送
 * 已過去的 startAt，那時直接落在 Go 或 None，不該補嗶。
 */
fun countdownBeep(prev: CountdownPhase, next: CountdownPhase): CountdownBeep? = when {
    next == prev -> null
    // 只念 5..1：時鐘偏差會讓第一個 tick 短暫落在 ceil=6，那一下不該出聲
    next is CountdownPhase.Counting -> if (next.secondsLeft in 1..COUNTDOWN_CUE_SECONDS) CountdownBeep.SHORT else null
    next is CountdownPhase.Go && prev is CountdownPhase.Counting -> CountdownBeep.LONG
    else -> null
}

private val SPOKEN_NUMBERS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")

/**
 * 該階段要念的字。「Go!」而非「GO!」：全大寫可能被部分 TTS 引擎當縮寫念成 G-O。
 * 秒數用英文單字而非阿拉伯數字，避免引擎依語系念成別的語言。
 */
fun countdownWord(phase: CountdownPhase): String = when (phase) {
    is CountdownPhase.Counting -> SPOKEN_NUMBERS.getOrElse(phase.secondsLeft) { phase.secondsLeft.toString() }
    CountdownPhase.Go -> "Go!"
    CountdownPhase.None -> ""
}
