package com.fitrace.runner

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.fitos.treadmill.IFitOSService
import com.fitos.treadmill.ITreadmillDataCallback
import com.fitos.treadmill.TreadmillMetric
import kotlin.math.abs
import kotlin.math.sign

/**
 * 取代真實 FitOS 系統服務的模擬跑步機，讓 App 能在一般平板／模擬器上開發（§6 Phase 3）。
 * 跑在獨立 process（見 AndroidManifest 的 android:process），因此走的是真正的 Binder IPC，
 * 而不是同 process 的直接呼叫——`DeathRecipient` 重連路徑才驗得到。
 */
class MockFitOSService : Service() {

    private val callbacks = RemoteCallbackList<ITreadmillDataCallback>()
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    @Volatile private var targetSpeed = 0f
    private var speed = 0f
    private var totalDistance = 0.0
    private var incline = 1.0f
    private var lastTickNanos = 0L

    private val binder = object : IFitOSService.Stub() {
        override fun registerCallback(cb: ITreadmillDataCallback?): Boolean =
            cb != null && callbacks.register(cb)

        override fun unregisterCallback(cb: ITreadmillDataCallback?): Boolean =
            cb != null && callbacks.unregister(cb)

        override fun getCurrentMetric(): TreadmillMetric = snapshot()

        override fun setTargetSpeed(speedKmh: Float): Boolean {
            targetSpeed = speedKmh.coerceIn(0f, 25f)
            return true
        }

        override fun setTargetIncline(value: Float): Boolean {
            incline = value.coerceIn(0f, 15f)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("mock-treadmill").apply { start() }
        handler = Handler(thread.looper)
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        handler.post(tick)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        callbacks.kill()
        super.onDestroy()
    }

    /** 馬達以有限加速度追上目標速度，並持續累計機台總刻度距離。 */
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = (now - lastTickNanos) / 1_000_000_000.0
            lastTickNanos = now

            val diff = targetSpeed - speed
            if (abs(diff) > 0.01f) {
                // 真實跑步機不會瞬間變速：限制在 2 km/h 每秒
                val step = (RAMP_KMH_PER_S * dt).toFloat()
                speed = if (abs(diff) <= step) targetSpeed else speed + step * sign(diff)
            }
            totalDistance += speed / 3.6 * dt

            val metric = snapshot()
            val n = callbacks.beginBroadcast()
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onMetricUpdated(metric) }
            }
            callbacks.finishBroadcast()

            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun snapshot() = TreadmillMetric(
        speedKmh = speed,
        totalDistanceMeters = totalDistance,
        incline = incline,
        // 真機讀自感測器；此處以速度粗略推估
        cadence = if (speed < 0.5f) 0 else (150 + speed * 1.8f).toInt(),
        timestamp = System.currentTimeMillis(),
        machineStatus = if (speed > 0.1f) TreadmillMetric.STATUS_RUNNING else TreadmillMetric.STATUS_IDLE,
    )

    private companion object {
        const val TICK_MS = 100L // 10Hz，§2.2-4 所述的 AIDL 取樣頻率
        const val RAMP_KMH_PER_S = 2.0
    }
}
