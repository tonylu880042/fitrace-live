package com.fitrace.runner

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Deep Velocity HUD（§7）
private val Carbon = Color(0xFF040F14)
private val Deep = Color(0xFF0A1E27)
private val Cyan = Color(0xFF22E8F0)
private val Coral = Color(0xFFFF2D4F)
private val Amber = Color(0xFFFF8C1A)
private val Gold = Color(0xFFFFD700)
private val Label = Color(0xFF9BB0B8)

private val Grotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private const val SPEED_MAX_KMH = 25f
private const val CADENCE_MAX_SPM = 200f

enum class Screen { PROFILE, LOBBY, RACE }
enum class RaceViewMode { COCKPIT, LEADERBOARD }

data class Profile(
    val host: String = "10.0.2.2:8080", // 模擬器要透過 10.0.2.2 才能連到開發機的 localhost
    val runnerId: String = "RUNNER_01",
    val name: String = "Alex Chen",
    val country: String = "TW",
)

data class RunnerJoinedAlert(
    val event: RunnerJoinedEvent,
    val shownAtMs: Long,
    val seq: Long,
)

data class PodiumAlert(
    val rank: Int, // 1, 2, or 3
    val runnerId: String,
    val name: String,
    val country: String? = null,
    val finishTimeMs: Long,
    val isMe: Boolean,
    val atMs: Long = System.currentTimeMillis(),
)

data class RaceUiState(
    val screen: Screen = Screen.PROFILE,
    val profile: Profile = Profile(),
    // ── 大廳 ──
    val rooms: List<RoomInfo> = emptyList(),
    val lobbyOnline: Boolean = false,
    /** 大廳用的粗略時鐘偏差（不做 RTT 校正，只拿來顯示倒數與已跑時間） */
    val lobbyClockOffsetMs: Long = 0,
    /** 報名被拒等需要讓選手看到的訊息 */
    val lobbyNotice: String? = null,
    // ── 比賽 ──
    val roomId: String = "",
    val treadmillConnected: Boolean = false,
    val serverConnected: Boolean = false,
    val clockOffsetMs: Long = 0,
    val rttMs: Long = 0,
    val startAtServerTime: Long? = null,
    val raceDistanceM: Double = 5000.0,
    val speedKmh: Float = 0f,
    val targetSpeed: Float = 0f,
    val incline: Float = 0f,
    val distance: Double = 0.0,
    val pace: String = "--'--\"",
    val cadence: Int = 0,
    val finishTimeMs: Long? = null,
    val rank: Int? = null,
    val fieldSize: Int = 0,
    /** 落後領先者的公尺數；自己是領先者時為 0 */
    val gapToLeaderM: Double? = null,
    /** 與相鄰對手的公尺差：正值為落後前一名，負值為領先後一名 */
    val gapToNeighbourM: Double? = null,
    /** 領先者的競賽距離，賽道條上的 P1 標記用 */
    val leaderDistanceM: Double? = null,
    /** 與領先者的差距是否正在縮小；資料不足時為 null */
    val closingOnLeader: Boolean? = null,
    val beltStatus: BeltStatus = BeltStatus.IDLE,
    val safetyKeyDetached: Boolean = false,
    val cutoffAtServerTime: Long? = null,
    val dnf: Boolean = false,
    val closed: Boolean = false,
    /** 自己在最新榜單中的相鄰對手資訊（名次提示用） */
    val standing: Standing? = null,
    /** 名次提示狀態機（見 RaceTension） */
    val tension: Tension = Tension(),
    /** 視圖模式：座艙儀表或全場排行榜 */
    val viewMode: RaceViewMode = RaceViewMode.COCKPIT,
    /** 全場即時排行榜名單 */
    val leaderboard: List<RaceClient.Entry> = emptyList(),
    /** 選手進入房間提示（新增選手特效） */
    val newRunnerAlert: RunnerJoinedAlert? = null,
    /** 前三名完賽通知（步驟 8） */
    val podiumAlert: PodiumAlert? = null,
    /** 已通知完賽的凸台名次（避免重複彈出） */
    val notifiedPodiumRanks: Set<Int> = emptySet(),
    /** 比賽關閉時間戳記（用於 30 秒自動返回大廳倒數） */
    val closedAtMs: Long? = null,
) {
    /** 可以離開回大廳：尚未發令，或自己已完賽，或比賽已關閉。比賽中不給一鍵離開，免得誤觸。 */
    val canLeave: Boolean get() = startAtServerTime == null || finishTimeMs != null || closed

    /** 只有比賽進行中才能調速：已過發令時間、自己未完賽、比賽未關閉（DNF 必然已關閉）。 */
    fun canAdjustSpeed(serverNow: Long): Boolean =
        startAtServerTime != null && serverNow >= startAtServerTime && finishTimeMs == null && !closed

    /** 已報名進入比賽畫面、但還沒發令（等待排程或倒數中）；比賽已關閉則不算。 */
    fun inPreRace(serverNow: Long): Boolean =
        screen == Screen.RACE && roomId.isNotEmpty() && !closed &&
            (startAtServerTime == null || serverNow < startAtServerTime)

    /**
     * 比賽是否允許新挑戰者加入房間：
     * 嚴格限定在比賽尚未發令起跑的預備/倒數階段（inPreRace）。
     * 一旦發令鳴槍起跑（serverNow >= startAtServerTime）或賽事已關閉，立刻鎖定禁止加入。
     */
    fun canAcceptNewChallenger(serverNow: Long): Boolean = inPreRace(serverNow)

    /** 是否處於第 1 名完賽後的最後 100 秒衝刺倒數（步驟 9） */
    fun isFinalSprintCutoff(serverNow: Long): Boolean {
        val cutoff = cutoffAtServerTime ?: return false
        val remaining = cutoff - serverNow
        return screen == Screen.RACE && !closed && finishTimeMs == null && remaining in 1L..100_000L
    }

    /** 賽事結束後的 30 秒自動返回大廳倒數秒數；未關閉時為 null（步驟 10） */
    fun autoExitRemainingSeconds(serverNow: Long): Int? {
        val closedAt = closedAtMs ?: return null
        if (!closed) return null
        return kotlin.math.max(0, 30 - ((serverNow - closedAt) / 1000L).toInt())
    }
}

/**
 * 伺服器在每次（重）連線時都會補發 RACE_SCHEDULED。起跑時間與目前這場相同，就是同一場的重播。
 * 新的起跑時間（例如主辦方重新排程）才算新的一場。
 */
fun isReplayOfCurrentRace(currentStartAt: Long?, scheduledStartAt: Long): Boolean =
    currentStartAt != null && currentStartAt == scheduledStartAt

class RaceViewModel(app: Application) : AndroidViewModel(app), RaceClient.Listener {

    private val _state = MutableStateFlow(RaceUiState())
    val state = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient()
    private val engine = RaceCalculationEngine()
    private var client: RaceClient? = null
    private var sequence = 0L
    private var lastMetric: TreadmillReading? = null

    private val prefs = app.getSharedPreferences("fitrace", Context.MODE_PRIVATE)
    private val deviceId = loadOrCreateDeviceId()

    private val treadmill: Treadmill = FitOSTreadmill(
        context = app,
        onReading = ::onMetric,
        onSafetyKey = { detached -> _state.value = _state.value.copy(safetyKeyDetached = detached) },
        onConnectedChanged = { ok -> _state.value = _state.value.copy(treadmillConnected = ok) },
    )

    private fun loadOrCreateDeviceId(): String {
        var id = prefs.getString("deviceId", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("deviceId", id).apply()
        }
        return id
    }

    private fun loadProfileFromPrefs(): Profile? {
        val host = prefs.getString("host", null) ?: return null
        val runnerId = prefs.getString("runnerId", null) ?: return null
        val name = prefs.getString("name", null) ?: return null
        val country = prefs.getString("country", null) ?: ""
        return Profile(host, runnerId, name, country)
    }

    private fun saveProfileToPrefs(profile: Profile) {
        prefs.edit().apply {
            putString("host", profile.host)
            putString("runnerId", profile.runnerId)
            putString("name", profile.name)
            putString("country", profile.country)
        }.apply()
    }

    init {
        // 加載之前保存的個人資料作為初始狀態
        loadProfileFromPrefs()?.let { profile ->
            _state.value = _state.value.copy(profile = profile)
        }
    }

    /* ── 個人資料 → 大廳 ── */

    fun enterLobby(profile: Profile) {
        saveProfileToPrefs(profile)
        // 在大廳就接上跑步機，讓選手看到連線狀態；發令前皮帶一律停住，見 onMetric()
        treadmill.connect()
        _state.value = _state.value.copy(screen = Screen.LOBBY, profile = profile, lobbyNotice = null)
        main.removeCallbacks(lobbyPoll)
        main.post(lobbyPoll)
    }

    fun editProfile() {
        main.removeCallbacks(lobbyPoll)
        _state.value = _state.value.copy(screen = Screen.PROFILE)
    }

    /** 每 2 秒刷新一次大廳；只在大廳畫面時才跑。 */
    private val lobbyPoll = object : Runnable {
        override fun run() {
            val host = _state.value.profile.host
            viewModelScope.launch(Dispatchers.IO) {
                val result = runCatching { fetchRooms(http, host) }
                val receivedAt = System.currentTimeMillis()
                main.post {
                    if (_state.value.screen != Screen.LOBBY) return@post
                    _state.value = result.fold(
                        onSuccess = { (serverTime, rooms) ->
                            _state.value.copy(
                                rooms = rooms, lobbyOnline = true,
                                lobbyClockOffsetMs = serverTime - receivedAt,
                            )
                        },
                        onFailure = { _state.value.copy(lobbyOnline = false) },
                    )
                }
            }
            main.postDelayed(this, LOBBY_REFRESH_MS)
        }
    }

    /* ── 大廳 ↔ 比賽 ── */

    fun join(roomId: String) {
        main.removeCallbacks(lobbyPoll)
        main.removeCallbacks(uploadTick)
        val p = _state.value.profile
        engine.reset()
        sequence = 0
        client?.close()
        client = RaceClient(p.host, roomId, p.runnerId, p.name, p.country.ifBlank { null }, deviceId, this, http)
            .also { it.connect() }
        _state.value = clearedRace(_state.value, Screen.RACE, roomId = roomId)
        main.post(uploadTick)
    }

    fun leaveToLobby(notice: String? = null) {
        // 若尚未發令，先在背景取消報名
        val s = _state.value
        if (s.startAtServerTime == null && s.roomId.isNotEmpty()) {
            // 先取出值再進協程：下面 clearedRace() 會立刻把 roomId 清空
            val host = s.profile.host
            val roomId = s.roomId
            val runnerId = s.profile.runnerId
            viewModelScope.launch(Dispatchers.IO) {
                cancelRegistration(http, host, roomId, runnerId, deviceId)
            }
        }
        client?.close()
        client = null
        // 上傳、尚未觸發的發令歸零都要取消，否則會影響下一場
        main.removeCallbacksAndMessages(null)
        engine.reset()
        _state.value = clearedRace(_state.value, Screen.LOBBY, notice = notice)
        main.post(lobbyPoll)
    }

    /** 清掉上一場的比賽數據，但保留跑步機即時狀態、個人資料與大廳清單。 */
    private fun clearedRace(s: RaceUiState, screen: Screen, roomId: String = "", notice: String? = null) =
        RaceUiState(
            screen = screen, profile = s.profile, roomId = roomId,
            rooms = s.rooms, lobbyOnline = s.lobbyOnline, lobbyClockOffsetMs = s.lobbyClockOffsetMs,
            lobbyNotice = notice,
            treadmillConnected = s.treadmillConnected, speedKmh = s.speedKmh, targetSpeed = s.targetSpeed,
            incline = s.incline, cadence = s.cadence, beltStatus = s.beltStatus,
            safetyKeyDetached = s.safetyKeyDetached,
        )

    fun serverNow(): Long = client?.serverNow() ?: System.currentTimeMillis()

