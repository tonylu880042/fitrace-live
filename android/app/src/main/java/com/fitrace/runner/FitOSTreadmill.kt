package com.fitrace.runner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.fitos.treadmill.IFitOSService
import com.fitos.treadmill.ITreadmillDataCallback
import com.fitos.treadmill.TreadmillMetric

/**
 * [Treadmill] 的 FitOS 實作：AIDL 連線與斷線自動重連（§2.1）。
 *
 * 綁定的是本專案的 [MockFitOSService]；接上真機時只需要改 [serviceIntent] 指向 FitOS 的元件，
 * 其餘邏輯（DeathRecipient、重新註冊 callback）不變。
 */
class FitOSTreadmill(
    private val context: Context,
    private val onReading: (TreadmillReading) -> Unit,
    private val onSafetyKey: (Boolean) -> Unit,
    private val onConnectedChanged: (Boolean) -> Unit,
) : Treadmill {
    private val main = Handler(Looper.getMainLooper())
    private var service: IFitOSService? = null
    private var bound = false
    private var lastTargetSpeed = 0f

    private val callback = object : ITreadmillDataCallback.Stub() {
        override fun onMetricUpdated(metric: TreadmillMetric?) {
            metric ?: return
            val reading = metric.toReading()
            main.post { onReading(reading) }
        }

        override fun onSafetyKeyTriggered(isDetached: Boolean) {
            main.post { onSafetyKey(isDetached) }
        }
    }

    /** FitOS 服務所在 process 若被系統回收，這裡收到通知後重新綁定並補註冊 callback。 */
    private val deathRecipient: IBinder.DeathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "FitOS service died, rebinding")
        service = null
        main.post {
            onConnectedChanged(false)
            context.unbindService(connection)
            bound = false
            main.postDelayed({ connect() }, REBIND_DELAY_MS)
        }
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IFitOSService.Stub.asInterface(binder) ?: return
            service = svc
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
            runCatching { svc.registerCallback(callback) }
            // 重啟後的服務是全新實例，目標速度回到 0；補送一次避免畫面顯示的速度與機台不符
            if (lastTargetSpeed > 0f) runCatching { svc.setTargetSpeed(lastTargetSpeed) }
            onConnectedChanged(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            onConnectedChanged(false)
        }
    }

    override fun connect() {
        if (bound) return
        bound = context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
    }

    override fun disconnect() {
        if (!bound) return
        runCatching { service?.unregisterCallback(callback) }
        context.unbindService(connection)
        bound = false
        service = null
        onConnectedChanged(false)
    }

    override fun setTargetSpeed(kmh: Float) {
        lastTargetSpeed = kmh
        runCatching { service?.setTargetSpeed(kmh) }
    }

    override fun currentReading(): TreadmillReading? =
        runCatching { service?.getCurrentMetric()?.toReading() }.getOrNull()

    private fun serviceIntent() = Intent(context, MockFitOSService::class.java)

    private fun TreadmillMetric.toReading() = TreadmillReading(
        speedKmh = speedKmh,
        totalDistanceMeters = totalDistanceMeters,
        incline = incline,
        cadence = cadence,
        timestampMs = timestamp,
        status = when (machineStatus) {
            TreadmillMetric.STATUS_RUNNING -> BeltStatus.RUNNING
            TreadmillMetric.STATUS_PAUSED -> BeltStatus.PAUSED
            else -> BeltStatus.IDLE
        },
    )

    private companion object {
        const val TAG = "FitOSTreadmill"
        const val REBIND_DELAY_MS = 500L
    }
}
