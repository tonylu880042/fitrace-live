package com.fitrace.runner

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.ucare.fitosequipmentsdk.EquipmentLimits
import com.ucare.fitosequipmentsdk.EquipmentSdkContract
import com.ucare.fitosequipmentsdk.EquipmentSnapshot
import com.ucare.fitosequipmentsdk.EquipmentState
import com.ucare.fitosequipmentsdk.IEquipmentCallback
import com.ucare.fitosequipmentsdk.IEquipmentService
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

/**
 * 模擬 FitOS 跑步機系統服務，實作官方 [IEquipmentService.Stub] 介面。
 * 讓 App 能在一般平板／模擬器上進行全功能開發與除錯。
 * 跑在獨立 process（見 AndroidManifest 的 android:process=":fitos"），
 * 走真實 Binder IPC 與 AIDL 序列化，包含 DeathRecipient 重連路徑。
 */
class MockFitOSService : Service() {

    private val callbacks = RemoteCallbackList<IEquipmentCallback>()
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    @Volatile private var targetSpeed = 0f
    private var speed = 0f
    private var totalDistance = 0.0
    private var incline = 1.0f
    private var lastTickNanos = 0L
    private var startNanos = 0L
    private var currentControlState = EquipmentSdkContract.CONTROL_STATE_INIT

    private val binder = object : IEquipmentService.Stub() {
        override fun getApiVersion(): Int = EquipmentSdkContract.API_VERSION

        override fun getConnectionState(): EquipmentState = currentConnectionState()

        override fun getSnapshot(): EquipmentSnapshot = snapshot()

        override fun getLimits(): EquipmentLimits = currentLimits()

        override fun registerCallback(cb: IEquipmentCallback?) {
            if (cb == null) return
            val ok = callbacks.register(cb)
            if (ok) {
                // 註冊成功後立即推播當前連線狀態與快照
                runCatching {
                    cb.onConnectionStateChanged(currentConnectionState())
                    cb.onControlStateChanged(currentControlState)
                    cb.onEquipmentDataChanged(snapshot())
                }
            }
        }

        override fun unregisterCallback(cb: IEquipmentCallback?) {
            if (cb != null) {
                callbacks.unregister(cb)
            }
        }

        override fun startWorkout() {
            currentControlState = EquipmentSdkContract.CONTROL_STATE_START
            broadcastControlState(currentControlState)
        }

        override fun stopWorkout() {
            currentControlState = EquipmentSdkContract.CONTROL_STATE_STOP
            targetSpeed = 0f
            broadcastControlState(currentControlState)
        }

        override fun setResistance(resistance: Int) {
            // 跑步機不支援阻力
        }

        override fun setIncline(inclinePercent: Int) {
            incline = inclinePercent.toFloat().coerceIn(0f, 15f)
        }

        override fun setSpeed(speedKmh: Double) {
            targetSpeed = speedKmh.toFloat().coerceIn(0f, 25f)
            if (targetSpeed > 0.1f && currentControlState != EquipmentSdkContract.CONTROL_STATE_START) {
                currentControlState = EquipmentSdkContract.CONTROL_STATE_START
                broadcastControlState(currentControlState)
            }
        }

        override fun setStairmillSpeedLevel(level: Int) {
            // 跑步機不支援段速
        }
    }

    override fun onCreate() {
        super.onCreate()
        startNanos = SystemClock.elapsedRealtimeNanos()
        lastTickNanos = startNanos
        thread = HandlerThread("mock-treadmill").apply { start() }
        handler = Handler(thread.looper)
        handler.post(tick)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        callbacks.kill()
        super.onDestroy()
    }

    private fun broadcastControlState(state: Int) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onControlStateChanged(state) }
        }
        callbacks.finishBroadcast()
    }

    /** 馬達以有限加速度追上目標速度，並持續累計機台總刻度距離。 */
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = (now - lastTickNanos) / 1_000_000_000.0
            lastTickNanos = now

            val diff = targetSpeed - speed
            if (abs(diff) > 0.01f) {
                val step = (RAMP_KMH_PER_S * dt).toFloat()
                speed = if (abs(diff) <= step) targetSpeed else speed + step * sign(diff)
            }
            totalDistance += speed / 3.6 * dt

            val metric = snapshot()
            val n = callbacks.beginBroadcast()
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onEquipmentDataChanged(metric) }
            }
            callbacks.finishBroadcast()

            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun snapshot(): EquipmentSnapshot {
        val currentSpeed = speed
        val currentIncline = incline
        val currentDistance = totalDistance
        val distanceKm = currentDistance / 1000.0
        val cadence = if (currentSpeed < 0.5f) 0 else (150 + currentSpeed * 1.8f).toInt()
        val paceMinPerKm = if (currentSpeed > 0.5f) (60.0 / currentSpeed) else 0.0
        val paceMinutes = paceMinPerKm.toInt()
        val paceSeconds = ((paceMinPerKm - paceMinutes) * 60).toInt()
        val paceStr = if (currentSpeed > 0.5f) String.format(Locale.US, "%02d:%02d", paceMinutes, paceSeconds) else "--:--"

        val elapsedSec = ((SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000_000L).coerceAtLeast(0)
        val hours = elapsedSec / 3600
        val minutes = (elapsedSec % 3600) / 60
        val seconds = elapsedSec % 60
        val timeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        return EquipmentSnapshot.Builder().apply {
            this.speed = String.format(Locale.US, "%.1f", currentSpeed)
            this.avgSpeed = String.format(Locale.US, "%.1f", currentSpeed)
            this.maxSpeed = "22.0"
            this.distance = String.format(Locale.US, "%.3f", distanceKm)
            this.incline = String.format(Locale.US, "%.1f", currentIncline)
            this.spm = cadence.toString()
            this.hr = if (currentSpeed > 1.0f) (130 + (currentSpeed * 3f).toInt()).toString() else "72"
            this.pace = paceStr
            this.currentPace = paceStr
            this.timeElapsed = timeStr
            this.calories = (currentDistance * 0.06).toInt().toString()
            this.watt = (currentSpeed * 18.5f).toInt().toString()
            this.elapsedRealtimeMillis = SystemClock.elapsedRealtime()
        }.build()
    }

    private fun currentConnectionState() = EquipmentState(
        EquipmentSdkContract.STATUS_CONNECTED,
        EquipmentSdkContract.CONNECTION_BLE,
        EquipmentSdkContract.TYPE_RUN,
        EquipmentSdkContract.RUN_TYPE_NORMAL,
        EquipmentSdkContract.CURVED_MODE_RUNNING,
        "FitRace Treadmill Console #01",
        101,
        "v2.0",
        "v3.4.1",
        currentControlState,
        true,
        true,
    )

    private fun currentLimits() = EquipmentLimits(
        1, 32,
        1, 16,
        1, 8,
        0, 15,
        0.8, 22.0,
        1, 20,
    )

    companion object {
        const val TICK_MS = 100L // 10Hz 取樣頻率
        const val RAMP_KMH_PER_S = 2.0
    }
}