    fun setViewMode(mode: RaceViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun toggleViewMode() {
        _state.update { current ->
            val next = if (current.viewMode == RaceViewMode.COCKPIT) RaceViewMode.LEADERBOARD else RaceViewMode.COCKPIT
            android.util.Log.d("RaceVM", "toggleViewMode: $current -> $next")
            current.copy(viewMode = next)
        }
    }

    fun enterVerificationRace(
        profile: Profile? = null,
        cockpitMode: Boolean = false,
        preRaceCountdownSec: Int? = null,
    ) {
        val p = profile ?: _state.value.profile
        saveProfileToPrefs(p)
        treadmill.connect()
        val raceDist = 5000.0
        val now = System.currentTimeMillis()
        if (preRaceCountdownSec != null) {
            // 準備起跑階段（熱身/倒數中）：發令時間在未來，等待槍響前可展示挑戰者加入
            val startAt = now + (preRaceCountdownSec * 1000L)
            val sampleBoard = sampleLeaderboard(p.runnerId, 0.0, raceDist)
            _state.update {
                RaceUiState(
                    screen = Screen.RACE,
                    profile = p,
                    roomId = "R0001",
                    serverConnected = true,
                    treadmillConnected = true,
                    startAtServerTime = startAt,
                    raceDistanceM = raceDist,
                    speedKmh = 0.0f,
                    targetSpeed = 0.0f,
                    incline = 1.0f,
                    distance = 0.0,
                    pace = "--'--\"",
                    cadence = 0,
                    rank = 6,
                    fieldSize = 5,
                    gapToLeaderM = 3650.0,
                    gapToNeighbourM = null,
                    leaderDistanceM = 3650.0,
                    beltStatus = BeltStatus.IDLE,
                    viewMode = if (cockpitMode) RaceViewMode.COCKPIT else RaceViewMode.LEADERBOARD,
                    leaderboard = sampleBoard,
                    tension = Tension(lastRank = 6, lastField = 5),
                )
            }
        } else {
            // 競速進行中模式（用於直接檢視中途 HUD / 排行榜數據）
            val myDist = 3230.0
            val sampleBoard = sampleLeaderboard(p.runnerId, myDist, raceDist)
            val startAt = now - 480_000L // 8 minutes ago
            _state.update {
                RaceUiState(
                    screen = Screen.RACE,
                    profile = p,
                    roomId = "R0001",
                    serverConnected = true,
                    treadmillConnected = true,
                    startAtServerTime = startAt,
                    raceDistanceM = raceDist,
                    speedKmh = 14.8f,
                    targetSpeed = 15.0f,
                    incline = 1.0f,
                    distance = myDist,
                    pace = "04'05\"",
                    cadence = 182,
                    rank = 2,
                    fieldSize = sampleBoard.size,
                    gapToLeaderM = 420.0,
                    gapToNeighbourM = 150.0,
                    leaderDistanceM = 3650.0,
                    beltStatus = BeltStatus.RUNNING,
                    viewMode = if (cockpitMode) RaceViewMode.COCKPIT else RaceViewMode.LEADERBOARD,
                    leaderboard = sampleBoard,
                )
            }
        }
    }

    fun setVerificationRunningMetrics(speedKmh: Float, distanceM: Double) {
        val s = _state.value
        val now = serverNow()
        val raceDist = s.raceDistanceM.takeIf { it > 0.0 } ?: 5000.0
        val updatedBoard = sampleLeaderboard(s.profile.runnerId, distanceM, raceDist)
        val sorted = updatedBoard.sortedBy { it.rank }
        val myIndex = sorted.indexOfFirst { it.runnerId == s.profile.runnerId }
        val me = sorted.getOrNull(myIndex)
        val neighbour = if (myIndex == 0) sorted.getOrNull(1) else sorted.getOrNull(myIndex - 1)
        val gapToLeader = me?.let { (sorted.firstOrNull()?.distance ?: it.distance) - it.distance }
        val standing = standingOf(sorted, s.profile.runnerId)
        val active = s.canAdjustSpeed(now)
        val tension = RaceTension.next(s.tension, standing, active = active, nowMs = now)

        val secPerKm = if (speedKmh > 0.5f) (3600.0 / speedKmh).roundToInt() else 0
        val calcPace = if (speedKmh > 0.5f) "%02d'%02d\"".format(secPerKm / 60, secPerKm % 60) else "--'--\""
        val calcCadence = if (speedKmh > 0.5f) (152 + (speedKmh * 2.1f).toInt()).coerceIn(160, 205) else 0

        _state.update { current ->
            current.copy(
                speedKmh = speedKmh,
                targetSpeed = speedKmh,
                distance = distanceM,
                cadence = calcCadence,
                pace = calcPace,
                beltStatus = if (speedKmh > 0.1f) BeltStatus.RUNNING else BeltStatus.IDLE,
                rank = me?.rank ?: current.rank,
                fieldSize = sorted.size,
                leaderboard = sorted,
                gapToLeaderM = gapToLeader,
                gapToNeighbourM = if (me != null && neighbour != null) neighbour.distance - me.distance else null,
                leaderDistanceM = sorted.firstOrNull()?.distance,
                standing = standing,
                tension = tension,
            )
        }
    }

    fun nudgeSpeed(delta: Float) {
        // UI 已停用按鈕，這裡再擋一次，避免倒數最後一瞬間的點擊漏過去
        if (!_state.value.canAdjustSpeed(serverNow())) return
        val target = (_state.value.targetSpeed + delta).coerceIn(0f, SPEED_MAX_KMH)
        treadmill.setTargetSpeed(target)
        _state.update { it.copy(targetSpeed = target, speedKmh = target) }
    }

    private fun onMetric(metric: TreadmillReading) {
        lastMetric = metric
        val s = _state.value
        if (!engine.isArmed) {
            // 不開放熱身：先跑起來的人發令時是帶速起跑，對靜止起跑的人不公平。
            // app 的速度鍵已鎖，這裡再擋機台實體按鍵——只在已報名、發令前（inPreRace）皮帶一動就下令歸零；大廳、個人檔案、完賽後緩跑都不干預。
            // 用 inPreRace 而非 isArmed 判斷，避免發令瞬間 arm() 還沒執行就把剛起步的皮帶停掉。
            if (metric.speedKmh > 0.1f && s.inPreRace(serverNow())) treadmill.setTargetSpeed(0f)
            _state.update { current ->
                current.copy(
                    speedKmh = if (current.speedKmh > 0.5f) current.speedKmh else metric.speedKmh,
                    cadence = if (current.cadence > 0) current.cadence else metric.cadence,
                    incline = metric.incline,
                    beltStatus = metric.status,
                )
            }
            return
        }
        val sample = engine.update(
            metric.totalDistanceMeters, metric.speedKmh, metric.cadence, serverNow(),
        )
        // 達成賽事距離即讓跑步機停下；減速曲線交給跑步機主控台／馬達控制器（模擬器為每秒 2 km/h）
        val target = if (sample.justFinished) {
            treadmill.setTargetSpeed(0f)
            0f
        } else s.targetSpeed
        _state.update { current ->
            current.copy(
                speedKmh = metric.speedKmh,
                targetSpeed = target,
                incline = metric.incline,
                beltStatus = metric.status,
                distance = sample.raceDistanceM,
                pace = sample.pace,
                cadence = sample.cadence,
                finishTimeMs = sample.finishTimeMs,
                // 撞線當下就收起名次提示，不等下一筆榜單
                tension = if (sample.justFinished) RaceTension.next(current.tension, null, false, serverNow()) else current.tension,
            )
        }
    }

    /** 本地 10Hz 供儀表板刷新，對雲端節流到 2.5Hz（§2.2-4）。 */
    private val uploadTick = object : Runnable {
        override fun run() {
            val s = _state.value
            val startAt = s.startAtServerTime
            val elapsed = if (startAt != null) max(0L, serverNow() - startAt) else 0L
            client?.sendTelemetry(
                sequenceId = sequence++,
                raceElapsedMs = elapsed,
                distance = s.distance,
                speedKmh = s.speedKmh,
                cadence = s.cadence,
                pace = s.pace,
                finishTimeMs = s.finishTimeMs,
            )
            main.postDelayed(this, UPLOAD_INTERVAL_MS)
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        val s = _state.value
        // 重連後的第一筆榜單只當基準：斷線期間的名次變化不算超越
        _state.value = s.copy(
            serverConnected = connected,
            tension = if (connected) RaceTension.onReconnect(s.tension) else s.tension,
        )
    }

    override fun onClockSynced(offsetMs: Long, rttMs: Long) {
        _state.value = _state.value.copy(clockOffsetMs = offsetMs, rttMs = rttMs)
    }

    override fun onJoinRejected(reason: String) {
        val room = _state.value.roomId.replace('_', ' ')
        val why = when (reason) {
            "REGISTRATION_CLOSED" -> "ENTRY CLOSED"
            "ROOM_FULL" -> "ROOM FULL"
            "RUNNER_ID_IN_USE" -> "RUNNER ID ALREADY IN USE ON ANOTHER TREADMILL"
            "ROOM_NOT_FOUND" -> "RACE NO LONGER EXISTS"
            else -> "ENTRY CLOSED"
        }
        leaveToLobby("$room — $why")
    }

    override fun onRaceScheduled(startAtServerTime: Long, raceDistanceM: Double, cutoffAtServerTime: Long?) {
        // 每次（重）連線伺服器都會補發 RACE_SCHEDULED。同一個起跑時間就是同一場的重播：
        // 不可歸零距離、清掉完賽成績或重新 arm()，否則 Wi-Fi 一斷，選手的距離就從 0 重來
        if (isReplayOfCurrentRace(_state.value.startAtServerTime, startAtServerTime)) {
            _state.value = _state.value.copy(raceDistanceM = raceDistanceM, cutoffAtServerTime = cutoffAtServerTime)
            return
        }
        _state.value = _state.value.copy(
            startAtServerTime = startAtServerTime,
            raceDistanceM = raceDistanceM,
            cutoffAtServerTime = cutoffAtServerTime,
            finishTimeMs = null,
            distance = 0.0,
        )
        // 發令槍響的那一刻才歸零，之前機台跑了多少都不算（§2.2-1）
        val delay = max(0L, startAtServerTime - serverNow())
        main.postDelayed({
            engine.arm(
                lastMetric?.totalDistanceMeters ?: treadmill.currentReading()?.totalDistanceMeters ?: 0.0,
                raceDistanceM,
            )
        }, delay)
    }

    override fun onLeaderboard(entries: List<RaceClient.Entry>) {
        val sorted = entries.sortedBy { it.rank }
        val runnerId = _state.value.profile.runnerId
        val now = serverNow()
        val current = _state.value

        // 步驟 8：偵測前三名完賽事件 (Podium Finish Alert)
        val currentPodiumRanks = current.notifiedPodiumRanks
        val newPodiumEntry = sorted.firstOrNull { entry ->
            entry.rank in 1..3 && (entry.status == "FINISHED" || entry.finishTimeMs != null) &&
                entry.rank !in currentPodiumRanks
        }
        val newPodiumAlert = newPodiumEntry?.let { entry ->
            PodiumAlert(
                rank = entry.rank,
                runnerId = entry.runnerId,
                name = entry.name,
                country = entry.country,
                finishTimeMs = entry.finishTimeMs ?: now,
                isMe = entry.runnerId == runnerId,
                atMs = now,
            )
        }
        val updatedPodiumRanks = if (newPodiumEntry != null) {
            currentPodiumRanks + newPodiumEntry.rank
        } else currentPodiumRanks

        // 步驟 9：若第 1 名完賽且尚未設定 100 秒關門倒數，動態啟動 100 秒衝刺倒數
        val p1Finished = sorted.any { it.rank == 1 && (it.status == "FINISHED" || it.finishTimeMs != null) }
        val updatedCutoff = if (p1Finished && current.cutoffAtServerTime == null) {
            now + 100_000L
        } else current.cutoffAtServerTime

        val standing = standingOf(sorted, runnerId)
        val tension = RaceTension.next(current.tension, standing, current.canAdjustSpeed(now), now)
        // 完賽後名次已定，差距凍結在撞線當下；否則其他人繼續跑會讓「領先幅度」一路縮到 0
        if (current.finishTimeMs != null) {
            _state.value = current.copy(
                fieldSize = sorted.size,
                tension = tension,
                leaderboard = sorted,
                podiumAlert = newPodiumAlert ?: current.podiumAlert,
                notifiedPodiumRanks = updatedPodiumRanks,
                cutoffAtServerTime = updatedCutoff,
            )
            return
        }
        val myIndex = sorted.indexOfFirst { it.runnerId == runnerId }
        val me = sorted.getOrNull(myIndex)
        // 領先者看的是對第 2 名的領先幅度，其餘人看的是對前一名的落後幅度
        val neighbour = if (myIndex == 0) sorted.getOrNull(1) else sorted.getOrNull(myIndex - 1)
        val gapToLeader = me?.let { (sorted.firstOrNull()?.distance ?: it.distance) - it.distance }
        val previous = current.gapToLeaderM
        _state.update { curr ->
            curr.copy(
                rank = me?.rank,
                fieldSize = sorted.size,
                leaderboard = sorted,
                gapToLeaderM = gapToLeader,
                gapToNeighbourM = if (me != null && neighbour != null) neighbour.distance - me.distance else null,
                leaderDistanceM = sorted.firstOrNull()?.distance,
                // 與上一次廣播相比差距是否縮小；差距變化小於一公尺視為持平
                closingOnLeader = if (gapToLeader != null && previous != null &&
                    kotlin.math.abs(gapToLeader - previous) >= 1.0
                ) gapToLeader < previous else null,
                standing = standing,
                tension = tension,
                podiumAlert = newPodiumAlert ?: curr.podiumAlert,
                notifiedPodiumRanks = updatedPodiumRanks,
                cutoffAtServerTime = updatedCutoff,
            )
        }
    }

    override fun onRaceClosed(reason: String) {
        val now = serverNow()
        _state.value = _state.value.copy(
            closed = true,
            closedAtMs = now,
        ).let {
            it.copy(tension = RaceTension.next(it.tension, null, active = false, nowMs = now))
        }
        // 未完賽的情況下比賽被關閉，標記為 DNF 並凍結距離
        if (_state.value.finishTimeMs == null) {
            treadmill.setTargetSpeed(0f)
            _state.value = _state.value.copy(targetSpeed = 0f, dnf = true)
            engine.reset()
        }
    }

    override fun onRaceCancelled() {
        val room = _state.value.roomId.replace('_', ' ')
        leaveToLobby("$room — RACE CANCELLED BY ORGANIZER")
    }

    override fun onRunnerJoined(event: RunnerJoinedEvent) {
        // 自己進入房間不需彈出提示
        if (event.runnerId == _state.value.profile.runnerId) return
        val s = _state.value
        val now = serverNow()
        // 規則嚴格鎖定：僅在比賽尚未起跑（Pre-Race 預備/倒數階段）允許新選手加入。
        // 比賽一旦發令鳴槍起跑（now >= startAtServerTime）或賽事已關閉，立刻鎖定房間並忽略任何加入事件。
        if (!s.canAcceptNewChallenger(now)) {
            android.util.Log.i("RaceVM", "Runner joined ignored: race is already locked/running (now=$now, startAt=${s.startAtServerTime})")
            return
        }
        val seq = System.currentTimeMillis()
        val curField = s.fieldSize
        val newField = if (event.fieldSize > 0) event.fieldSize else curField + 1
        _state.update { current ->
            current.copy(
                fieldSize = newField,
                newRunnerAlert = RunnerJoinedAlert(event, System.currentTimeMillis(), seq),
                tension = current.tension.copy(lastField = newField),
            )
        }
    }

    fun dismissRunnerAlert() {
        _state.update { it.copy(newRunnerAlert = null) }
    }

    fun triggerMockRunnerJoinedAlert() {
        val s = _state.value
        val now = serverNow()
        if (!s.canAcceptNewChallenger(now)) {
            android.util.Log.w("RaceVM", "Cannot trigger mock alert: race is locked/running")
            return
        }
        val mockEvent = RunnerJoinedEvent(
            roomId = s.roomId.ifEmpty { "R0001" },
            runnerId = "R_ELIUD",
            name = "Eliud Kipchoge",
            country = "KE",
            bib = "341",
            avatarUrl = null,
            lane = 3,
            deviceId = "TREADMILL CONSOLE #03",
            fieldSize = (s.fieldSize.takeIf { it > 0 } ?: 5) + 1,
            capacity = 8,
            tier = "WORLD CLASS TIER",
            bio = "Marathon World Record Holder · 5,000M Olympic Finalist",
            pr5k = "14:15.0",
            targetPace = "02:50.4",
            vo2Max = 84.2f,
            serverTime = System.currentTimeMillis(),
        )
        onRunnerJoined(mockEvent)
    }

    fun dismissPodiumAlert() {
        _state.update { it.copy(podiumAlert = null) }
    }

    fun triggerMockPodiumAlert(rank: Int = 1) {
        val s = _state.value
        val now = serverNow()
        val startAt = s.startAtServerTime ?: (now - 860_000L)
        val isMe = rank == (s.rank ?: 2)
        val name = when (rank) {
            1 -> "Eliud Kipchoge"
            2 -> if (isMe) s.profile.name else "Kenenisa Bekele"
            3 -> "Joshua Cheptegei"
            else -> "Elite Runner"
        }
        val country = when (rank) {
            1 -> "KE"
            2 -> if (isMe) s.profile.country else "ET"
            3 -> "UG"
            else -> "US"
        }
        val finishTime = startAt + when (rank) {
            1 -> 855_000L // 14:15.0
            2 -> 868_200L // 14:28.2
            3 -> 882_500L // 14:42.5
            else -> 900_000L
        }
        val alert = PodiumAlert(
            rank = rank,
            runnerId = if (isMe) s.profile.runnerId else "MOCK_P$rank",
            name = name,
            country = country,
            finishTimeMs = finishTime,
            isMe = isMe,
            atMs = now,
        )
        _state.update {
            it.copy(
                podiumAlert = alert,
                notifiedPodiumRanks = it.notifiedPodiumRanks + rank,
            )
        }
    }

    fun triggerMockFinalResults(userFinished: Boolean = true, userRank: Int = 2) {
        val s = _state.value
        val now = serverNow()
        val raceDist = s.raceDistanceM.takeIf { it > 0.0 } ?: 5000.0
        val startAt = now - 920_000L // ~15m20s ago
        val userFinishTime = if (userFinished) startAt + 872_000L else null // 14:32.0

        val finalBoard = listOf(
            RaceClient.Entry(
                rank = 1, runnerId = "R_ELIUD", name = "Eliud Kipchoge",
                distance = raceDist, pace = "02'51\"", status = "FINISHED",
                finishTimeMs = startAt + 855_000L, country = "KE", speedKmh = 21.0f, cadence = 194,
                progressPercent = 1.0, gapToLeaderMs = 0L, gapToAheadMs = 0L,
            ),
            RaceClient.Entry(
                rank = 2, runnerId = if (userRank == 2) s.profile.runnerId else "R_KENENISA",
                name = if (userRank == 2) s.profile.name else "Kenenisa Bekele",
                distance = if (userFinished && userRank == 2) raceDist else 4680.0,
                pace = "02'54\"",
                status = if (userFinished || userRank != 2) "FINISHED" else "DNF",
                finishTimeMs = if (userFinished && userRank == 2) userFinishTime else (startAt + 870_000L),
                country = if (userRank == 2) s.profile.country else "ET",
                speedKmh = 20.6f, cadence = 190,
                progressPercent = if (userFinished && userRank == 2) 1.0 else 0.936,
                gapToLeaderMs = 15_000L, gapToAheadMs = 15_000L,
            ),
            RaceClient.Entry(
                rank = 3, runnerId = if (userRank == 3) s.profile.runnerId else "R_JOSHUA",
                name = if (userRank == 3) s.profile.name else "Joshua Cheptegei",
                distance = raceDist, pace = "02'56\"", status = "FINISHED",
                finishTimeMs = startAt + 880_000L, country = "UG", speedKmh = 20.4f, cadence = 188,
                progressPercent = 1.0, gapToLeaderMs = 25_000L, gapToAheadMs = 10_000L,
            ),
            RaceClient.Entry(
                rank = 4, runnerId = if (userRank == 4) s.profile.runnerId else "R_KENJI",
                name = if (userRank == 4) s.profile.name else "Kenji Sato",
                distance = 4820.0, pace = "03'08\"", status = "DNF",
                finishTimeMs = null, country = "JP", speedKmh = 19.1f, cadence = 182,
                progressPercent = 0.964, gapToLeaderMs = 52_000L, gapToAheadMs = 27_000L,
            ),
            RaceClient.Entry(
                rank = 5, runnerId = if (userRank == 5) s.profile.runnerId else "R_SARAH",
                name = if (userRank == 5) s.profile.name else "Sarah Connor",
                distance = 4510.0, pace = "03'18\"", status = "DNF",
                finishTimeMs = null, country = "US", speedKmh = 18.2f, cadence = 176,
                progressPercent = 0.902, gapToLeaderMs = 85_000L, gapToAheadMs = 33_000L,
            ),
        )

        _state.update {
            it.copy(
                screen = Screen.RACE,
                roomId = "R0001",
                serverConnected = true,
                treadmillConnected = true,
                startAtServerTime = startAt,
                raceDistanceM = raceDist,
                distance = if (userFinished) raceDist else 4680.0,
                speedKmh = 0f,
                targetSpeed = 0f,
                rank = if (userFinished) userRank else null,
                fieldSize = finalBoard.size,
                finishTimeMs = userFinishTime,
                dnf = !userFinished,
                closed = true,
                closedAtMs = now,
                leaderboard = finalBoard,
                viewMode = RaceViewMode.LEADERBOARD,
            )
        }
    }

    override fun onCleared() {
        main.removeCallbacksAndMessages(null)
        treadmill.disconnect()
        client?.close()
        super.onCleared()
    }

    private companion object {
        const val UPLOAD_INTERVAL_MS = 400L // 2.5Hz
        const val LOBBY_REFRESH_MS = 2000L
    }
}

class MainActivity : ComponentActivity() {
    private var vmRef: RaceViewModel? = null

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        if (newIntent.getBooleanExtra("verify_runner_pre_race", false)) {
            val countdownSec = newIntent.getIntExtra("countdown_sec", 6)
            vmRef?.enterVerificationRace(cockpitMode = true, preRaceCountdownSec = countdownSec)
        }
        if (newIntent.getBooleanExtra("verify_runner_cockpit", false)) {
            vmRef?.enterVerificationRace(cockpitMode = true)
        }
        if (newIntent.getBooleanExtra("verify_runner_leaderboard", false)) {
            vmRef?.enterVerificationRace(cockpitMode = false)
        }
        if (newIntent.getBooleanExtra("verify_runner_alert", false)) {
            vmRef?.triggerMockRunnerJoinedAlert()
        }
        if (newIntent.getBooleanExtra("verify_podium_alert", false)) {
            val rank = newIntent.getIntExtra("podium_rank", 1)
            vmRef?.triggerMockPodiumAlert(rank)
        }
        if (newIntent.getBooleanExtra("verify_final_results", false)) {
            val finished = newIntent.getBooleanExtra("user_finished", true)
            val rank = newIntent.getIntExtra("user_rank", 2)
            vmRef?.triggerMockFinalResults(userFinished = finished, userRank = rank)
        }
        if (newIntent.hasExtra("sim_speed")) {
            val spd = newIntent.getFloatExtra("sim_speed", 16.0f)
            val dist = newIntent.getDoubleExtra("sim_dist", 3200.0)
            vmRef?.setVerificationRunningMetrics(spd, dist)
        }
        if (newIntent.getBooleanExtra("toggle_view_mode", false)) {
            vmRef?.toggleViewMode()
        }
        if (newIntent.getStringExtra("set_view_mode") == "leaderboard") {
            vmRef?.setViewMode(RaceViewMode.LEADERBOARD)
        }
        if (newIntent.getStringExtra("set_view_mode") == "cockpit") {
            vmRef?.setViewMode(RaceViewMode.COCKPIT)
        }
        if (newIntent.hasExtra("nudge_speed")) {
            vmRef?.nudgeSpeed(newIntent.getFloatExtra("nudge_speed", 1.0f))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Carbon, surface = Deep)) {
                val vm: RaceViewModel = viewModel()
                vmRef = vm
                LaunchedEffect(Unit) {
                    if (intent?.getBooleanExtra("verify_runner_pre_race", false) == true) {
                        val countdownSec = intent?.getIntExtra("countdown_sec", 6) ?: 6
                        vm.enterVerificationRace(cockpitMode = true, preRaceCountdownSec = countdownSec)
                    } else if (intent?.getBooleanExtra("verify_leaderboard", false) == true) {
                        vm.enterVerificationRace(cockpitMode = false)
                    } else if (intent?.getBooleanExtra("verify_runner_cockpit", false) == true) {
                        vm.enterVerificationRace(cockpitMode = true)
                    }
                    if (intent?.getBooleanExtra("verify_runner_alert", false) == true) {
                        vm.triggerMockRunnerJoinedAlert()
                    }
                    if (intent?.getBooleanExtra("verify_podium_alert", false) == true) {
                        val rank = intent?.getIntExtra("podium_rank", 1) ?: 1
                        vm.triggerMockPodiumAlert(rank)
                    }
                    if (intent?.getBooleanExtra("verify_final_results", false) == true) {
                        val finished = intent?.getBooleanExtra("user_finished", true) ?: true
                        val rank = intent?.getIntExtra("user_rank", 2) ?: 2
                        vm.triggerMockFinalResults(userFinished = finished, userRank = rank)
                    }
                }
                val state by vm.state.collectAsState()
                when (state.screen) {
                    Screen.PROFILE -> Setup(state.profile, vm)
                    Screen.LOBBY -> Lobby(state, vm)
                    Screen.RACE -> Hud(state, vm)
                }
            }
        }
    }
}

/* ─────────────────────────── 個人資料 ─────────────────────────── */

