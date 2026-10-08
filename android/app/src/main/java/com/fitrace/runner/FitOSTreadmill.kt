package com.fitrace.runner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.ucare.fitosequipmentsdk.EquipmentLimits
import com.ucare.fitosequipmentsdk.EquipmentSdkContract
import com.ucare.fitosequipmentsdk.EquipmentServiceClient
import com.ucare.fitosequipmentsdk.EquipmentSnapshot
import com.ucare.fitosequipmentsdk.EquipmentState
import com.ucare.fitosequipmentsdk.IEquipmentCallback
import com.ucare.fitosequipmentsdk.IEquipmentService

/**
 * [Treadmill] 的正式 FitOS AIDL 實作：
 *
 * 1. 真機環境（若系統安裝有 FitOS `com.ucare.fitos` 服務）：
 *    使用官方 [EquipmentServiceClient]，依循《FitOS Equipment SDK Integration Guide v1》規範，
 *    以顯式 Intent 綁定 `com.ucare.fitos.action.EQUIPMENT_SERVICE`，
 *    支援斷線自動重連、主執行緒回呼分發、公英制轉換及速度安全邊界限制。
 *
 * 2. 開發 / 模擬器環境（若未安裝 FitOS）：
 *    自動 Fallback 綁定專案內建的 [MockFitOSService]（獨立 process，實作官方 AIDL [IEquipmentService.Stub]），
 *    走真實 Binder IPC 與 DeathRecipient 監聽，確保本地開發與自動化測試正常運作。
 */