@Composable
private fun Setup(initial: Profile, vm: RaceViewModel) {
    var host by remember { mutableStateOf(initial.host) }
    var id by remember { mutableStateOf(initial.runnerId) }
    var name by remember { mutableStateOf(initial.name) }
    var country by remember { mutableStateOf(initial.country) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Carbon).drawBehind { ambientBackdrop() }) {
        val k = min(maxWidth.value / 1280f, maxHeight.value / 800f)
        Box(
            Modifier.align(Alignment.TopStart).padding(start = (36 * k).dp).height((84 * k).dp),
            contentAlignment = Alignment.CenterStart,
        ) { FitRaceLogo(k) }

        Column(
            Modifier.align(Alignment.Center).width((560 * k).dp).glass(k)
                .padding(horizontal = (40 * k).dp, vertical = (32 * k).dp),
            verticalArrangement = Arrangement.spacedBy((12 * k).dp),
        ) {
            SoftLabel("ATHLETE PROFILE", Cyan, k, 15f)
            Text(
                "Ready to race?", color = Color.White, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
                fontSize = (30 * k).sp, letterSpacing = (-0.4 * k).sp,
            )
            Spacer(Modifier.height((4 * k).dp))
            val shape = RoundedCornerShape((14 * k).dp)
            val colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Cyan, unfocusedBorderColor = Color.White.copy(alpha = .16f),
                focusedLabelColor = Cyan, unfocusedLabelColor = Label, cursorColor = Cyan,
                focusedContainerColor = Cyan.copy(alpha = .05f), unfocusedContainerColor = Color.White.copy(alpha = .03f),
            )
            listOf(
                Triple("伺服器", host) { v: String -> host = v },
                Triple("選手 ID", id) { v: String -> id = v },
                Triple("姓名", name) { v: String -> name = v },
                Triple("國碼 (ISO 兩碼)", country) { v: String -> country = v },
            ).forEach { (label, value, set) ->
                OutlinedTextField(
                    value = value, onValueChange = set,
                    label = { Text(label, fontFamily = Grotesk, fontWeight = FontWeight.Medium) },
                    textStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = (17 * k).sp),
                    singleLine = true, shape = shape, colors = colors, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height((8 * k).dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((10 * k).dp)) {
                Box(Modifier.weight(1.1f)) {
                    PrimaryPill("ENTER LOBBY", k) {
                        vm.enterLobby(Profile(host.trim(), id.trim(), name.trim(), country.trim().uppercase()))
                    }
                }
                Box(Modifier.weight(0.95f)) {
                    SecondaryPill("VERIFY HUD", k) {
                        vm.enterVerificationRace(Profile(host.trim(), id.trim(), name.trim(), country.trim().uppercase()))
                    }
                }
                Box(Modifier.weight(1.05f)) {
                    SecondaryPill("PRE-RACE HEAT", k) {
                        vm.enterVerificationRace(
                            Profile(host.trim(), id.trim(), name.trim(), country.trim().uppercase()),
                            cockpitMode = true,
                            preRaceCountdownSec = 6,
                        )
                        vm.triggerMockRunnerJoinedAlert()
                    }
                }
            }
        }
    }
}

/* ─────────────────────────── 競速 HUD ─────────────────────────── */

/**
 * 霓虹儀表風格：柔光弧線、玻璃卡片、圓潤的 Space Grotesk 等寬數字（tnum，高頻刷新不跳動）。
 * 以 1280x800 為基準等比縮放，換機台解析度不需重排版。
 */
@Composable
private fun Hud(s: RaceUiState, vm: RaceViewModel) {
    var now by remember { mutableLongStateOf(vm.serverNow()) }
    LaunchedEffect(Unit) {
        while (true) { now = vm.serverNow(); delay(50) }
    }
    val startAt = s.startAtServerTime
    val phase = countdownPhase(startAt, now)
    // 倒數與名次提示共用同一組 TTS／ToneGenerator；進 HUD 就初始化，槍響前引擎已暖好
    val context = LocalContext.current
    val audio = remember { CountdownAudio(context.applicationContext) }
    DisposableEffect(audio) { onDispose { audio.release() } }
    CountdownSounds(phase, audio)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Carbon).drawBehind { ambientBackdrop(streams = false) }
    ) {
        val k = min(maxWidth.value / 1280f, maxHeight.value / 800f)
        TunnelBackdrop(s.speedKmh)

        Column(Modifier.fillMaxSize().padding(start = (36 * k).dp, end = (36 * k).dp, bottom = (26 * k).dp)) {
            TopBar(s, now, startAt, phase, k, onLeave = { vm.leaveToLobby() })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (s.closed) {
                    FinalLeaderboardView(s, vm, now, k)
                } else {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = s.viewMode == RaceViewMode.COCKPIT,
                        enter = androidx.compose.animation.fadeIn(tween(250)),
                        exit = androidx.compose.animation.fadeOut(tween(200)),
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            PaceDial(s, k)
                            Spacer(Modifier.weight(1f))
                            SpeedRing(s, now, k)
                            Spacer(Modifier.weight(1f))
                            Column {
                                LeaderboardCard(s, k, onExpand = { vm.setViewMode(RaceViewMode.LEADERBOARD) })
                                Spacer(Modifier.height((14 * k).dp))
                                SpeedControl(s, vm, s.canAdjustSpeed(now), k)
                                Spacer(Modifier.height((14 * k).dp))
                                ViewModeToggle(s.viewMode, k, onToggle = { vm.toggleViewMode() })
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = s.viewMode == RaceViewMode.LEADERBOARD,
                        enter = androidx.compose.animation.fadeIn(tween(250)),
                        exit = androidx.compose.animation.fadeOut(tween(200)),
                    ) {
                        LeaderboardView(s, vm, now, k)
                    }
                }
            }
            CompetitionTrack(s, k)
        }

        // 倒數與安全鑰匙警示疊在提示卡之上，出現時提示卡也讓位
        PositionAlerts(s, now, blocked = phase != CountdownPhase.None || s.safetyKeyDetached, audio, k)

        if (startAt != null && phase != CountdownPhase.None) CountdownOverlay(phase, startAt, now, k)

        if (s.safetyKeyDetached) {
            Box(
                Modifier.align(Alignment.Center).glass(k, Coral.copy(alpha = .6f), Color(0xCC1A0A12))
                    .padding(horizontal = (48 * k).dp, vertical = (28 * k).dp)
            ) { Glow("SAFETY KEY DETACHED", Coral, k, 40f) }
        }

        // 發令鳴槍起跑瞬間自動消除可能仍在畫面的加入提示，專注比賽
        LaunchedEffect(s.inPreRace(now)) {
            if (!s.inPreRace(now) && s.newRunnerAlert != null) {
                vm.dismissRunnerAlert()
            }
        }

        // 新選手加入比賽房間通知特效（Google Stitch 電競戰術彈卡：僅在發令前預備/倒數期間展示，起跑後鎖定）
        if (s.canAcceptNewChallenger(now)) {
            s.newRunnerAlert?.let { alert ->
                NewRunnerOverlay(
                    alert = alert,
                    now = now,
                    k = k,
                    audio = audio,
                    onDismiss = { vm.dismissRunnerAlert() },
                )
            }
        }

        // 前三名完賽通知浮層（步驟 8：凸台完賽特效）
        s.podiumAlert?.let { alert ->
            PodiumAlertOverlay(
                alert = alert,
                now = now,
                startAt = startAt,
                k = k,
                audio = audio,
                onDismiss = { vm.dismissPodiumAlert() },
            )
        }
    }
}

/* ── 新選手進入房間特效通知（Google Stitch 設計） ── */

private const val RUNNER_ALERT_DURATION_MS = 5000L

private fun countryFullName(code: String?): String = when (code?.uppercase()) {
    "KE" -> "KENYA"
    "TW" -> "TAIWAN"
    "US", "USA" -> "USA"
    "JP" -> "JAPAN"
    "GB" -> "GREAT BRITAIN"
    "DE" -> "GERMANY"
    "FR" -> "FRANCE"
    "ETH", "ET" -> "ETHIOPIA"
    else -> code?.uppercase() ?: "ATHLETE"
}

/** 削角多邊形（Chamfer Corner Cut），與 Stitch 設計中的 .clip-chamfer-corner 100% 對齊 */
class ChamferCutShape(private val cut: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width - cut, 0f)
            lineTo(size.width, cut)
            lineTo(size.width, size.height)
            lineTo(cut, size.height)
            lineTo(0f, size.height - cut)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
private fun BoxScope.NewRunnerOverlay(
    alert: RunnerJoinedAlert,
    now: Long,
    k: Float,
    audio: CountdownAudio,
    onDismiss: () -> Unit,
) {
    val evt = alert.event
    val elapsed = (now - alert.shownAtMs).coerceAtLeast(0L)
    val remainingSecs = max(0f, (RUNNER_ALERT_DURATION_MS - elapsed) / 1000f)
    val progress = (1f - elapsed.toFloat() / RUNNER_ALERT_DURATION_MS).coerceIn(0f, 1f)

    LaunchedEffect(alert.seq) {
        val laneText = evt.lane?.let { "Lane $it" } ?: ""
        audio.say("New challenger joined heat. ${evt.name}. $laneText")
    }

    LaunchedEffect(now) {
        if (elapsed >= RUNNER_ALERT_DURATION_MS) {
            onDismiss()
        }
    }

    val enterAnim = remember { Animatable(0f) }
    LaunchedEffect(alert.seq) {
        enterAnim.snapTo(0f)
        enterAnim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }

    val infinite = rememberInfiniteTransition(label = "runnerAlertFx")
    val laserProgress by infinite.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "laser",
    )
    val rotAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
        ),
        label = "rot",
    )
    val beaconPulse by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "beacon",
    )

    // 全螢幕半透明遮罩
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f * enterAnim.value))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val cutPx = 20f * k
        val cardShape = remember(cutPx) { ChamferCutShape(cutPx) }

        // 戰術電競彈出卡主體
        Box(
            Modifier.width((780 * k).dp)
                .graphicsLayer {
                    val t = enterAnim.value
                    scaleX = 0.88f + 0.12f * t
                    scaleY = 0.88f + 0.12f * t
                    alpha = t
                }
                .clickable(enabled = false) {} // 點擊卡片內部不關閉
                .clip(cardShape)
                .background(Color(0xF5081118))
                .border((1.8f * k).dp, Cyan.copy(alpha = 0.85f), cardShape)
                .drawBehind {
                    // 電競金色直角 brackets 裝飾（四個角落）
                    val bLen = 24f * k
                    val bStroke = 3.5f * k
                    // Top-Left
                    drawLine(Gold, Offset(0f, 0f), Offset(bLen, 0f), strokeWidth = bStroke)
                    drawLine(Gold, Offset(0f, 0f), Offset(0f, bLen), strokeWidth = bStroke)
                    // Top-Right
                    drawLine(Gold, Offset(size.width, 0f), Offset(size.width - bLen, 0f), strokeWidth = bStroke)
                    drawLine(Gold, Offset(size.width, 0f), Offset(size.width, bLen), strokeWidth = bStroke)
                    // Bottom-Left
                    drawLine(Gold, Offset(0f, size.height), Offset(bLen, size.height), strokeWidth = bStroke)
                    drawLine(Gold, Offset(0f, size.height), Offset(0f, size.height - bLen), strokeWidth = bStroke)
                    // Bottom-Right
                    drawLine(Gold, Offset(size.width, size.height), Offset(size.width - bLen, size.height), strokeWidth = bStroke)
                    drawLine(Gold, Offset(size.width, size.height), Offset(size.width, size.height - bLen), strokeWidth = bStroke)

                    // 雷射光線掃描效果
                    val lx = size.width * laserProgress
                    val lWidth = 140f * k
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Cyan.copy(alpha = 0.15f), Color.Transparent),
                            startX = lx - lWidth / 2f,
                            endX = lx + lWidth / 2f,
                        ),
                        size = size,
                    )
                }
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 1. 頂部狀態列
                Row(
                    Modifier.fillMaxWidth()
                        .height((50 * k).dp)
                        .background(Color(0xF0050B10))
                        .padding(horizontal = (20 * k).dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 脈衝雷達點
                        Box(Modifier.size((16 * k).dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(Cyan.copy(alpha = 0.35f), radius = (size.minDimension / 2f) * beaconPulse)
                                drawCircle(Cyan, radius = size.minDimension / 3f)
                            }
                        }
                        Spacer(Modifier.width((10 * k).dp))
                        Text(
                            text = "⚡ NEW CHALLENGER JOINED HEAT",
                            color = Cyan,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (15 * k).sp,
                            letterSpacing = (1.4 * k).sp,
                        )
                    }

                    // 房間選手席位狀態
                    val totalSlots = evt.capacity ?: 8
                    val openSlots = max(0, totalSlots - evt.fieldSize)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${evt.fieldSize} / $totalSlots ATHLETES READY",
                            color = Gold,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (13 * k).sp,
                            letterSpacing = (0.8 * k).sp,
                        )
                        Text(
                            text = "  ·  ",
                            color = Label,
                            fontFamily = Grotesk,
                            fontSize = (13 * k).sp,
                        )
                        Text(
                            text = "$openSlots SLOTS OPEN",
                            color = Coral,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (13 * k).sp,
                            letterSpacing = (0.8 * k).sp,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height((1 * k).dp).background(Cyan.copy(alpha = 0.35f)))

                // 2. 選手資料與 PR 矩陣本體
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = (24 * k).dp, vertical = (20 * k).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左側選手頭像、水道、機台編號
                    Column(
                        Modifier.width((190 * k).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size((124 * k).dp), contentAlignment = Alignment.Center) {
                            // 動態旋轉虛線光環
                            Canvas(Modifier.fillMaxSize()) {
                                val strokeW = 2.2f * k
                                val radius = size.minDimension / 2f - strokeW
                                drawCircle(
                                    color = Cyan.copy(alpha = 0.5f),
                                    radius = radius,
                                    style = Stroke(
                                        width = strokeW,
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                            floatArrayOf(12f * k, 10f * k),
                                            phase = rotAngle * 2f,
                                        ),
                                    ),
                                )
                            }

                            // 漸層光圈與深黑底
                            Box(
                                Modifier.size((106 * k).dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.linearGradient(listOf(Cyan, Gold, Cyan)))
                                    .padding((3.5f * k).dp)
                            ) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(50))
                                        .background(Carbon),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = flagEmoji(evt.country),
                                            fontSize = (26 * k).sp,
                                        )
                                        Text(
                                            text = initials(evt.name),
                                            color = Cyan,
                                            fontFamily = Grotesk,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = (20 * k).sp,
                                        )
                                    }
                                }
                            }

                            // 水道標籤
                            Box(
                                Modifier.align(Alignment.BottomCenter)
                                    .offset(y = (8 * k).dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Gold)
                                    .padding(horizontal = (12 * k).dp, vertical = (3 * k).dp)
                            ) {
                                Text(
                                    text = "LANE %02d".format(evt.lane ?: evt.fieldSize),
                                    color = Carbon,
                                    fontFamily = Grotesk,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = (12 * k).sp,
                                    letterSpacing = (1.2 * k).sp,
                                )
                            }
                        }

                        Spacer(Modifier.height((18 * k).dp))

                        // 跑步機連線標籤
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Color(0xFF131D24))
                                .border((1 * k).dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                                .padding(horizontal = (10 * k).dp, vertical = (4 * k).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size((7 * k).dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Cyan)
                            )
                            Spacer(Modifier.width((6 * k).dp))
                            Text(
                                text = evt.deviceId ?: "TREADMILL CONSOLE #%02d".format(evt.lane ?: 3),
                                color = Label,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = (10 * k).sp,
                                letterSpacing = (0.5 * k).sp,
                            )
                        }
                    }

                    Spacer(Modifier.width((24 * k).dp))

                    // 右側詳細資料與 Bento 矩陣
                    Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.clip(RoundedCornerShape((4 * k).dp))
                                    .background(Gold.copy(alpha = 0.12f))
                                    .border((1 * k).dp, Gold.copy(alpha = 0.35f), RoundedCornerShape((4 * k).dp))
                                    .padding(horizontal = (8 * k).dp, vertical = (3 * k).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${countryFullName(evt.country)} ${flagEmoji(evt.country)} · EAST AFRICA ATHLETICS",
                                    color = Gold,
                                    fontFamily = Grotesk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (11 * k).sp,
                                    letterSpacing = (0.8 * k).sp,
                                )
                            }

                            Text(
                                text = evt.tier ?: "WORLD CLASS TIER",
                                color = Cyan,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.Bold,
                                fontSize = (11 * k).sp,
                                letterSpacing = (1 * k).sp,
                            )
                        }

                        Spacer(Modifier.height((6 * k).dp))

                        Text(
                            text = evt.name.uppercase(),
                            color = Color.White,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (32 * k).sp,
                            letterSpacing = (-0.5 * k).sp,
                            maxLines = 1,
                        )

                        Text(
                            text = evt.bio ?: "Marathon World Record Holder · 5,000M Olympic Finalist",
                            color = Label,
                            fontFamily = Grotesk,
                            fontSize = (13 * k).sp,
                            maxLines = 1,
                        )

                        Spacer(Modifier.height((12 * k).dp))

                        // 3 欄 Bento 數據矩陣
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape((8 * k).dp))
                                .background(Color(0xFF060D12))
                                .border((1 * k).dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape((8 * k).dp))
                                .padding(horizontal = (12 * k).dp, vertical = (10 * k).dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Col 1: 5KM PR
                            Column(Modifier.weight(1f)) {
                                Text("5KM PR", color = Label, fontFamily = Grotesk, fontSize = (10 * k).sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height((2 * k).dp))
                                Text(evt.pr5k ?: "14:15.0", color = Gold, fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = (18 * k).sp)
                                Spacer(Modifier.height((2 * k).dp))
                                Text("PACE 02:51 /KM", color = Cyan, fontFamily = Grotesk, fontSize = (10 * k).sp, fontWeight = FontWeight.Medium)
                            }

                            Box(Modifier.width((1 * k).dp).height((40 * k).dp).background(Color.White.copy(alpha = 0.12f)))

                            // Col 2: TARGET PACE
                            Column(Modifier.weight(1.1f).padding(horizontal = (10 * k).dp)) {
                                Text("PROJECTED PACE", color = Label, fontFamily = Grotesk, fontSize = (10 * k).sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height((2 * k).dp))
                                Text(evt.targetPace ?: "02:50.4", color = Cyan, fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = (18 * k).sp)
                                Spacer(Modifier.height((2 * k).dp))
                                Text("SPLIT DELTA: -0.8s", color = Label, fontFamily = Grotesk, fontSize = (10 * k).sp, fontWeight = FontWeight.Medium)
                            }

                            Box(Modifier.width((1 * k).dp).height((40 * k).dp).background(Color.White.copy(alpha = 0.12f)))

                            // Col 3: VO2 PEAK
                            Column(Modifier.weight(0.9f).padding(start = (10 * k).dp)) {
                                Text("VO2 PEAK", color = Label, fontFamily = Grotesk, fontSize = (10 * k).sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height((2 * k).dp))
                                Text(
                                    evt.vo2Max?.let { "%.1f".format(it) } ?: "84.2",
                                    color = Coral,
                                    fontFamily = Grotesk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (18 * k).sp,
                                )
                                Spacer(Modifier.height((2 * k).dp))
                                Text("ML/KG/MIN", color = Label, fontFamily = Grotesk, fontSize = (10 * k).sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(Modifier.height((10 * k).dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "⚡ SYNCED: 21.2 KM/H MAX",
                                    color = Label,
                                    fontFamily = Grotesk,
                                    fontSize = (11 * k).sp,
                                )
                                Text(
                                    text = "  ·  ",
                                    color = Label,
                                    fontFamily = Grotesk,
                                    fontSize = (11 * k).sp,
                                )
                                Text(
                                    text = "DUAL-LINK: 0% LOSS",
                                    color = Label,
                                    fontFamily = Grotesk,
                                    fontSize = (11 * k).sp,
                                )
                            }

                            Text(
                                text = "WARM-UP COMPLETE",
                                color = Gold,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.Bold,
                                fontSize = (11 * k).sp,
                                letterSpacing = (0.5 * k).sp,
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height((1 * k).dp).background(Color.White.copy(alpha = 0.12f)))

                // 3. 底部動作列（倒數進度條 + 按鈕）
                Row(
                    Modifier.fillMaxWidth()
                        .height((56 * k).dp)
                        .background(Color(0xE0121A20))
                        .padding(horizontal = (20 * k).dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 自動倒數進度條
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "AUTO-DISMISS",
                            color = Label,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (11 * k).sp,
                            letterSpacing = (0.8 * k).sp,
                        )
                        Spacer(Modifier.width((10 * k).dp))
                        Box(
                            Modifier.width((180 * k).dp)
                                .height((6 * k).dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Box(
                                Modifier.fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Cyan.copy(alpha = 0.6f), Cyan)
                                        )
                                    )
                            )
                        }
                        Spacer(Modifier.width((10 * k).dp))
                        Text(
                            text = "%.1fs".format(remainingSecs),
                            color = Cyan,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (12 * k).sp,
                        )
                    }

                    // 動作按鈕
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((10 * k).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.clip(RoundedCornerShape((6 * k).dp))
                                .background(Color(0xFF1B242C))
                                .border((1 * k).dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape((6 * k).dp))
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = (16 * k).dp, vertical = (8 * k).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕ DISMISS",
                                color = Color.White,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = (12 * k).sp,
                                letterSpacing = (0.8 * k).sp,
                            )
                        }

                        Box(
                            Modifier.clip(RoundedCornerShape((6 * k).dp))
                                .background(Cyan)
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = (18 * k).dp, vertical = (8 * k).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "◎ TAP TO VIEW PROFILE",
                                color = Carbon,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = (12 * k).sp,
                                letterSpacing = (0.8 * k).sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ── 前三名完賽特效通知（步驟 8：Google Stitch 電競凸台設計） ── */

private const val PODIUM_ALERT_DURATION_MS = 4500L

@Composable
private fun BoxScope.PodiumAlertOverlay(
    alert: PodiumAlert,
    now: Long,
    startAt: Long?,
    k: Float,
    audio: CountdownAudio,
    onDismiss: () -> Unit,
) {
    val elapsed = (now - alert.atMs).coerceAtLeast(0L)
    val remainingSecs = max(0f, (PODIUM_ALERT_DURATION_MS - elapsed) / 1000f)
    val progress = (1f - elapsed.toFloat() / PODIUM_ALERT_DURATION_MS).coerceIn(0f, 1f)

    LaunchedEffect(alert.rank, alert.runnerId) {
        val rankName = when (alert.rank) {
            1 -> "First place"
            2 -> "Second place"
            3 -> "Third place"
            else -> "Podium position"
        }
        val speech = if (alert.isMe) {
            "Congratulations! You completed the race in $rankName! Podium finish!"
        } else when (alert.rank) {
            1 -> "First place finished! Champion is ${alert.name}!"
            2 -> "Second place finished! Silver medal goes to ${alert.name}!"
            3 -> "Third place finished! Podium complete with ${alert.name}!"
            else -> "$rankName finished! ${alert.name}!"
        }
        audio.say(speech)
    }

    LaunchedEffect(now) {
        if (elapsed >= PODIUM_ALERT_DURATION_MS) {
            onDismiss()
        }
    }

    val enterAnim = remember { Animatable(0f) }
    LaunchedEffect(alert.rank, alert.runnerId) {
        enterAnim.snapTo(0f)
        enterAnim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }

    val infinite = rememberInfiniteTransition(label = "podiumAlertFx")
    val laserProgress by infinite.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "podiumLaser",
    )

    val (accentColor, medalBadge, titleText) = when (alert.rank) {
        1 -> Triple(Gold, "🥇 GOLD MEDAL", "1ST PLACE CHAMPION")
        2 -> Triple(Color(0xFFC0D8E0), "🥈 SILVER MEDAL", "2ND PLACE FINISHER")
        3 -> Triple(Color(0xFFCD7F32), "🥉 BRONZE MEDAL", "3RD PLACE PODIUM")
        else -> Triple(Cyan, "⭐ PODIUM", "FINISHER")
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f * enterAnim.value))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        val cutPx = 20f * k
        val cardShape = remember(cutPx) { ChamferCutShape(cutPx) }

        Box(
            Modifier.width((780 * k).dp)
                .graphicsLayer {
                    val t = enterAnim.value
                    scaleX = 0.88f + 0.12f * t
                    scaleY = 0.88f + 0.12f * t
                    alpha = t
                }
                .clickable(enabled = false) {}
                .clip(cardShape)
                .background(Color(0xF50A1016))
                .border((2f * k).dp, accentColor.copy(alpha = 0.85f), cardShape)
                .drawBehind {
                    val bLen = 24f * k
                    val bStroke = 3.5f * k
                    // Top-Left
                    drawLine(accentColor, Offset(0f, 0f), Offset(bLen, 0f), strokeWidth = bStroke)
                    drawLine(accentColor, Offset(0f, 0f), Offset(0f, bLen), strokeWidth = bStroke)
                    // Top-Right
                    drawLine(accentColor, Offset(size.width, 0f), Offset(size.width - bLen, 0f), strokeWidth = bStroke)
                    drawLine(accentColor, Offset(size.width, 0f), Offset(size.width, bLen), strokeWidth = bStroke)
                    // Bottom-Left
                    drawLine(accentColor, Offset(0f, size.height), Offset(bLen, size.height), strokeWidth = bStroke)
                    drawLine(accentColor, Offset(0f, size.height), Offset(0f, size.height - bLen), strokeWidth = bStroke)
                    // Bottom-Right
                    drawLine(accentColor, Offset(size.width, size.height), Offset(size.width - bLen, size.height), strokeWidth = bStroke)
                    drawLine(accentColor, Offset(size.width, size.height), Offset(size.width, size.height - bLen), strokeWidth = bStroke)

                    val lx = size.width * laserProgress
                    val lWidth = 140f * k
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, accentColor.copy(alpha = 0.18f), Color.Transparent),
                            startX = lx,
                            endX = lx + lWidth,
                        )
                    )
                }
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 1. 頂部狀態列
                Row(
                    Modifier.fillMaxWidth()
                        .height((52 * k).dp)
                        .background(Color(0xFF0F1720))
                        .padding(horizontal = (20 * k).dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size((10 * k).dp)
                                .clip(RoundedCornerShape(50))
                                .background(accentColor)
                        )
                        Spacer(Modifier.width((10 * k).dp))
                        Text(
                            text = "PODIUM FINISH EVENT · HEAT BROADCAST",
                            color = accentColor,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (13 * k).sp,
                            letterSpacing = (1.5 * k).sp,
                        )
                    }

                    Box(
                        Modifier.clip(RoundedCornerShape((4 * k).dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .border((1 * k).dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape((4 * k).dp))
                            .padding(horizontal = (10 * k).dp, vertical = (4 * k).dp),
                    ) {
                        Text(
                            text = medalBadge,
                            color = accentColor,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = (12 * k).sp,
                            letterSpacing = (1 * k).sp,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height((1 * k).dp).background(accentColor.copy(alpha = 0.35f)))

                // 2. 完賽選手凸台卡本體
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = (24 * k).dp, vertical = (22 * k).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左側大獎章頭像
                    Column(
                        Modifier.width((170 * k).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size((110 * k).dp)
                                .clip(RoundedCornerShape(50))
                                .background(accentColor.copy(alpha = 0.18f))
                                .border((2.5f * k).dp, accentColor, RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "P${alert.rank}",
                                color = accentColor,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.Bold,
                                fontSize = (46 * k).sp,
                            )
                        }
                        Spacer(Modifier.height((10 * k).dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border((1 * k).dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                                .padding(horizontal = (12 * k).dp, vertical = (4 * k).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (alert.isMe) "YOU FINISHED" else "HEAT FINISHER",
                                color = if (alert.isMe) Cyan else Label,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.Bold,
                                fontSize = (11 * k).sp,
                                letterSpacing = (0.5 * k).sp,
                            )
                        }
                    }

                    Spacer(Modifier.width((24 * k).dp))

                    // 右側選手姓名與成績
                    Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.clip(RoundedCornerShape((4 * k).dp))
                                    .background(accentColor.copy(alpha = 0.12f))
                                    .border((1 * k).dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape((4 * k).dp))
                                    .padding(horizontal = (8 * k).dp, vertical = (3 * k).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${countryFullName(alert.country)} ${flagEmoji(alert.country)}",
                                    color = accentColor,
                                    fontFamily = Grotesk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (11 * k).sp,
                                    letterSpacing = (0.8 * k).sp,
                                )
                            }

                            Text(
                                text = titleText,
                                color = accentColor,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.Bold,
                                fontSize = (12 * k).sp,
                                letterSpacing = (1 * k).sp,
                            )
                        }

                        Spacer(Modifier.height((6 * k).dp))

                        Text(
                            text = alert.name.uppercase(),
                            color = Color.White,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (32 * k).sp,
                            letterSpacing = (-0.5 * k).sp,
                            maxLines = 1,
                        )

                        Spacer(Modifier.height((12 * k).dp))

                        // 成績卡片
                        val elapsedMs = if (startAt != null) max(0L, alert.finishTimeMs - startAt) else alert.finishTimeMs
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape((8 * k).dp))
                                .background(Color(0xFF060D12))
                                .border((1 * k).dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape((8 * k).dp))
                                .padding(horizontal = (16 * k).dp, vertical = (12 * k).dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("OFFICIAL FINISH TIME", color = Label, fontFamily = Grotesk, fontSize = (11 * k).sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height((2 * k).dp))
                                Glow(fmtClock(elapsedMs), accentColor, k, 24f, glow = true)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("RESULT STATUS", color = Label, fontFamily = Grotesk, fontSize = (11 * k).sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height((2 * k).dp))
                                Text("OFFICIAL FINISH", color = Color.White, fontFamily = Grotesk, fontSize = (15 * k).sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height((1 * k).dp).background(Color.White.copy(alpha = 0.12f)))

                // 3. 底部動作列
                Row(
                    Modifier.fillMaxWidth()
                        .height((56 * k).dp)
                        .background(Color(0xE0121A20))
                        .padding(horizontal = (20 * k).dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "AUTO-DISMISS",
                            color = Label,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (11 * k).sp,
                            letterSpacing = (0.8 * k).sp,
                        )
                        Spacer(Modifier.width((10 * k).dp))
                        Box(
                            Modifier.width((180 * k).dp)
                                .height((6 * k).dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Box(
                                Modifier.fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .clip(RoundedCornerShape(50))
                                    .background(accentColor)
                            )
                        }
                        Spacer(Modifier.width((10 * k).dp))
                        Text(
                            text = "%.1fs".format(remainingSecs),
                            color = accentColor,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (12 * k).sp,
                        )
                    }

                    Box(
                        Modifier.clip(RoundedCornerShape((6 * k).dp))
                            .background(accentColor)
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = (20 * k).dp, vertical = (9 * k).dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "CONTINUE RACE",
                            color = Carbon,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = (12 * k).sp,
                            letterSpacing = (0.8 * k).sp,
                        )
                    }
                }
            }
        }
    }
}

/* ── 名次提示 ── */

/** 提示卡／晶片要畫的內容。 */
private class AlertLook(val tag: String, val title: String, val chip: String, val sub: String, val color: Color, val image: Int)

private fun fmtGap(m: Double?, ms: Long?): String =
    "${m?.let { abs(it).roundToInt().toString() } ?: "--"} m · ${ms?.let { "%.1f".format(it / 1000.0) } ?: "--"} s"

/** 狀態卡的文字用即時榜單數字；事件卡用觸發當下的名次。 */
private fun alertLook(state: TensionState?, popup: TensionPopup?, st: Standing?): AlertLook? {
    val event = popup?.event
    val from = popup?.fromRank
    val to = popup?.toRank
    val moved = if (from != null && to != null) abs(from - to) else 1
    val positions = if (moved == 1) "POSITION" else "POSITIONS"
    return when {
        event == TensionEvent.OVERTAKE -> AlertLook(
            "OVERTAKE", "+$moved $positions", "+$moved $positions", "P$from → P$to", Cyan, R.drawable.pict_overtake,
        )
        event == TensionEvent.OVERTAKEN -> AlertLook(
            "POSITION LOST", "−$moved $positions", "−$moved $positions", "P$from → P$to", Coral, R.drawable.pict_closing,
        )
        event == TensionEvent.BECAME_LEADER || state == TensionState.LEADING -> AlertLook(
            "YOU ARE LEADING", "LEADING", "LEADING",
            "+${st?.behindGapM?.let { abs(it).roundToInt() } ?: "--"} m over P${st?.behindRank ?: 2}", Gold, R.drawable.pict_leading,
        )
        state == TensionState.CLOSING_IN -> AlertLook(
            "WATCH OUT", "P${st?.behindRank ?: "-"} CLOSING IN", "P${st?.behindRank ?: "-"} CLOSING IN",
            "${fmtGap(st?.behindGapM, st?.behindGapMs)} behind you", Coral, R.drawable.pict_closing,
        )
        state == TensionState.CATCHING -> AlertLook(
            "ALMOST THERE", "CATCHING P${st?.aheadRank ?: "-"}", "CATCHING P${st?.aheadRank ?: "-"}",
            "${fmtGap(st?.aheadGapM, st?.aheadGapMs)} ahead", Cyan, R.drawable.pict_catching,
        )
        else -> null
    }
}

/** 彈卡時念的話；被超越不出聲。 */
private fun alertWord(p: TensionPopup): String? = when (p.event) {
    TensionEvent.OVERTAKE -> "Overtake!"
    TensionEvent.BECAME_LEADER -> "You're in the lead!"
    TensionEvent.OVERTAKEN -> null
    null -> when (p.state) {
        TensionState.CLOSING_IN -> "Watch out!"
        TensionState.CATCHING -> "Go get them!"
        else -> null
    }
}

private const val ALERT_IN_MS = 320
private const val ALERT_HOLD_MS = 2200L
private const val ALERT_OUT_MS = 380

/**
 * 名次提示：進入狀態或發生事件時（已過冷卻）在速度環上方彈出中央卡，停約 2.2 秒後收起，
 * 之後以速度環下方的小晶片持續顯示目前狀態，回到 NONE 時淡出。
 * 全部不攔截觸控；倒數與安全鑰匙警示出現時讓位。
 */
@Composable
private fun BoxScope.PositionAlerts(s: RaceUiState, now: Long, blocked: Boolean, audio: CountdownAudio, k: Float) {
    val visible = s.canAdjustSpeed(now) && !blocked
    val popup = s.tension.popup
    val card = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<TensionPopup?>(null) }
    var handledSeq by remember { mutableLongStateOf(0L) }
    LaunchedEffect(popup?.seq) {
        val p = popup ?: return@LaunchedEffect
        handledSeq = p.seq
        // 重新組合（例如回到 HUD）時不重播舊卡
        if (System.currentTimeMillis() + s.clockOffsetMs - p.atMs > 2_000L && shown == null) return@LaunchedEffect
        shown = p
        alertWord(p)?.let { audio.say(it) }
        // 從目前的透明度接著放大淡入：前一張還在畫面上時不會先閃掉再出現
        card.animateTo(1f, tween(ALERT_IN_MS, easing = FastOutSlowInEasing))
        delay(ALERT_HOLD_MS)
        card.animateTo(0f, tween(ALERT_OUT_MS, easing = FastOutSlowInEasing))
        shown = null
    }

    val cardPopup = shown
    val cardLook = cardPopup?.let { alertLook(it.state, it, s.standing) }
    if (visible && cardLook != null) {
        Box(
            Modifier.align(Alignment.Center).graphicsLayer {
                val t = card.value
                val sc = .82f + .18f * t
                scaleX = sc; scaleY = sc; alpha = t
            }
        ) { AlertCard(cardLook, k) }
    }

    // 中央卡收起後才顯示晶片，兩者不同時出現
    val state = s.tension.state
    val lastChip = remember { arrayOf<AlertLook?>(null) }
    alertLook(state, null, s.standing)?.let { lastChip[0] = it }
    // 新卡即將彈出（LaunchedEffect 下一幀才接手）時也先收起晶片，免得兩者同時出現
    val cardPending = cardPopup != null || (popup != null && popup.seq != handledSeq)
    val chipOn = visible && state != TensionState.NONE && !cardPending
    val chipAlpha by animateFloatAsState(if (chipOn) 1f else 0f, tween(300), label = "chip")
    val chipLook = lastChip[0]
    if (chipAlpha > 0.01f && chipLook != null && !cardPending) {
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = (172 * k).dp)
                .graphicsLayer { alpha = chipAlpha }
        ) { AlertChip(chipLook, k) }
    }
}

@Composable
private fun AlertCard(look: AlertLook, k: Float) = Column(
    // 寬度留到右側榜單卡之前（1280 基準下榜單從 x≈905 起）
    Modifier.width((520 * k).dp).height((360 * k).dp)
        // 卡片蓋在速度環的大字上：多墊一層深底，否則底下的數字會透出來干擾標題
        .clip(RoundedCornerShape((26 * k).dp)).background(Carbon.copy(alpha = .85f))
        .glass(k, look.color.copy(alpha = .55f), radius = 26f)
        // 柔和的內光暈：由邊緣往內、首尾相接的同色細環，透明度平滑遞減（不重疊才不會出現條紋）
        .drawBehind {
            val r = 26f * k
            val band = 3f * k
            val steps = 14
            for (i in 0 until steps) {
                val inset = band / 2f + i * band
                val f = 1f - i / steps.toFloat()
                drawRoundRect(
                    look.color.copy(alpha = .10f * f * f),
                    Offset(inset, inset), Size(size.width - inset * 2f, size.height - inset * 2f),
                    CornerRadius(max(0f, r - inset)), style = Stroke(band),
                )
            }
        }
        .padding(horizontal = (28 * k).dp, vertical = (20 * k).dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) {
    SoftLabel(look.tag, Label, k, 14f)
    Spacer(Modifier.height((6 * k).dp))
    Box(
        Modifier.height((180 * k).dp).fillMaxWidth()
            .drawBehind { ambient(look.color.copy(alpha = .22f), center, size.minDimension * .75f) },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(look.image), contentDescription = null, modifier = Modifier.fillMaxHeight())
    }
    Spacer(Modifier.height((6 * k).dp))
    Glow(look.title, look.color, k, 44f)
    Spacer(Modifier.height((4 * k).dp))
    Text(
        look.sub, maxLines = 1,
        style = TextStyle(
            color = Color(0xFFDCE8EC), fontFamily = Grotesk, fontWeight = FontWeight.Medium,
            fontSize = (20 * k).sp, fontFeatureSettings = "tnum",
        ),
    )
}