class FitOSTreadmill(
    private val context: Context,
    private val onReading: (TreadmillReading) -> Unit,
    private val onSafetyKey: (Boolean) -> Unit,
    private val onConnectedChanged: (Boolean) -> Unit,
) : Treadmill {
    private val main = Handler(Looper.getMainLooper())
    private var lastTargetSpeed = 0f
    private var lastReading: TreadmillReading? = null

    // 官方 SDK Client (真機模式)
    private var officialClient: EquipmentServiceClient? = null
    private var isUsingOfficialClient = false

    // Mock 模式的 AIDL 連線與 Binder 管理
    private var mockService: IEquipmentService? = null
    private var mockBound = false

    private val officialCallback = object : EquipmentServiceClient.Callback() {
        override fun onServiceConnected() {
            Log.i(TAG, "Official FitOS service connected! API version: ${officialClient?.apiVersion}")
            onConnectedChanged(true)
            if (lastTargetSpeed > 0f) {
                applyTargetSpeed(lastTargetSpeed)
            }
        }

        override fun onServiceDisconnected() {
            Log.w(TAG, "Official FitOS service disconnected")
            onConnectedChanged(false)
        }

        override fun onConnectionStateChanged(state: EquipmentState?) {
            Log.d(TAG, "Connection state changed: ${state?.connectionStatus}, type: ${state?.equipmentType}")
        }

        override fun onEquipmentDataChanged(snapshot: EquipmentSnapshot?) {
            snapshot ?: return
            val reading = snapshot.toReading(
                isMetric = officialClient?.connectionState?.isMetric ?: true,
                controlState = officialClient?.connectionState?.controlState
            )
            lastReading = reading
            onReading(reading)
        }

        override fun onControlStateChanged(controlState: Int) {
            Log.d(TAG, "Control state changed: $controlState")
            // 根據整合指南：若使用者於機台按下 STOP 或拔除安全夾，controlState 會轉為 CONTROL_STATE_STOP
            if (controlState == EquipmentSdkContract.CONTROL_STATE_STOP) {
                onSafetyKey(true)
            } else if (controlState == EquipmentSdkContract.CONTROL_STATE_START) {
                onSafetyKey(false)
            }
        }
    }

    private val mockCallback = object : IEquipmentCallback.Stub() {
        override fun onConnectionStateChanged(state: EquipmentState?) {
            Log.d(TAG, "Mock connection state: ${state?.connectionStatus}")
        }

        override fun onEquipmentDataChanged(snapshot: EquipmentSnapshot?) {
            snapshot ?: return
            val reading = snapshot.toReading(
                isMetric = true,
                controlState = runCatching { mockService?.connectionState?.controlState }.getOrNull()
            )
            lastReading = reading
            main.post { onReading(reading) }
        }

        override fun onControlStateChanged(controlState: Int) {
            main.post {
                if (controlState == EquipmentSdkContract.CONTROL_STATE_STOP) {
                    onSafetyKey(true)
                } else if (controlState == EquipmentSdkContract.CONTROL_STATE_START) {
                    onSafetyKey(false)
                }
            }
        }

        override fun onDeviceListChanged(found: List<String>?, bound: List<String>?) {}
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Mock FitOS service process died, rebinding")
        mockService = null
        main.post {
            onConnectedChanged(false)
            if (mockBound) {
                runCatching { context.unbindService(mockConnection) }
                mockBound = false
                main.postDelayed({ connect() }, REBIND_DELAY_MS)
            }
        }
    }

    private val mockConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IEquipmentService.Stub.asInterface(binder) ?: return
            mockService = svc
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
            runCatching { svc.registerCallback(mockCallback) }
            if (lastTargetSpeed > 0f) {
                runCatching { svc.setSpeed(lastTargetSpeed.toDouble()) }
            }
            onConnectedChanged(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mockService = null
            onConnectedChanged(false)
        }
    }

    override fun connect() {
        if (isOfficialFitOsAvailable()) {
            Log.i(TAG, "Connecting via official FitOS EquipmentServiceClient...")
            isUsingOfficialClient = true
            if (officialClient == null) {
                officialClient = EquipmentServiceClient(context, officialCallback)
            }
            officialClient?.connect()
        } else {
            Log.i(TAG, "Official FitOS not present on device; connecting to MockFitOSService (AIDL Binder)...")
            isUsingOfficialClient = false
            if (mockBound) return
            val intent = Intent(context, MockFitOSService::class.java)
            mockBound = context.bindService(intent, mockConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun disconnect() {
        main.removeCallbacksAndMessages(null)
        if (isUsingOfficialClient) {
            runCatching { officialClient?.disconnect() }
            officialClient = null
            isUsingOfficialClient = false
        }
        if (mockBound) {
            runCatching { mockService?.asBinder()?.unlinkToDeath(deathRecipient, 0) }
            runCatching { mockService?.unregisterCallback(mockCallback) }
            runCatching { context.unbindService(mockConnection) }
            mockBound = false
            mockService = null
        }
        lastReading = null
        onConnectedChanged(false)
    }

    override fun setTargetSpeed(kmh: Float) {
        lastTargetSpeed = kmh
        applyTargetSpeed(kmh)
    }

    private fun applyTargetSpeed(kmh: Float) {
        if (isUsingOfficialClient) {
            val client = officialClient ?: return
            val isMetric = client.connectionState?.isMetric ?: true
            val limits = client.limits
            val minKmh = limits?.runSpeedMinKmh ?: 0.8
            val maxKmh = limits?.runSpeedMaxKmh ?: 25.0
            val clampedKmh = kmh.toDouble().coerceIn(minKmh, maxKmh)
            val sendValue = if (isMetric) clampedKmh else clampedKmh / 1.60934
            runCatching { client.setSpeed(sendValue) }
        } else {
            runCatching { mockService?.setSpeed(kmh.toDouble()) }
        }
    }

    override fun currentReading(): TreadmillReading? {
        if (isUsingOfficialClient) {
            val snap = runCatching { officialClient?.snapshot }.getOrNull()
            if (snap != null) {
                return snap.toReading(
                    isMetric = officialClient?.connectionState?.isMetric ?: true,
                    controlState = officialClient?.connectionState?.controlState
                ).also { lastReading = it }
            }
        } else {
            val snap = runCatching { mockService?.snapshot }.getOrNull()
            if (snap != null) {
                return snap.toReading(
                    isMetric = true,
                    controlState = runCatching { mockService?.connectionState?.controlState }.getOrNull()
                ).also { lastReading = it }
            }
        }
        return lastReading
    }

    private fun isOfficialFitOsAvailable(): Boolean {
        return try {
            val intent = Intent(EquipmentSdkContract.ACTION_BIND)
                .setPackage(EquipmentSdkContract.HOST_PACKAGE)
            val resolveInfo = context.packageManager.resolveService(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            false
        }
    }

    private fun EquipmentSnapshot.toReading(isMetric: Boolean, controlState: Int?): TreadmillReading {
        val rawSpeed = speed?.toDoubleOrNull() ?: 0.0
        val speedKmh = if (isMetric) rawSpeed.toFloat() else (rawSpeed * 1.60934).toFloat()

        val rawDistance = distance?.toDoubleOrNull() ?: 0.0
        val distanceMeters = if (isMetric) rawDistance * 1000.0 else rawDistance * 1609.344

        val inclineVal = incline?.toDoubleOrNull()?.toFloat() ?: 0f
        val cadenceVal = spm?.toIntOrNull() ?: 0

        val status = when (controlState) {
            EquipmentSdkContract.CONTROL_STATE_START -> BeltStatus.RUNNING
            EquipmentSdkContract.CONTROL_STATE_PAUSE -> BeltStatus.PAUSED
            EquipmentSdkContract.CONTROL_STATE_STOP -> BeltStatus.IDLE
            else -> if (speedKmh > 0.1f) BeltStatus.RUNNING else BeltStatus.IDLE
        }

        return TreadmillReading(
            speedKmh = speedKmh,
            totalDistanceMeters = distanceMeters,
            incline = inclineVal,
            cadence = cadenceVal,
            timestampMs = if (elapsedRealtimeMillis > 0) elapsedRealtimeMillis else System.currentTimeMillis(),
            status = status,
        )
    }

    companion object {
        private const val TAG = "FitOSTreadmill"
        private const val REBIND_DELAY_MS = 500L
    }
}