@Composable
private fun AlertChip(look: AlertLook, k: Float) = Row(
    Modifier.glass(k, look.color.copy(alpha = .5f), radius = 50f)
        .padding(start = (10 * k).dp, end = (20 * k).dp, top = (5 * k).dp, bottom = (5 * k).dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Image(painterResource(look.image), contentDescription = null, modifier = Modifier.height((34 * k).dp))
    Spacer(Modifier.width((10 * k).dp))
    Glow(look.chip, look.color, k, 20f)
}

/* ── 起跑倒數 ── */

/**
 * 全螢幕倒數：暗幕上巨大的金色秒數（每秒放大淡入）、逐秒流失的細光環，槍響後青色 GO! 放大淡出。
 * 倒數中吞掉觸控（此時速度鈕本來就鎖著）；GO! 階段不攔截，消失後更不會擋到任何東西。
 */
@Composable
private fun CountdownOverlay(phase: CountdownPhase, startAt: Long, now: Long, k: Float) {
    when (phase) {
        is CountdownPhase.Counting -> {
            val secs = phase.secondsLeft
            val pop = remember { Animatable(0f) }
            val ring = remember { Animatable(1f) }
            LaunchedEffect(secs) {
                // 這一秒還剩多少：一般是整秒，中途加入時是零頭
                val left = (startAt - now - (secs - 1) * 1000L).coerceIn(0L, 1000L)
                ring.snapTo(left / 1000f)
                launch { ring.animateTo(0f, tween(left.toInt(), easing = LinearEasing)) }
                pop.snapTo(0f)
                pop.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
            }
            Box(
                Modifier.fillMaxSize().background(Carbon.copy(alpha = .7f))
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size((440 * k).dp).drawBehind {
                            ambient(Gold.copy(alpha = .12f), center, size.minDimension * .5f)
                            val r = size.minDimension / 2f - 24f * k
                            drawCircle(Color.White.copy(alpha = .07f), r, style = Stroke(3f * k))
                            glowArc(Gold, -90f, 360f * ring.value, r, 5f * k)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        val t = pop.value
                        Box(Modifier.graphicsLayer {
                            val sc = 1.4f - .4f * t
                            // 逐筆調整透明度、不開離屏圖層：否則大字的柔光會被圖層邊界切成方塊
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            scaleX = sc; scaleY = sc; alpha = t
                        }) { Glow("$secs", Gold, k, 300f) }
                    }
                    Spacer(Modifier.height((8 * k).dp))
                    SoftLabel("GET SET", Label, k, 30f)
                }
            }
        }
        CountdownPhase.Go -> {
            val out = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                // 中途加入時從目前進度接著播，讓 GO! 仍在槍響後 1.2 秒消失
                val p0 = ((now - startAt).toFloat() / GO_PHASE_MS).coerceIn(0f, 1f)
                out.snapTo(p0)
                out.animateTo(1f, tween(((1f - p0) * GO_PHASE_MS).toInt(), easing = LinearEasing))
            }
            val t = out.value
            Box(
                Modifier.fillMaxSize().background(Carbon.copy(alpha = .7f * (1f - t))),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.graphicsLayer {
                    val sc = 1f + .6f * t
                    // 逐筆調整透明度、不開離屏圖層：否則大字的柔光會被圖層邊界切成方塊
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            scaleX = sc; scaleY = sc; alpha = 1f - t
                }) { Glow("GO!", Cyan, k, 300f) }
            }
        }
        CountdownPhase.None -> Unit
    }
}

/**
 * 倒數音效：每進入新的一秒以人聲念秒數（Five…One），槍響喊「Go!」。
 * 只在階段改變時觸發，50ms 的時間刷新或重組都不會重播。Hud 只在 RACE 畫面組合，離開即釋放。
 */
@Composable
private fun CountdownSounds(phase: CountdownPhase, audio: CountdownAudio) {
    val prev = remember { arrayOf<CountdownPhase>(CountdownPhase.None) }
    LaunchedEffect(phase) {
        countdownBeep(prev[0], phase)?.let { audio.cue(it, countdownWord(phase)) }
        prev[0] = phase
    }
}

/**
 * 起跑人聲（平台 TextToSpeech）加嗶聲備援（平台 ToneGenerator），都不需素材或依賴。
 * 人聲與嗶聲不同時播，免得糊在一起。只要 TTS 不能用就改嗶，選手一定聽得到起跑：
 * 尚未初始化完成、沒有引擎、語言缺資料、speak() 失敗，或引擎事後回報 onError（之後整場改用嗶聲）。
 * 所有呼叫包在 runCatching：音效失敗不可影響比賽。
 */
private class CountdownAudio(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    // ponytail: 部分機型建構 ToneGenerator 會丟例外，拿不到就安靜
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
    /** 只有 onInit 成功且語言可用後才為 true；在那之前的秒數一律以嗶聲代替 */
    @Volatile private var voiceReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = runCatching { TextToSpeech(context) { status -> main.post { onInit(status) } } }
            .onFailure { Log.w(TAG, "TTS construct failed", it) }.getOrNull()
    }

    private fun onInit(status: Int) {
        runCatching {
            val t = tts ?: return
            Log.i(TAG, "TTS onInit status=$status (SUCCESS=${TextToSpeech.SUCCESS}) defaultEngine=${t.defaultEngine} engines=${t.engines.map { it.name }}")
            if (status != TextToSpeech.SUCCESS) return
            var lang = t.setLanguage(Locale.US)
            Log.i(TAG, "setLanguage(US)=$lang (MISSING_DATA=${TextToSpeech.LANG_MISSING_DATA} NOT_SUPPORTED=${TextToSpeech.LANG_NOT_SUPPORTED})")
            if (lang < TextToSpeech.LANG_AVAILABLE) {
                lang = t.setLanguage(Locale.getDefault())
                Log.i(TAG, "setLanguage(${Locale.getDefault()})=$lang")
            }
            t.setSpeechRate(1.1f)
            t.setPitch(1.15f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { Log.i(TAG, "utterance onStart $id") }
                override fun onDone(id: String) { Log.i(TAG, "utterance onDone $id") }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) = onError(id, -1)
                override fun onError(id: String, errorCode: Int) {
                    Log.w(TAG, "utterance onError $id code=$errorCode")
                    if (id == WARMUP_ID || id.startsWith(ALERT_ID)) return
                    // 人聲沒出來：這一聲補嗶，之後整場改用嗶聲
                    main.post {
                        voiceReady = false
                        beep(if (id.startsWith("LONG")) CountdownBeep.LONG else CountdownBeep.SHORT)
                    }
                }
            })
            voiceReady = lang >= TextToSpeech.LANG_AVAILABLE
            Log.i(TAG, "TTS ready=$voiceReady voice=${t.voice?.name}")
            // 暖機：第一句合成要約 700ms，會被下一秒的 QUEUE_FLUSH 截斷；先靜音念一次，之後約 30ms 出聲
            if (voiceReady) {
                val r = t.speak("Go", TextToSpeech.QUEUE_FLUSH, Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f)
                }, WARMUP_ID)
                Log.i(TAG, "warmup speak=$r")
            }
        }.onFailure { Log.w(TAG, "TTS setup failed", it) }
    }

    fun cue(kind: CountdownBeep, word: String) {
        if (voiceReady && speak(kind, word)) return
        Log.i(TAG, "cue '$word' -> beep $kind (voiceReady=$voiceReady)")
        beep(kind)
    }

    /** 名次提示的人聲：只在 TTS 可用時念，不用嗶聲代替（比賽中的嗶聲會跟倒數混淆）。 */
    fun say(word: String) {
        if (voiceReady) speak(null, word)
    }

    private fun speak(kind: CountdownBeep?, word: String): Boolean = runCatching {
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        // QUEUE_FLUSH：念得慢也不會拖到下一秒
        val r = tts?.speak(word, TextToSpeech.QUEUE_FLUSH, params, "${kind?.name ?: ALERT_ID}-$word")
        Log.i(TAG, "speak('$word')=$r")
        r == TextToSpeech.SUCCESS
    }.getOrDefault(false)

    private fun beep(kind: CountdownBeep) {
        runCatching {
            when (kind) {
                CountdownBeep.SHORT -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                CountdownBeep.LONG -> tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
            }
        }
    }

    fun release() {
        voiceReady = false
        main.removeCallbacksAndMessages(null)
        runCatching { tts?.shutdown() }
        runCatching { tone?.release() }
        tts = null
    }

    private companion object {
        const val TAG = "FitRaceGo"
        const val WARMUP_ID = "warmup"
        const val ALERT_ID = "ALERT"
    }
}

/* ── 視角切換膠囊（儀表板右下角 / 排行榜頂列） ── */

@Composable
private fun ViewModeToggle(
    current: RaceViewMode,
    k: Float,
    modifier: Modifier = Modifier.width((340 * k).dp),
    onToggle: () -> Unit,
) {
    Row(
        modifier
            .height((48 * k).dp)
            .glass(k, Color.White.copy(alpha = .18f), Color.Black.copy(alpha = .45f), radius = 50f)
            .padding((4 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTab(
            label = "COCKPIT",
            active = current == RaceViewMode.COCKPIT,
            k = k,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = { if (current != RaceViewMode.COCKPIT) onToggle() },
        )
        Spacer(Modifier.width((4 * k).dp))
        ModeTab(
            label = "LEADERBOARD",
            active = current == RaceViewMode.LEADERBOARD,
            k = k,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onClick = { if (current != RaceViewMode.LEADERBOARD) onToggle() },
        )
    }
}

@Composable
private fun ModeTab(
    label: String,
    active: Boolean,
    k: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (active) Cyan else Color.Transparent
    val textC = if (active) Carbon else Label
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textC,
            fontFamily = Grotesk,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = (13 * k).sp,
            letterSpacing = (1.2 * k).sp,
        )
    }
}

/* ── 頂列（乾淨無多餘切換鈕） ── */

@Composable
private fun TopBar(
    s: RaceUiState, now: Long, startAt: Long?, phase: CountdownPhase, k: Float,
    onLeave: () -> Unit,
) = Box(Modifier.fillMaxWidth().height((84 * k).dp)) {
    Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
        FitRaceLogo(k)
        Box(
            Modifier.padding(horizontal = (16 * k).dp).width((1 * k).dp).height((22 * k).dp)
                .background(Color.White.copy(alpha = .18f))
        )
        SoftLabel(
            "ROOM ${s.roomId.replace('_', ' ')}  ·  %,dm".format(s.raceDistanceM.roundToInt()),
            Color.White.copy(alpha = .8f), k, 18f,
        )
    }

    // 中央梯形頁籤：狀態 + 比賽計時
    Row(
        Modifier.align(Alignment.TopCenter).height((72 * k).dp)
            .drawBehind {
                val slant = 36f * k
                val tab = Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f)
                    lineTo(size.width - slant, size.height); lineTo(slant, size.height); close()
                }
                drawPath(tab, Brush.verticalGradient(listOf(Color.White.copy(alpha = .03f), Color.White.copy(alpha = .08f))))
                drawPath(tab, Color.White.copy(alpha = .14f), style = Stroke(1.2f * k))
            }
            .padding(horizontal = (48 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val counting = phase is CountdownPhase.Counting
        SoftLabel(
            when {
                s.closed -> "OFFICIAL RESULTS"
                s.dnf -> "DNF"
                startAt == null -> "STANDBY"
                counting -> "GET SET"
                s.finishTimeMs != null -> "FINISHED"
                else -> "RUNNING RACE"
            }, Label, k, 18f,
        )
        Spacer(Modifier.width((14 * k).dp))
        Glow(
            when {
                s.closed -> "RACE CLOSED"
                s.dnf -> "%,dm".format(s.distance.roundToInt())
                startAt == null -> "--:--"
                phase is CountdownPhase.Counting -> "T-${phase.secondsLeft}"
                s.finishTimeMs != null -> fmtClock(s.finishTimeMs - startAt)
                else -> fmtClock(now - startAt)
            },
            if (s.dnf) Coral else if (s.closed) Gold else if (counting || s.finishTimeMs != null) Gold else Color.White,
            k, 34f, glow = counting || s.finishTimeMs != null || s.closed,
        )
    }

    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
        // 截止倒數：若進入第 1 名完賽後的最後 100 秒衝刺階段，則以動態珊瑚紅徽章閃爍警示（步驟 9）
        if (startAt != null && s.finishTimeMs == null && !s.closed && s.cutoffAtServerTime != null) {
            val isFinalSprint = s.isFinalSprintCutoff(now)
            val remainingMs = max(0L, s.cutoffAtServerTime - now)
            val timeText = fmtClock(remainingMs).substringBefore('.')
            if (isFinalSprint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Coral.copy(alpha = 0.2f))
                        .border((1.2f * k).dp, Coral, RoundedCornerShape(50))
                        .padding(horizontal = (12 * k).dp, vertical = (4 * k).dp),
                ) {
                    Glow("⚡ FINAL SPRINT $timeText", Coral, k, 15f, glow = true)
                }
            } else {
                SoftLabel("CUTOFF $timeText", Label, k, 15f)
            }
            Spacer(Modifier.width((20 * k).dp))
        }
        StatusDot("BELT", s.treadmillConnected, k)
        Spacer(Modifier.width((14 * k).dp))
        StatusDot(if (s.serverConnected) "${s.rttMs}ms" else "OFFLINE", s.serverConnected, k)
        Spacer(Modifier.width((24 * k).dp))
        SoftLabel("INCLINE", Label, k, 18f)
        Spacer(Modifier.width((8 * k).dp))
        Glow("%.1f%%".format(s.incline), Color.White, k, 26f, glow = false)
        if (s.canLeave) {
            Spacer(Modifier.width((20 * k).dp))
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .border((1 * k).dp, Color.White.copy(alpha = .25f), RoundedCornerShape(50))
                    .clickable(onClick = onLeave)
                    .padding(horizontal = (18 * k).dp, vertical = (9 * k).dp),
            ) { SoftLabel("‹ LOBBY", Color.White, k, 15f) }
        }
    }

    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((1 * k).dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .14f), Color.Transparent))
        )
    )
}

@Composable
private fun StatusDot(label: String, ok: Boolean, k: Float) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        val c = if (ok) Cyan else Coral
        Box(
            Modifier.size((14 * k).dp).drawBehind {
                drawCircle(c.copy(alpha = .25f), radius = size.minDimension / 2f)
                drawCircle(c, radius = size.minDimension / 4f)
            }
        )
        Spacer(Modifier.width((6 * k).dp))
        SoftLabel(label, if (ok) Label else Coral, k, 13f)
    }

/* ── 左：配速圓盤 ── */

@Composable
private fun PaceDial(s: RaceUiState, k: Float) =
    Box(Modifier.size((330 * k).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 30f * k
            drawCircle(Carbon.copy(alpha = .78f), radius = r) // 同玻璃卡片的深底
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .07f), Color.White.copy(alpha = .02f)),
                    center = center, radius = r,
                ),
                radius = r,
            )
            drawCircle(Color.White.copy(alpha = .10f), radius = r, style = Stroke(1.2f * k))
            glowArc(Cyan, 115f, 130f, size.minDimension / 2f - 12f * k, 9f * k)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SoftLabel("PACE", Label, k, 20f)
            Glow(fmtPace(s.pace), Cyan, k, 86f)
            SoftLabel("/km", Label, k, 22f)
        }
    }

/* ── 中：速度環 ── */

private const val RING_START = 135f
private const val RING_SWEEP = 270f

@Composable
private fun SpeedRing(s: RaceUiState, now: Long, k: Float) {
    val frac = (s.speedKmh / SPEED_MAX_KMH).coerceIn(0f, 1f)
    Box(Modifier.size((500 * k).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 46f * k
            val w = 14f * k
            val lit = RING_SWEEP * frac
            // 已達速度為青色，剩餘量程為珊瑚紅，兩段中間留一道縫
            glowArc(Cyan, RING_START, lit, r, w)
            glowArc(Coral, RING_START + lit + 5f, RING_SWEEP - lit - 5f, r, w, strength = .8f)
            // 外圈裝飾細弧
            glowArc(Coral, -55f, 105f, size.minDimension / 2f - 12f * k, 4f * k, strength = .45f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SoftLabel("SPEED", Label, k, 20f)
            Row(verticalAlignment = Alignment.Bottom) {
                Glow("%.1f".format(s.speedKmh), Color.White, k, 44f, glow = false)
                SoftLabel(" km/h", Label, k, 20f, Modifier.padding(bottom = (8 * k).dp))
            }
            val (big, caption, color) = when {
                s.dnf -> Triple("%,dm".format(s.distance.roundToInt()), "DID NOT FINISH", Coral)
                // 環內空間有限，只顯示到秒；精確成績在頂列
                s.finishTimeMs != null -> Triple(etaText(s, now).substringBefore('.'), "FINISH TIME", Gold)
                else -> Triple(etaText(s, now).substringBefore('.'), "EST. FINISH", Cyan)
            }
            Glow(big, color, k, 96f)
            SoftLabel(caption, Label, k, 15f)
            Spacer(Modifier.height((10 * k).dp))
            CadenceGauge(s, k)
        }
    }
}

@Composable
private fun CadenceGauge(s: RaceUiState, k: Float) =
    Box(Modifier.width((210 * k).dp).height((96 * k).dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height - 6f * k
            val tl = Offset(size.width / 2f - r, size.height - r)
            val sz = Size(r * 2f, r * 2f)
            val w = 8f * k
            drawArc(Color.White.copy(alpha = .08f), 200f, 140f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            val sweep = 140f * (s.cadence / CADENCE_MAX_SPM).coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(Amber.copy(alpha = .2f), 200f, sweep, false, tl, sz, style = Stroke(w * 2.6f, cap = StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(listOf(Coral, Amber, Gold), center = Offset(size.width / 2f, size.height)),
                    200f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Glow("${s.cadence}", Color.White, k, 42f, glow = false)
            SoftLabel("SPM", Label, k, 14f)
        }
    }

/* ── 右：迷你即時榜 ── */

@Composable
private fun LeaderboardCard(s: RaceUiState, k: Float, onExpand: () -> Unit = {}) = Column(
    Modifier.width((340 * k).dp).glass(k).padding((24 * k).dp),
) {
    val leading = s.rank == 1
    // 兩位數名次（P12 / 12）時縮字，否則右側差距欄會被擠到截斷
    val twoDigits = s.fieldSize >= 10
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftLabel("LIVE LEADERBOARD", Color.White.copy(alpha = .9f), k, 17f)
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(Cyan.copy(alpha = .12f))
                .border((1 * k).dp, Cyan.copy(alpha = .45f), RoundedCornerShape(50))
                .clickable(onClick = onExpand)
                .padding(horizontal = (10 * k).dp, vertical = (4 * k).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EXPAND ›",
                color = Cyan,
                fontFamily = Grotesk,
                fontWeight = FontWeight.Bold,
                fontSize = (12 * k).sp,
                letterSpacing = (1 * k).sp,
            )
        }
    }
    GlassDivider(k)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 名次接總人數（P2 / 6）：總人數 = 本場報名人數，含尚未起跑者
        Row(verticalAlignment = Alignment.Bottom) {
            Glow(s.rank?.let { "P$it" } ?: "--", if (leading) Gold else Color.White, k, if (twoDigits) 52f else 64f, glow = leading)
            if (s.fieldSize > 0) {
                SoftLabel(
                    "/ ${s.fieldSize}", Label, k, if (twoDigits) 22f else 26f,
                    Modifier.padding(start = (6 * k).dp, bottom = ((if (twoDigits) 10 else 12) * k).dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            SoftLabel(if (leading) "LEAD OVER P2" else "GAP TO LEADER", Label, k, 15f)
            val gap = if (leading) fmtMetres(s.gapToNeighbourM?.let { abs(it) }) else fmtMetres(s.gapToLeaderM)
            Glow(gap, if (leading) Gold else Coral, k, gapSize(gap))
            SoftLabel(
                when {
                    leading -> "LEADING"
                    s.closingOnLeader == true -> "▲ CLOSING"
                    s.closingOnLeader == false -> "▼ OPENING"
                    else -> " "
                }, Label, k, 12f,
            )
        }
    }
    GlassDivider(k)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SoftLabel(if (leading) "NEAREST CHASER" else "GAP TO RUNNER AHEAD", Label, k, 15f)
        fmtMetres(s.gapToNeighbourM).let { Glow(it, Cyan, k, gapSize(it)) }
    }
}

/** 差距到四位數（+1250m）時縮字，避免與左側的「P11 / 11」重疊。 */
private fun gapSize(text: String) = if (text.length > 5) 36f else 44f

@Composable
private fun GlassDivider(k: Float) = Box(
    Modifier.padding(vertical = (14 * k).dp).fillMaxWidth().height((1 * k).dp)
        .background(Color.White.copy(alpha = .10f))
)

@Composable
private fun SpeedControl(s: RaceUiState, vm: RaceViewModel, enabled: Boolean, k: Float) = Row(
    Modifier.width((340 * k).dp).glass(k, radius = 50f).padding((8 * k).dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    RoundButton("−", enabled, k) { vm.nudgeSpeed(-0.5f) }
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        SoftLabel(
            when {
                enabled -> "TARGET SPEED"
                s.startAtServerTime == null -> "UNLOCKS AT START"
                s.finishTimeMs == null && !s.closed -> "GET SET"
                else -> "RACE OVER"
            }, Label, k, 12f,
        )
        Glow(
            "%.1f km/h".format(s.targetSpeed), Color.White.copy(alpha = if (enabled) 1f else .45f),
            k, 22f, glow = false,
        )
    }
    RoundButton("+", enabled, k) { vm.nudgeSpeed(0.5f) }
}

@Composable
private fun RoundButton(label: String, enabled: Boolean, k: Float, onClick: () -> Unit) = Box(
    Modifier.size((52 * k).dp).clip(RoundedCornerShape(50))
        .background(if (enabled) Cyan.copy(alpha = .10f) else Color.White.copy(alpha = .04f))
        .border((1 * k).dp, if (enabled) Cyan.copy(alpha = .5f) else Color.White.copy(alpha = .12f), RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
) { Glow(label, if (enabled) Cyan else Label.copy(alpha = .4f), k, 28f, glow = enabled) }

/* ── 底部賽道 ── */

@Composable
private fun CompetitionTrack(s: RaceUiState, k: Float) {
    val pct = (s.distance / s.raceDistanceM).coerceIn(0.0, 1.0).toFloat()
    val leaderPct = s.leaderDistanceM?.let { (it / s.raceDistanceM).coerceIn(0.0, 1.0).toFloat() }
    val fill = if (s.finishTimeMs != null) Gold else Cyan
    Column(Modifier.fillMaxWidth().padding(horizontal = (110 * k).dp)) {
        SoftLabel("%,dm COMPETITION TRACK".format(s.raceDistanceM.roundToInt()), Color.White.copy(alpha = .8f), k, 18f)
        Spacer(Modifier.height((6 * k).dp))
        Canvas(Modifier.fillMaxWidth().height((48 * k).dp)) {
            val barH = 28f * k
            val top = size.height - barH
            val pill = CornerRadius(barH / 2f)
            drawRoundRect(Color.White.copy(alpha = .05f), Offset(0f, top), Size(size.width, barH), pill)
            drawRoundRect(Color.White.copy(alpha = .28f), Offset(0f, top), Size(size.width, barH), pill, style = Stroke(1.5f * k))

            val inset = 6f * k
            val innerH = barH - inset * 2f
            val innerW = size.width - inset * 2f
            val x = inset + innerW * pct
            if (pct > 0f) {
                // 兩層加寬的半透明底模擬光暈
                listOf(10f * k to .07f, 5f * k to .16f).forEach { (g, a) ->
                    drawRoundRect(
                        fill.copy(alpha = a), Offset(inset - g, top + inset - g),
                        Size(innerW * pct + g * 2f, innerH + g * 2f), CornerRadius(innerH / 2f + g),
                    )
                }
                drawRoundRect(
                    Brush.horizontalGradient(listOf(fill.copy(alpha = .7f), fill), startX = inset, endX = x),
                    Offset(inset, top + inset), Size(innerW * pct, innerH), CornerRadius(innerH / 2f),
                )
            }
            leaderPct?.takeIf { it > pct + .002f }?.let { lp ->
                val lx = inset + innerW * lp
                drawCircle(Gold.copy(alpha = .3f), radius = 9f * k, center = Offset(lx, top + barH / 2f))
                drawCircle(Gold, radius = 5f * k, center = Offset(lx, top + barH / 2f))
            }
            // 自己的位置：賽道上方的白色倒三角
            val t = 10f * k
            drawPath(
                Path().apply {
                    moveTo(x - t, top - 16f * k); lineTo(x + t, top - 16f * k); lineTo(x, top - 4f * k); close()
                },
                Color.White,
            )
        }
        Spacer(Modifier.height((10 * k).dp))
        Text(
            "%,dm / %,dm (%d%%)".format(s.distance.roundToInt(), s.raceDistanceM.roundToInt(), (pct * 100).roundToInt()),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = TextStyle(
                color = Color.White, fontFamily = Grotesk, fontWeight = FontWeight.Medium,
                fontSize = (22 * k).sp, fontFeatureSettings = "tnum",
            ),
        )
    }
}

/* ── 完整排行榜視圖 ── */

@Composable
private fun LeaderboardView(
    s: RaceUiState,
    vm: RaceViewModel,
    now: Long,
    k: Float,
) {
    val entries = if (s.leaderboard.isNotEmpty()) s.leaderboard else sampleLeaderboard(s.profile.runnerId, s.distance, s.raceDistanceM)
    Column(
        Modifier.fillMaxSize()
            .glass(k, Color.White.copy(alpha = .12f), Color.Black.copy(alpha = .38f))
            .padding((20 * k).dp)
    ) {
        // 頂部列：標題 + 選手總數 + 速度微調按鈕
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glow("LIVE LEADERBOARD", Cyan, k, 26f, glow = false)
                    Spacer(Modifier.width((12 * k).dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(Gold.copy(alpha = .15f))
                            .border((1 * k).dp, Gold.copy(alpha = .5f), RoundedCornerShape(50))
                            .padding(horizontal = (10 * k).dp, vertical = (3 * k).dp)
                    ) {
                        Text(
                            "${entries.size} ATHLETES",
                            color = Gold,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (12 * k).sp,
                            letterSpacing = (1 * k).sp,
                        )
                    }
                }
                Spacer(Modifier.height((4 * k).dp))
                SoftLabel(
                    "DYNAMIC STANDINGS · TAP ROW OR TOGGLE VIEW TO RETURN",
                    Label, k, 13f,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedControl(s, vm, s.canAdjustSpeed(now), k)
                Spacer(Modifier.width((16 * k).dp))
                ViewModeToggle(
                    current = s.viewMode,
                    k = k,
                    modifier = Modifier.width((280 * k).dp),
                    onToggle = { vm.toggleViewMode() },
                )
            }
        }

        Spacer(Modifier.height((12 * k).dp))

        // 表頭
        Row(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = .04f), RoundedCornerShape((8 * k).dp))
                .padding(horizontal = (16 * k).dp, vertical = (10 * k).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width((56 * k).dp), contentAlignment = Alignment.CenterStart) {
                SoftLabel("POS", Label, k, 13f)
            }
            Box(Modifier.weight(2.6f)) {
                SoftLabel("ATHLETE", Label, k, 13f)
            }
            Box(Modifier.weight(2.8f)) {
                SoftLabel("PROGRESS & DISTANCE", Label, k, 13f)
            }
            Box(Modifier.width((90 * k).dp), contentAlignment = Alignment.CenterEnd) {
                SoftLabel("PACE", Label, k, 13f)
            }
            Box(Modifier.width((90 * k).dp), contentAlignment = Alignment.CenterEnd) {
                SoftLabel("SPEED", Label, k, 13f)
            }
            Box(Modifier.width((120 * k).dp), contentAlignment = Alignment.CenterEnd) {
                SoftLabel("GAP / STATUS", Label, k, 13f)
            }
        }

        Spacer(Modifier.height((8 * k).dp))

        // 選手清單 (支援 animateItem() 動態重排動畫)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy((8 * k).dp),
        ) {
            items(entries, key = { it.runnerId }) { entry ->
                val isMe = entry.runnerId == s.profile.runnerId
                LeaderboardRow(
                    entry = entry,
                    isMe = isMe,
                    raceDistanceM = s.raceDistanceM,
                    k = k,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun LeaderboardRow(
    entry: RaceClient.Entry,
    isMe: Boolean,
    raceDistanceM: Double,
    k: Float,
    modifier: Modifier = Modifier,
) {
    val isP1 = entry.rank == 1
    val isP2 = entry.rank == 2
    val isP3 = entry.rank == 3

    val (cardBg, cardBorder) = when {
        isMe -> Cyan.copy(alpha = .18f) to Cyan.copy(alpha = .85f)
        isP1 -> Gold.copy(alpha = .10f) to Gold.copy(alpha = .45f)
        else -> Color.White.copy(alpha = .04f) to Color.White.copy(alpha = .10f)
    }

    val rankColor = when {
        isMe -> Cyan
        isP1 -> Gold
        isP2 -> Color(0xFFC0D8E0)
        isP3 -> Amber
        else -> Label
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height((58 * k).dp)
            .clip(RoundedCornerShape((12 * k).dp))
            .background(cardBg)
            .border((1.2f * k).dp, cardBorder, RoundedCornerShape((12 * k).dp))
            .padding(horizontal = (16 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 名次 Badge
        Box(Modifier.width((56 * k).dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glow(
                    text = "P${entry.rank}",
                    color = rankColor,
                    k = k,
                    size = if (entry.rank <= 3 || isMe) 22f else 18f,
                    glow = isP1 || isMe,
                )
            }
        }

        // 2. 選手資訊 (頭像 + 國旗 + 姓名 + YOU 標籤)
        Row(Modifier.weight(2.6f), verticalAlignment = Alignment.CenterVertically) {
            val avatarBg = when {
                isMe -> Cyan
                isP1 -> Gold
                else -> Color.White.copy(alpha = .15f)
            }
            Box(
                Modifier.size((36 * k).dp)
                    .clip(RoundedCornerShape(50))
                    .background(avatarBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials(entry.name),
                    color = if (isMe || isP1) Carbon else Color.White,
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = (14 * k).sp,
                )
            }

            Spacer(Modifier.width((10 * k).dp))

            Text(
                text = flagEmoji(entry.country),
                fontSize = (20 * k).sp,
            )

            Spacer(Modifier.width((8 * k).dp))

            Text(
                text = entry.name,
                color = if (isMe) Cyan else Color.White,
                fontFamily = Grotesk,
                fontWeight = if (isMe || isP1) FontWeight.Bold else FontWeight.Medium,
                fontSize = (17 * k).sp,
                maxLines = 1,
            )

            if (isMe) {
                Spacer(Modifier.width((8 * k).dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Cyan)
                        .padding(horizontal = (8 * k).dp, vertical = (2 * k).dp)
                ) {
                    Text(
                        text = "YOU",
                        color = Carbon,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (11 * k).sp,
                        letterSpacing = (1 * k).sp,
                    )
                }
            }
        }

        // 3. 進度 Bar + 距離 + 百分比
        val pct = (entry.distance / max(1.0, raceDistanceM)).coerceIn(0.0, 1.0).toFloat()
        val barColor = if (isMe) Cyan else if (isP1) Gold else Color(0xFF6CF3F7)
        Row(
            Modifier.weight(2.8f).padding(end = (16 * k).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "%,dm".format(entry.distance.roundToInt()),
                        color = Color.White,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (14 * k).sp,
                    )
                    Text(
                        text = "%.1f%%".format(pct * 100f),
                        color = if (isMe) Cyan else Label,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.Medium,
                        fontSize = (12 * k).sp,
                    )
                }
                Spacer(Modifier.height((4 * k).dp))
                Canvas(Modifier.fillMaxWidth().height((8 * k).dp)) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(Color.White.copy(alpha = .12f), cornerRadius = r)
                    if (pct > 0f) {
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(barColor.copy(alpha = .7f), barColor)),
                            size = Size(size.width * pct, size.height),
                            cornerRadius = r,
                        )
                    }
                }
            }
        }

        // 4. 配速
        Box(Modifier.width((90 * k).dp), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End) {
                Glow(
                    text = fmtPace(entry.pace),
                    color = if (isMe) Cyan else Color.White,
                    k = k,
                    size = 17f,
                    glow = false,
                )
                SoftLabel("/km", Label, k, 11f)
            }
        }

        // 5. 速度
        Box(Modifier.width((90 * k).dp), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End) {
                Glow(
                    text = "%.1f".format(entry.speedKmh),
                    color = Color.White,
                    k = k,
                    size = 17f,
                    glow = false,
                )
                SoftLabel("km/h", Label, k, 11f)
            }
        }

        // 6. 差距 / 狀態
        Box(Modifier.width((120 * k).dp), contentAlignment = Alignment.CenterEnd) {
            when {
                entry.status == "FINISHED" || entry.finishTimeMs != null -> {
                    Glow("FINISHED", Gold, k, 15f)
                }
                entry.status == "DNF" -> {
                    Glow("DNF", Coral, k, 15f)
                }
                isP1 -> {
                    Glow("LEADER", Gold, k, 15f, glow = true)
                }
                entry.gapToLeaderMs != null -> {
                    Column(horizontalAlignment = Alignment.End) {
                        Glow(
                            text = "+%.1fs".format(entry.gapToLeaderMs / 1000.0),
                            color = Coral,
                            k = k,
                            size = 16f,
                            glow = false,
                        )
                        SoftLabel("TO P1", Label, k, 11f)
                    }
                }
                else -> {
                    SoftLabel("--", Label, k, 14f)
                }
            }
        }
    }
}

/* ── 最終成績排行榜視圖（步驟 10：30 秒倒數 + 離開按鈕） ── */

@Composable
private fun FinalLeaderboardView(
    s: RaceUiState,
    vm: RaceViewModel,
    now: Long,
    k: Float,
) {
    val entries = if (s.leaderboard.isNotEmpty()) s.leaderboard else sampleLeaderboard(s.profile.runnerId, s.distance, s.raceDistanceM)
    val startAt = s.startAtServerTime
    val remainingSec = s.autoExitRemainingSeconds(now)

    // 30 秒自動倒數歸零後，自動觸發返回大廳（步驟 10）
    LaunchedEffect(remainingSec) {
        if (remainingSec != null && remainingSec <= 0) {
            vm.leaveToLobby()
        }
    }

    Column(
        Modifier.fillMaxSize()
            .glass(k, Color.White.copy(alpha = .12f), Color.Black.copy(alpha = .38f))
            .padding((20 * k).dp)
    ) {
        // 1. 頂部列：標題 + 30秒倒數 + 離開按鈕
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glow("OFFICIAL FINAL RESULTS", Gold, k, 26f, glow = true)
                    Spacer(Modifier.width((12 * k).dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(Gold.copy(alpha = .15f))
                            .border((1 * k).dp, Gold.copy(alpha = .5f), RoundedCornerShape(50))
                            .padding(horizontal = (10 * k).dp, vertical = (3 * k).dp)
                    ) {
                        Text(
                            "EVENT CONCLUDED",
                            color = Gold,
                            fontFamily = Grotesk,
                            fontWeight = FontWeight.Bold,
                            fontSize = (12 * k).sp,
                            letterSpacing = (1 * k).sp,
                        )
                    }
                }
                Spacer(Modifier.height((4 * k).dp))
                SoftLabel(
                    "%,dm WORLD TRACK COMPETITION · OFFICIAL STANDINGS LOCKED".format(s.raceDistanceM.roundToInt()),
                    Label, k, 13f,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 30 秒倒數標籤
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border((1 * k).dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = (14 * k).dp, vertical = (6 * k).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size((8 * k).dp)
                            .clip(RoundedCornerShape(50))
                            .background(Cyan)
                    )
                    Spacer(Modifier.width((8 * k).dp))
                    Text(
                        text = "AUTO EXIT IN ${remainingSec ?: 30}s",
                        color = Cyan,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.Bold,
                        fontSize = (13 * k).sp,
                        letterSpacing = (0.5 * k).sp,
                    )
                }

                Spacer(Modifier.width((14 * k).dp))

                // 回大廳主要按鈕
                Box(
                    Modifier.clip(RoundedCornerShape((8 * k).dp))
                        .background(
                            Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.9f), Cyan))
                        )
                        .clickable { vm.leaveToLobby() }
                        .padding(horizontal = (20 * k).dp, vertical = (10 * k).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "‹ RETURN TO LOBBY",
                        color = Carbon,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (14 * k).sp,
                        letterSpacing = (0.8 * k).sp,
                    )
                }
            }
        }

        Spacer(Modifier.height((12 * k).dp))

        // 2. 個人成果焦點卡片（Finisher 榮譽卡 or DNF 關門卡）
        val isFinished = s.finishTimeMs != null
        val personalBg = if (isFinished) Cyan.copy(alpha = 0.12f) else Coral.copy(alpha = 0.12f)
        val personalBorder = if (isFinished) Cyan.copy(alpha = 0.6f) else Coral.copy(alpha = 0.6f)
        val personalTagColor = if (isFinished) Cyan else Coral

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape((10 * k).dp))
                .background(personalBg)
                .border((1.2f * k).dp, personalBorder, RoundedCornerShape((10 * k).dp))
                .padding(horizontal = (16 * k).dp, vertical = (10 * k).dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isFinished) "🏆" else "⚠️",
                    fontSize = (22 * k).sp,
                )
                Spacer(Modifier.width((12 * k).dp))
                Column {
                    Text(
                        text = if (isFinished) "CONGRATULATIONS, ${s.profile.name.uppercase()}!" else "TIME EXPIRED, ${s.profile.name.uppercase()}",
                        color = Color.White,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.Bold,
                        fontSize = (15 * k).sp,
                    )
                    Text(
                        text = if (isFinished) {
                            val timeStr = if (startAt != null) fmtClock(s.finishTimeMs - startAt) else fmtClock(s.finishTimeMs)
                            "You officially finished P${s.rank ?: 1} with time $timeStr · Avg Pace: ${s.pace}"
                        } else {
                            "Time cutoff reached (DNF) · Completed %,dm of %,dm".format(s.distance.roundToInt(), s.raceDistanceM.roundToInt())
                        },
                        color = Label,
                        fontFamily = Grotesk,
                        fontSize = (12 * k).sp,
                    )
                }
            }

            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(personalTagColor.copy(alpha = 0.2f))
                    .border((1 * k).dp, personalTagColor, RoundedCornerShape(50))
                    .padding(horizontal = (12 * k).dp, vertical = (4 * k).dp),
            ) {
                Text(
                    text = if (isFinished) "STATUS: OFFICIAL FINISH" else "STATUS: DNF",
                    color = personalTagColor,
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * k).sp,
                    letterSpacing = (0.5 * k).sp,
                )
            }
        }

        Spacer(Modifier.height((12 * k).dp))

        // 3. 表頭
        Row(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = .04f), RoundedCornerShape((8 * k).dp))
                .padding(horizontal = (16 * k).dp, vertical = (10 * k).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width((64 * k).dp), contentAlignment = Alignment.CenterStart) {
                SoftLabel("POS", Label, k, 13f)
            }
            Box(Modifier.weight(2.6f)) {
                SoftLabel("ATHLETE", Label, k, 13f)
            }
            Box(Modifier.width((110 * k).dp), contentAlignment = Alignment.Center) {
                SoftLabel("STATUS", Label, k, 13f)
            }
            Box(Modifier.weight(2.2f), contentAlignment = Alignment.CenterEnd) {
                SoftLabel("FINISH TIME / DISTANCE", Label, k, 13f)
            }
            Box(Modifier.width((100 * k).dp), contentAlignment = Alignment.CenterEnd) {
                SoftLabel("AVG PACE", Label, k, 13f)
            }
            Box(Modifier.width((100 * k).dp), contentAlignment = Alignment.CenterEnd) {
                SoftLabel("SPEED", Label, k, 13f)
            }
        }

        Spacer(Modifier.height((8 * k).dp))

        // 4. 成績清單
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy((8 * k).dp),
        ) {
            items(entries, key = { it.runnerId }) { entry ->
                val isMe = entry.runnerId == s.profile.runnerId
                FinalLeaderboardRow(
                    entry = entry,
                    isMe = isMe,
                    startAt = startAt,
                    k = k,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun FinalLeaderboardRow(
    entry: RaceClient.Entry,
    isMe: Boolean,
    startAt: Long?,
    k: Float,
    modifier: Modifier = Modifier,
) {
    val isP1 = entry.rank == 1
    val isP2 = entry.rank == 2
    val isP3 = entry.rank == 3
    val isFinished = entry.status == "FINISHED" || entry.finishTimeMs != null

    val (cardBg, cardBorder) = when {
        isMe -> Cyan.copy(alpha = .18f) to Cyan.copy(alpha = .85f)
        isP1 -> Gold.copy(alpha = .10f) to Gold.copy(alpha = .45f)
        else -> Color.White.copy(alpha = .04f) to Color.White.copy(alpha = .10f)
    }

    val rankColor = when {
        isMe -> Cyan
        isP1 -> Gold
        isP2 -> Color(0xFFC0D8E0)
        isP3 -> Color(0xFFCD7F32)
        else -> Label
    }

    val medalIcon = when (entry.rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height((58 * k).dp)
            .clip(RoundedCornerShape((12 * k).dp))
            .background(cardBg)
            .border((1.2f * k).dp, cardBorder, RoundedCornerShape((12 * k).dp))
            .padding(horizontal = (16 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 名次 Badge
        Box(Modifier.width((64 * k).dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (medalIcon.isNotEmpty()) {
                    Text(medalIcon, fontSize = (16 * k).sp)
                    Spacer(Modifier.width((4 * k).dp))
                }
                Glow(
                    text = "P${entry.rank}",
                    color = rankColor,
                    k = k,
                    size = if (entry.rank <= 3 || isMe) 20f else 17f,
                    glow = isP1 || isMe,
                )
            }
        }

        // 2. 選手資訊
        Row(Modifier.weight(2.6f), verticalAlignment = Alignment.CenterVertically) {
            val avatarBg = when {
                isMe -> Cyan
                isP1 -> Gold
                else -> Color.White.copy(alpha = .15f)
            }
            Box(
                Modifier.size((36 * k).dp)
                    .clip(RoundedCornerShape(50))
                    .background(avatarBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials(entry.name),
                    color = Carbon,
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = (13 * k).sp,
                )
            }
            Spacer(Modifier.width((12 * k).dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        color = Color.White,
                        fontFamily = Grotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (16 * k).sp,
                    )
                    if (isMe) {
                        Spacer(Modifier.width((8 * k).dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Cyan.copy(alpha = .25f))
                                .border((1 * k).dp, Cyan, RoundedCornerShape(50))
                                .padding(horizontal = (6 * k).dp, vertical = (2 * k).dp)
                        ) {
                            Text(
                                "YOU",
                                color = Cyan,
                                fontFamily = Grotesk,
                                fontWeight = FontWeight.Bold,
                                fontSize = (9 * k).sp,
                            )
                        }
                    }
                }
                Text(
                    text = "${countryFullName(entry.country)} ${flagEmoji(entry.country)}",
                    color = Label,
                    fontFamily = Grotesk,
                    fontSize = (12 * k).sp,
                )
            }
        }

        // 3. 狀態 Badge
        Box(Modifier.width((110 * k).dp), contentAlignment = Alignment.Center) {
            val stBg = if (isFinished) Gold.copy(alpha = 0.15f) else Coral.copy(alpha = 0.15f)
            val stColor = if (isFinished) Gold else Coral
            val stBorder = if (isFinished) Gold.copy(alpha = 0.5f) else Coral.copy(alpha = 0.5f)
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(stBg)
                    .border((1 * k).dp, stBorder, RoundedCornerShape(50))
                    .padding(horizontal = (10 * k).dp, vertical = (3 * k).dp)
            ) {
                Text(
                    text = if (isFinished) "FINISHED" else "DNF",
                    color = stColor,
                    fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * k).sp,
                    letterSpacing = (0.5 * k).sp,
                )
            }
        }

        // 4. 成績時間 / 距離
        Box(Modifier.weight(2.2f), contentAlignment = Alignment.CenterEnd) {
            val finishStr = when {
                entry.finishTimeMs != null && startAt != null -> fmtClock(max(0L, entry.finishTimeMs - startAt))
                entry.finishTimeMs != null -> fmtClock(entry.finishTimeMs)
                isFinished -> "--:--"
                else -> "DNF (%,dm)".format(entry.distance.roundToInt())
            }
            Text(
                text = finishStr,
                color = if (isFinished) Color.White else Coral,
                fontFamily = Grotesk,
                fontWeight = FontWeight.Bold,
                fontSize = (16 * k).sp,
            )
        }

        // 5. 配速
        Box(Modifier.width((100 * k).dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = entry.pace,
                color = Color.White,
                fontFamily = Grotesk,
                fontWeight = FontWeight.Medium,
                fontSize = (15 * k).sp,
            )
        }

        // 6. 速度
        Box(Modifier.width((100 * k).dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "%.1f km/h".format(entry.speedKmh),
                color = Label,
                fontFamily = Grotesk,
                fontWeight = FontWeight.Medium,
                fontSize = (14 * k).sp,
            )
        }
    }
}

private fun flagEmoji(country: String?): String {
    if (country == null || country.length != 2) return "🌐"
    val code = country.uppercase()
    val c0 = code[0]
    val c1 = code[1]
    if (c0 !in 'A'..'Z' || c1 !in 'A'..'Z') return "🌐"
    val first = c0.code - 'A'.code + 0x1F1E6
    val second = c1.code - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

private fun initials(name: String): String {
    val clean = name.replace(Regex("[^A-Za-z0-9\\s]"), " ").trim()
    val parts = clean.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

fun sampleLeaderboard(myRunnerId: String, myDist: Double, raceDist: Double): List<RaceClient.Entry> {
    val list = mutableListOf(
        RaceClient.Entry(
            rank = 1,
            runnerId = "R_ELIUD",
            name = "Eliud Kipchoge",
            distance = 3650.0,
            pace = "03'15\"",
            status = "RUNNING",
            gapToLeaderMs = null,
            gapToAheadMs = null,
            country = "KE",
            speedKmh = 18.5f,
            cadence = 190,
            progressPercent = (3650.0 / raceDist).coerceIn(0.0, 1.0),
        ),
        RaceClient.Entry(
            rank = 2,
            runnerId = myRunnerId,
            name = "Tung Lu",
            distance = myDist,
            pace = "04'05\"",
            status = "RUNNING",
            gapToLeaderMs = 102_000L,
            gapToAheadMs = 102_000L,
            country = "TW",
            speedKmh = 14.8f,
            cadence = 182,
            progressPercent = (myDist / raceDist).coerceIn(0.0, 1.0),
        ),
        RaceClient.Entry(
            rank = 3,
            runnerId = "R_KENJI",
            name = "Kenji Sato",
            distance = 3080.0,
            pace = "04'13\"",
            status = "RUNNING",
            gapToLeaderMs = 138_000L,
            gapToAheadMs = 36_000L,
            country = "JP",
            speedKmh = 14.2f,
            cadence = 178,
            progressPercent = (3080.0 / raceDist).coerceIn(0.0, 1.0),
        ),
        RaceClient.Entry(
            rank = 4,
            runnerId = "R_SARAH",
            name = "Sarah Connor",
            distance = 2850.0,
            pace = "04'26\"",
            status = "RUNNING",
            gapToLeaderMs = 194_000L,
            gapToAheadMs = 56_000L,
            country = "US",
            speedKmh = 13.5f,
            cadence = 174,
            progressPercent = (2850.0 / raceDist).coerceIn(0.0, 1.0),
        ),
        RaceClient.Entry(
            rank = 5,
            runnerId = "R_LUKAS",
            name = "Lukas Weber",
            distance = 2600.0,
            pace = "04'41\"",
            status = "RUNNING",
            gapToLeaderMs = 255_000L,
            gapToAheadMs = 61_000L,
            country = "DE",
            speedKmh = 12.8f,
            cadence = 170,
            progressPercent = (2600.0 / raceDist).coerceIn(0.0, 1.0),
        ),
        RaceClient.Entry(
            rank = 6,
            runnerId = "R_EMMA",
            name = "Emma Watson",
            distance = 2320.0,
            pace = "05'00\"",
            status = "RUNNING",
            gapToLeaderMs = 322_000L,
            gapToAheadMs = 67_000L,
            country = "GB",
            speedKmh = 12.0f,
            cadence = 166,
            progressPercent = (2320.0 / raceDist).coerceIn(0.0, 1.0),
        ),
    )
    return list.sortedByDescending { it.distance }.mapIndexed { idx, e ->
        e.copy(rank = idx + 1)
    }
}

/* ── 共用小元件 ── */

@Composable
private fun SoftLabel(text: String, color: Color, k: Float, size: Float, modifier: Modifier = Modifier) = Text(
    text, color = color, fontFamily = Grotesk, fontWeight = FontWeight.Medium,
    fontSize = (size * k).sp, letterSpacing = (size * .06f * k).sp, maxLines = 1, modifier = modifier,
)

/** 大數字：Space Grotesk 粗體、等寬數字，可選同色柔光。 */
@Composable
private fun Glow(text: String, color: Color, k: Float, size: Float, glow: Boolean = true) {
    val density = LocalDensity.current.density
    Text(
        text, maxLines = 1,
        style = TextStyle(
            color = color, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
            fontSize = (size * k).sp, letterSpacing = (-size * .02f * k).sp, fontFeatureSettings = "tnum",
            shadow = if (glow) Shadow(color.copy(alpha = .55f), Offset.Zero, size * .35f * k * density) else null,
        ),
    )
}

/** 玻璃卡片：半透明白底、細白邊、大圓角。 */
private fun Modifier.glass(
    k: Float, edge: Color = Color.White.copy(alpha = .14f), tint: Color? = null, radius: Float = 22f,
): Modifier {
    val shape = RoundedCornerShape((radius * k).dp)
    // 先墊一層深底當「毛玻璃」：背景資料流不會透出來干擾數字（真模糊需 API 31+ RenderEffect）
    return this.clip(shape)
        .background(Carbon.copy(alpha = .78f))
        .background(
            if (tint != null) Brush.verticalGradient(listOf(tint, tint))
            else Brush.verticalGradient(listOf(Color.White.copy(alpha = .09f), Color.White.copy(alpha = .035f)))
        )
        .border((1 * k).dp, edge, shape)
}

/** 字標：發光圓角青條＋斜體粗體 Space Grotesk（HUD、大廳、個人資料共用）。 */
@Composable
private fun FitRaceLogo(k: Float) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
        Modifier.width((5 * k).dp).height((26 * k).dp).drawBehind {
            drawRoundRect(Cyan.copy(alpha = .3f), Offset(-3f * k, -3f * k), Size(size.width + 6f * k, size.height + 6f * k), CornerRadius(6f * k))
            drawRoundRect(Cyan, cornerRadius = CornerRadius(3f * k))
        }
    )
    Spacer(Modifier.width((10 * k).dp))
    Text(
        "FitRace",
        style = TextStyle(
            color = Color.White, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic, fontSize = (30 * k).sp, letterSpacing = (-0.6 * k).sp,
            shadow = Shadow(Cyan.copy(alpha = .35f), Offset.Zero, 18f * k * LocalDensity.current.density),
        ),
    )
}

/* ── 繪圖與格式化 ── */

/**
 * 發光弧：三層同心筆畫（寬而淡 → 窄而實）疊出光暈。
 * ponytail: 疊筆畫取代 BlurMaskFilter，便宜且不需離屏圖層；要更真實的散景再換 RenderEffect。
 */
private fun DrawScope.glowArc(color: Color, start: Float, sweep: Float, radius: Float, width: Float, strength: Float = 1f) {
    if (sweep <= 0f) return
    val tl = Offset(center.x - radius, center.y - radius)
    val sz = Size(radius * 2f, radius * 2f)
    listOf(4.5f to .06f, 2.4f to .16f, 1f to 1f).forEach { (m, a) ->
        drawArc(
            color.copy(alpha = a * strength), start, sweep, false, tl, sz,
            style = Stroke(width * m, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.ambient(color: Color, at: Offset, radius: Float) = drawCircle(
    Brush.radialGradient(listOf(color, Color.Transparent), center = at, radius = radius),
    radius = radius, center = at,
)

/** 左青右紅的環境光，取代硬邊框來分區（HUD、大廳、個人資料共用）。 */
private fun DrawScope.ambientBackdrop(streams: Boolean = true) {
    // 背景每次重繪都一樣，只在尺寸改變時畫一次到點陣圖，之後每幀貼一張圖（HUD 每 50ms 重繪）
    val key = size to streams
    val cached = backdropCache?.takeIf { it.first == key }?.second ?: ImageBitmap(
        size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1),
    ).also { bmp ->
        CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bmp), size) {
            ambient(Cyan.copy(alpha = .10f), Offset(size.width * .3f, size.height * .5f), size.width * .45f)
            ambient(Coral.copy(alpha = .08f), Offset(size.width * .95f, size.height * .95f), size.width * .35f)
            if (streams) dataStreams()
        }
        backdropCache = key to bmp
    }
    drawImage(cached)
}

private var backdropCache: Pair<Pair<Size, Boolean>, ImageBitmap>? = null

/* ── 時空隧道背景（HUD）── */

/** 每 km/h 每秒往前推進的隧道深度（0..1 為整段隧道）；10 km/h ≈ 每 0.4 秒掠過一道環。 */
private const val TUNNEL_FLOW = .018f
private const val TUNNEL_RINGS = 14
private const val TUNNEL_NEAR = .6f // 深度 1 = 環剛好貼齊螢幕邊；小於 1 已飛出畫面
private const val TUNNEL_FAR = 8f
/** 超過這個速度隧道開始升溫，到 TUNNEL_HOT_KMH 全面燒成橘紅。 */
private const val TUNNEL_WARM_KMH = 10f
private const val TUNNEL_HOT_KMH = 15f
private val Ember = Color(0xFFFF4A1A) // 橘紅，介於 Amber 與 Coral 之間

/** 隧道牆上的流光：(dx, dy) 為深度 1 時在牆上的位置（相對消失點、以半寬半高為單位），p0 為初始深度相位。 */
private class TunnelStreak(val dx: Float, val dy: Float, val p0: Float, val coral: Boolean)

private val TUNNEL_STREAKS: List<TunnelStreak> by lazy {
    val rnd = java.util.Random(11)
    List(70) {
        val t = rnd.nextFloat() * 2f - 1f
        val (dx, dy) = when (rnd.nextInt(4)) { 0 -> -1f to t; 1 -> 1f to t; 2 -> t to -1f; else -> t to 1f }
        TunnelStreak(dx, dy, rnd.nextFloat(), rnd.nextFloat() < .15f)
    }
}

/**
 * 往前衝的數位隧道：一道道環從消失點朝選手飛來，牆上流光拉成殘影。
 * 流速跟跑步機速度走；速度 0（含倒數階段）時隧道靜止。自己一層 Canvas 逐幀重畫，不牽動前景儀表。
 */
@Composable
private fun TunnelBackdrop(speedKmh: Float) {
    // 跑步機回報有雜訊，平滑後再推流速，避免一頓一頓
    val speed = animateFloatAsState(speedKmh.coerceIn(0f, SPEED_MAX_KMH), tween(800), label = "tunnel")
    var travel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) travel = (travel + speed.value * TUNNEL_FLOW * (t - last) / 1e9f) % 1000f
            last = t
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        tunnel(travel, ((speed.value - TUNNEL_WARM_KMH) / (TUNNEL_HOT_KMH - TUNNEL_WARM_KMH)).coerceIn(0f, 1f))
    }
}

private fun fract(x: Float) = x - kotlin.math.floor(x)

/** 相位 p（0 遠 → 1 近）換成透視縮放；深度隨 p 線性前進，看起來才是等速。 */
private fun tunnelScale(p: Float) = 1f / (TUNNEL_FAR - p * (TUNNEL_FAR - TUNNEL_NEAR))

/** 遠處淡入、飛近淡出，中心留給主儀表。 */
private fun tunnelFade(p: Float) = min(1f, p * 3f) * min(1f, (1f - p) * 5f)

/**
 * 單一線段的升溫：每條線有自己的點燃門檻 t，heat 越過門檻後短短一段內燒成橘紅。
 * 不整體一起漸變，因為青色和橘紅互補，中間混出來是一片灰。
 */
private fun burn(heat: Float, t: Float) = ((heat * 1.25f - t) / .25f).coerceIn(0f, 1f)

private fun hot(base: Color, hotColor: Color, b: Float, alpha: Float) =
    lerp(base, hotColor, b).copy(alpha = min(1f, alpha * (1f + .5f * b)))

/** heat 0 = 正常青色，1 = 全面燒成橘紅、也更亮。 */
private fun DrawScope.tunnel(travel: Float, heat: Float) {
    val c = Offset(size.width * .5f, size.height * .46f)
    val hw = size.width * .5f
    val hh = size.height * .54f
    // 四個角往消失點收的縱向導軌
    for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, hot(Cyan, Ember, burn(heat, .5f), .10f)), c, Offset(c.x + sx * hw, c.y + sy * hh)),
            c, Offset(c.x + sx * hw / TUNNEL_NEAR, c.y + sy * hh / TUNNEL_NEAR), strokeWidth = size.height * .002f,
        )
    }
    for (i in 0 until TUNNEL_RINGS) {
        val p = fract(i.toFloat() / TUNNEL_RINGS + travel)
        val sc = tunnelScale(p)
        val a = tunnelFade(p)
        // 每第 5 道環換珊瑚紅當節奏點；升溫時它燒成亮橘，才不會在一片橘紅裡消失
        val b = burn(heat, fract(i * .618f))
        val color = if (i % 5 == 0) hot(Coral, Amber, b, .22f * a) else hot(Cyan, Ember, b, .22f * a)
        drawRoundRect(
            color, Offset(c.x - hw * sc, c.y - hh * sc), Size(2f * hw * sc, 2f * hh * sc),
            CornerRadius(hh * .12f * sc), style = Stroke(size.height * .004f * sc),
        )
    }
    for (b in TUNNEL_STREAKS) {
        val p = fract(b.p0 + travel * 1.6f) // 流光比環快一點，拉出層次
        val head = tunnelScale(p)
        val tail = tunnelScale((p - .06f).coerceAtLeast(0f))
        val a = tunnelFade(p)
        drawLine(
            if (b.coral) hot(Coral, Amber, burn(heat, b.p0), .55f * a) else hot(Cyan, Ember, burn(heat, b.p0), .55f * a),
            Offset(c.x + b.dx * hw * tail, c.y + b.dy * hh * tail),
            Offset(c.x + b.dx * hw * head, c.y + b.dy * hh * head),
            strokeWidth = size.height * .005f * head, cap = StrokeCap.Round,
        )
    }
}

/** 資料流的一段：side 0 左 / 1 右；row 為相對地平線的垂直位置 (-1..1)；u 為從螢幕外緣 (0) 往中心 (1) 的位置。 */
private class StreamBit(val side: Int, val row: Float, val u0: Float, val u1: Float, val kind: Int)

/** 固定種子，每次開機畫面都一樣。kind 0 = 暗色「程式碼」，1 = 青色亮段，2 = 珊瑚紅亮段。 */
private val STREAM_BITS: List<StreamBit> by lazy {
    val rnd = java.util.Random(7)
    buildList {
        for (side in 0..1) for (i in 0 until 20) {
            val row = -1f + 2f * i / 19f + (rnd.nextFloat() - .5f) * .05f
            var u = rnd.nextFloat() * .04f
            while (u < 1f) {
                val roll = rnd.nextFloat()
                // 紅色亮段多放右側，呼應設計稿
                val kind = when {
                    roll < .06f -> 1
                    roll < (if (side == 1) .12f else .08f) -> 2
                    else -> 0
                }
                val len = if (kind == 0) .008f + rnd.nextFloat() * .05f else .04f + rnd.nextFloat() * .06f
                add(StreamBit(side, row, u, min(1f, u + len), kind))
                u += len + .006f + rnd.nextFloat() * .03f
            }
        }
    }
}

/**
 * 設計稿兩側的資料流：一排排向畫面中心透視收束的細碎線段，像高速掠過的終端機文字。
 * 越往中心越細越淡，讓出主儀表的位置。
 */
private fun DrawScope.dataStreams() {
    val w = size.width
    val h = size.height
    val cy = h * .46f
    val reach = .36f // 每側佔畫面寬度比例
    for (b in STREAM_BITS) {
        val um = (b.u0 + b.u1) / 2f
        val shrink = 1f - .8f * um // 透視：往中心收束
        val y = cy + b.row * h * .6f * shrink
        val x0 = if (b.side == 0) w * reach * b.u0 else w * (1f - reach * b.u1)
        val x1 = if (b.side == 0) w * reach * b.u1 else w * (1f - reach * b.u0)
        val thick = h * (if (b.kind == 0) .0055f else .007f) * shrink
        // 外緣清楚、中心淡出；上下兩端也收淡，避免壓到頂列與賽道
        val fade = (1f - um * .85f) * (1f - abs(b.row) * .45f)
        val color = when (b.kind) {
            1 -> Cyan.copy(alpha = .85f * fade)
            2 -> Coral.copy(alpha = .90f * fade)
            else -> Label.copy(alpha = .30f * fade)
        }
        if (b.kind != 0) {
            // 亮段加一層寬而淡的底模擬光暈
            drawRect(color.copy(alpha = color.alpha * .3f), Offset(x0, y - thick * 2f), Size(x1 - x0, thick * 5f))
        }
        drawRect(color, Offset(x0, y - thick / 2f), Size(x1 - x0, thick))
    }
}

/** 兩端淡出的細白分隔線。 */
private fun DrawScope.softHairline(y: Float, k: Float) = drawRect(
    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .14f), Color.Transparent)),
    Offset(0f, y), Size(size.width, 1f * k),
)

/** 協議是 04'03"，畫面用 03:15 這種寫法 */
private fun beltStatusText(status: BeltStatus) = status.name

private fun fmtPace(p: String) = p.replace("'", ":").replace("\"", "")

/** 已跑時間加上「以目前速度跑完剩餘距離」的推估完賽時間；已完賽則顯示實際成績。 */
private fun etaText(s: RaceUiState, now: Long): String {
    val startAt = s.startAtServerTime ?: return "--:--"
    s.finishTimeMs?.let { return fmtClock(it - startAt) }
    val remaining = s.raceDistanceM - s.distance
    if (remaining <= 0.0 || s.speedKmh <= 0.3f) return "--:--"
    val elapsed = max(0L, now - startAt)
    return fmtClock(elapsed + (remaining / (s.speedKmh / 3.6) * 1000.0).toLong())
}

private fun fmtMetres(m: Double?): String {
    if (m == null) return "--"
    val r = m.roundToInt()
    return if (r >= 0) "+${r}m" else "-${abs(r)}m"
}

private fun fmtClock(ms: Long): String {
    val t = max(0L, ms)
    return "%02d:%02d.%02d".format(t / 60000, (t % 60000) / 1000, (t % 1000) / 10)
}

/* ─────────────────────────── 大廳 ─────────────────────────── */
/*
 * 版面依 Stitch 專案 17741436539835090440 的 "FitRace 10-Inch Race Lobby Kiosk"
 * （a5dce5db18834177b0ceec8979b4e0df）實作。設計稿的名額條／FULL 已拿掉：系統沒有房間人數上限。
 */

private val Muted = Color(0xFF5A6B78)

private fun statusColor(status: String) = when (status) {
    "OPEN" -> Cyan
    "STARTING" -> Gold
    "RUNNING" -> Coral
    else -> Muted
}

private fun statusBadge(status: String) = when (status) {
    "OPEN" -> "OPEN FOR ENTRY"
    "STARTING" -> "STARTING"
    "RUNNING" -> "LIVE"
    else -> "FINISHED"
}

/** 可報名的排最前面，其次倒數中、比賽中，已結束的放最後 */
private val STATUS_ORDER = listOf("OPEN", "STARTING", "RUNNING", "FINISHED")

@Composable
private fun Lobby(s: RaceUiState, vm: RaceViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(200) }
    }
    val serverNow = now + s.lobbyClockOffsetMs
    val rooms = s.rooms.sortedWith(
        compareBy({ STATUS_ORDER.indexOf(it.status).let { i -> if (i < 0) 99 else i } }, { it.roomId }),
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Carbon).drawBehind { ambientBackdrop() }) {
        val k = min(maxWidth.value / 1280f, maxHeight.value / 800f)
        Column(Modifier.fillMaxSize().padding(horizontal = (36 * k).dp)) {
            LobbyTopBar(s, vm, k)
            s.lobbyNotice?.let { NoticeBanner(it, k) }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (rooms.isEmpty()) {
                    SoftLabel(
                        if (s.lobbyOnline) "NO RACES YET — WAITING FOR THE ORGANIZER TO OPEN A HEAT"
                        else "CONNECTING TO RACE SERVER…",
                        Label, k, 17f,
                    )
                } else {
                    LazyRow(
                        Modifier.fillMaxHeight().padding(vertical = (24 * k).dp),
                        horizontalArrangement = Arrangement.spacedBy((16 * k).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(rooms, key = { it.roomId }) { r ->
                            RoomCard(r, serverNow, k) { vm.join(r.roomId) }
                        }
                    }
                }
            }
            LobbyFooter(s, rooms, k)
        }
    }
}

@Composable
private fun LobbyTopBar(s: RaceUiState, vm: RaceViewModel, k: Float) = Row(
    Modifier.fillMaxWidth().height((84 * k).dp).drawBehind { softHairline(size.height - 1f * k, k) },
    verticalAlignment = Alignment.CenterVertically,
) {
    FitRaceLogo(k)
    Box(
        Modifier.padding(horizontal = (16 * k).dp).width((1 * k).dp).height((22 * k).dp)
            .background(Color.White.copy(alpha = .18f))
    )
    SoftLabel("SELECT RACE", Color.White.copy(alpha = .8f), k, 18f)
    Spacer(Modifier.weight(1f))

    // 個人資料膠囊
    Row(
        Modifier.glass(k, radius = 50f).padding(horizontal = (6 * k).dp, vertical = (5 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size((34 * k).dp).clip(RoundedCornerShape(50)).background(Cyan.copy(alpha = .12f))
                .border((1 * k).dp, Cyan.copy(alpha = .45f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { SoftLabel(s.profile.country.ifBlank { "--" }, Cyan, k, 13f) }
        Spacer(Modifier.width((12 * k).dp))
        Column {
            Text(
                s.profile.name, color = Color.White, fontFamily = Grotesk,
                fontWeight = FontWeight.Bold, fontSize = (18 * k).sp, lineHeight = (20 * k).sp, maxLines = 1,
            )
            SoftLabel(s.profile.runnerId, Label, k, 11f)
        }
        Spacer(Modifier.width((16 * k).dp))
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .border((1 * k).dp, Color.White.copy(alpha = .25f), RoundedCornerShape(50))
                .clickable { vm.editProfile() }
                .padding(horizontal = (16 * k).dp, vertical = (8 * k).dp),
        ) { SoftLabel("EDIT PROFILE", Color.White, k, 12f) }
    }

    Spacer(Modifier.width((24 * k).dp))
    StatusDot("RACE SERVER · " + if (s.lobbyOnline) "ONLINE" else "OFFLINE", s.lobbyOnline, k)
}

@Composable
private fun NoticeBanner(text: String, k: Float) = Box(
    Modifier.fillMaxWidth().padding(top = (14 * k).dp)
        .glass(k, Coral.copy(alpha = .5f), Color(0xCC1A0A12), radius = 50f)
        .padding(horizontal = (24 * k).dp, vertical = (12 * k).dp),
) { SoftLabel(text, Coral, k, 15f) }

@Composable
private fun RoomCard(r: RoomInfo, serverNow: Long, k: Float, onJoin: () -> Unit) {
    val accent = statusColor(r.status)
    val finished = r.status == "FINISHED"
    val roomFull = r.capacity != null && r.runnerCount >= r.capacity
    Column(
        Modifier.width((236 * k).dp).fillMaxHeight()
            .glass(k, accent.copy(alpha = if (finished) .10f else .28f))
            // 卡片頂端一抹狀態色的環境光，取代硬邊括號
            .drawBehind { ambient(accent.copy(alpha = if (finished) .04f else .14f), Offset(size.width * .5f, 0f), size.width * .9f) }
            .padding((18 * k).dp),
    ) {
        StatusPill(r.status, accent, k)
        Spacer(Modifier.height((12 * k).dp))
        Text(
            r.title, color = if (finished) Label else Color.White, maxLines = 2,
            fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = (22 * k).sp,
            lineHeight = (26 * k).sp,
        )
        SoftLabel(r.roomId, Label, k, 11f)
        GlassDivider(k)

        when (r.status) {
            "OPEN" -> {
                SoftLabel("DISTANCE TARGET", Label, k, 12f)
                BigDistance(r.raceDistanceM, Color.White, k)
                Spacer(Modifier.height((12 * k).dp))
                // 顯示參賽人數與容量
                if (r.capacity != null) {
                    InfoBox("ATHLETES ENTERED", "${r.runnerCount} / ${r.capacity}", Cyan, k)
                    Spacer(Modifier.height((10 * k).dp))
                    CapacityBar(r.runnerCount, r.capacity, k)
                } else {
                    InfoBox("ATHLETES ENTERED", "${r.runnerCount}", Cyan, k)
                }
                // 自動發令倒計時
                if (r.autoStartAtServerTime != null) {
                    Spacer(Modifier.height((10 * k).dp))
                    val secRemain = ((r.autoStartAtServerTime - serverNow + 999) / 1000).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoftLabel("AUTO START IN ", Label, k, 12f)
                        Glow("${secRemain / 60}:${"%02d".format(secRemain % 60)}", Gold, k, 16f)
                    }
                }
            }
            "STARTING" -> {
                val secs = r.startAtServerTime?.let { ((it - serverNow + 999) / 1000).coerceAtLeast(0) } ?: 0
                HighlightBox(accent, "LAUNCH SEQUENCE", if (secs > 0) "T-$secs" else "GO", "SECONDS TO START", k)
                Spacer(Modifier.height((14 * k).dp))
                DistanceAndField(r, accent, k)
            }
            "RUNNING" -> {
                val elapsed = r.startAtServerTime?.let { max(0L, serverNow - it) } ?: 0L
                HighlightBox(
                    accent, "ELAPSED TIME",
                    "%02d:%02d".format(elapsed / 60000, (elapsed % 60000) / 1000), null, k,
                )
                Spacer(Modifier.height((14 * k).dp))
                DistanceAndField(r, accent, k)
            }
            else -> {
                SoftLabel("STAGE DISTANCE", Label, k, 12f)
                BigDistance(r.raceDistanceM, Muted, k)
                Spacer(Modifier.height((12 * k).dp))
                InfoBox("FIELD", "${r.runnerCount} · ALL FINISHED", Muted, k)
            }
        }

        Spacer(Modifier.weight(1f))
        when {
            r.status != "OPEN" -> {
                LockedBox(
                    when (r.status) {
                        "STARTING" -> "COUNTDOWN IN PROGRESS\nENTRY CLOSED"
                        "RUNNING" -> "RACE IN PROGRESS\nENTRY CLOSED"
                        else -> "RACE FINISHED"
                    },
                    accent, k,
                )
            }
            roomFull -> {
                LockedBox("ROOM FULL", Muted, k)
            }
            else -> {
                PrimaryPill("JOIN RACE", k, onJoin)
            }
        }
    }
}

@Composable
private fun StatusPill(status: String, accent: Color, k: Float) = Row(
    Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = .12f))
        .border((1 * k).dp, accent.copy(alpha = .4f), RoundedCornerShape(50))
        .padding(horizontal = (10 * k).dp, vertical = (5 * k).dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(
        Modifier.size((10 * k).dp).drawBehind {
            drawCircle(accent.copy(alpha = .3f), radius = size.minDimension / 2f)
            drawCircle(accent, radius = size.minDimension / 4f)
        }
    )
    Spacer(Modifier.width((6 * k).dp))
    SoftLabel(statusBadge(status), accent, k, 11f)
}

@Composable
private fun BigDistance(m: Double, color: Color, k: Float) = Row(verticalAlignment = Alignment.Bottom) {
    Glow("%,d".format(m.roundToInt()), color, k, 58f, glow = color != Muted)
    Spacer(Modifier.width((4 * k).dp))
    SoftLabel("m", if (color == Muted) Muted else Cyan, k, 20f, Modifier.padding(bottom = (12 * k).dp))
}

/** 名額條：與 HUD 賽道同款的膠囊軌道＋發光填色。 */
@Composable
private fun CapacityBar(entered: Int, capacity: Int, k: Float) {
    val frac = if (capacity > 0) (entered.toFloat() / capacity).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().height((8 * k).dp)) {
        val pill = CornerRadius(size.height / 2f)
        drawRoundRect(Color.White.copy(alpha = .08f), cornerRadius = pill)
        if (frac > 0f) {
            val w = max(size.height, size.width * frac)
            val g = 3f * k
            drawRoundRect(Cyan.copy(alpha = .18f), Offset(-g, -g), Size(w + g * 2f, size.height + g * 2f), CornerRadius(size.height / 2f + g))
            drawRoundRect(Cyan, size = Size(w, size.height), cornerRadius = pill)
        }
    }
}

@Composable
private fun InfoBox(label: String, value: String, accent: Color, k: Float) = Column(
    Modifier.fillMaxWidth().glass(k, Color.White.copy(alpha = .08f), Color.White.copy(alpha = .04f), radius = 14f)
        .padding(horizontal = (14 * k).dp, vertical = (10 * k).dp),
) {
    SoftLabel(label, Label, k, 11f)
    Spacer(Modifier.height((2 * k).dp))
    Glow(value, if (accent == Muted) Muted else Color.White, k, 26f, glow = false)
}

@Composable
private fun HighlightBox(accent: Color, label: String, value: String, sub: String?, k: Float) = Column(
    Modifier.fillMaxWidth().glass(k, accent.copy(alpha = .35f), accent.copy(alpha = .08f), radius = 16f)
        .padding(vertical = (12 * k).dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    SoftLabel(label, accent, k, 11f)
    Glow(value, accent, k, 52f)
    sub?.let { SoftLabel(it, Label, k, 10f) }
}

@Composable
private fun DistanceAndField(r: RoomInfo, accent: Color, k: Float) = Row(Modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f)) {
        SoftLabel("DISTANCE", Label, k, 11f)
        Glow("%,d m".format(r.raceDistanceM.roundToInt()), Color.White, k, 22f, glow = false)
    }
    Column(horizontalAlignment = Alignment.End) {
        SoftLabel("ATHLETES", Label, k, 11f)
        Glow("${r.runnerCount}", accent, k, 22f)
    }
}

/** 主要動作鈕：發光青色膠囊（個人資料「進入大廳」與房卡「報名」共用）。 */
@Composable
private fun PrimaryPill(text: String, k: Float, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height((60 * k).dp)
        .drawBehind {
            // 兩層加寬的半透明底模擬光暈（畫在裁切之前，才能溢出按鈕邊緣）
            listOf(8f * k to .08f, 4f * k to .18f).forEach { (g, a) ->
                drawRoundRect(
                    Cyan.copy(alpha = a), Offset(-g, -g), Size(size.width + g * 2f, size.height + g * 2f),
                    CornerRadius(size.height / 2f + g),
                )
            }
        }
        .clip(RoundedCornerShape(50))
        .background(Brush.verticalGradient(listOf(Color(0xFF6CF3F7), Cyan)))
        .clickable(onClick = onClick),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    Canvas(Modifier.size((13 * k).dp)) {
        val r = 2f * k
        drawPath(
            Path().apply { moveTo(r, 0f); lineTo(size.width, size.height / 2f); lineTo(r, size.height); close() },
            Carbon,
        )
    }
    Spacer(Modifier.width((12 * k).dp))
    Text(
        text, color = Carbon, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
        fontSize = (18 * k).sp, letterSpacing = (1.8 * k).sp, maxLines = 1,
    )
}

/** 次要動作鈕：Amber / Gold 柔光膠囊（用於展示/驗證模式）。 */
@Composable
private fun SecondaryPill(text: String, k: Float, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height((60 * k).dp)
        .glass(k, Amber.copy(alpha = .45f), Color.White.copy(alpha = .04f), radius = 50f)
        .clickable(onClick = onClick),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    Canvas(Modifier.size((13 * k).dp)) {
        val r = 2f * k
        drawPath(
            Path().apply { moveTo(r, 0f); lineTo(size.width, size.height / 2f); lineTo(r, size.height); close() },
            Amber,
        )
    }
    Spacer(Modifier.width((12 * k).dp))
    Text(
        text, color = Amber, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
        fontSize = (18 * k).sp, letterSpacing = (1.8 * k).sp, maxLines = 1,
    )
}

@Composable
private fun LockedBox(text: String, accent: Color, k: Float) = Box(
    Modifier.fillMaxWidth().height((60 * k).dp)
        .glass(k, accent.copy(alpha = .3f), Color.White.copy(alpha = .03f), radius = 50f),
    contentAlignment = Alignment.Center,
) {
    Text(
        text, color = accent, fontFamily = Grotesk, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        fontSize = (12 * k).sp, letterSpacing = (1.2 * k).sp, lineHeight = (16 * k).sp,
    )
}

@Composable
private fun LobbyFooter(s: RaceUiState, rooms: List<RoomInfo>, k: Float) = Row(
    Modifier.fillMaxWidth().height((52 * k).dp).drawBehind { softHairline(0f, k) },
    verticalAlignment = Alignment.CenterVertically,
) {
    val open = rooms.count { it.status == "OPEN" }
    val dot = if (open > 0) Cyan else Muted
    Box(
        Modifier.size((12 * k).dp).drawBehind {
            drawCircle(dot.copy(alpha = .25f), radius = size.minDimension / 2f)
            drawCircle(dot, radius = size.minDimension / 4f)
        }
    )
    Spacer(Modifier.width((10 * k).dp))
    SoftLabel(
        if (rooms.isEmpty()) "NO RACES YET — WAITING FOR THE ORGANIZER TO OPEN A HEAT"
        else "${rooms.size} RACES · $open OPEN FOR ENTRY",
        Label, k, 13f,
    )
    Spacer(Modifier.weight(1f))
    SoftLabel(
        "TREADMILL LINK: " + if (s.treadmillConnected) beltStatusText(s.beltStatus) else "OFFLINE",
        if (s.treadmillConnected) Label else Coral, k, 13f,
    )
    Box(
        Modifier.padding(horizontal = (16 * k).dp).width((1 * k).dp).height((18 * k).dp)
            .background(Color.White.copy(alpha = .18f))
    )
    SoftLabel("AUTO-REFRESH 2.0S", Cyan.copy(alpha = .85f), k, 13f)
}
