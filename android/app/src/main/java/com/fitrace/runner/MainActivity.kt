package com.fitrace.runner

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
private val Hair = Color(0xFF1B3742)

private val Grotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

// 設計系統指定：數值與標籤一律 JetBrains Mono（等寬，高頻刷新不跳動）
private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private const val SPEED_MAX_KMH = 25f
private const val CADENCE_MAX_SPM = 200f

enum class Screen { PROFILE, LOBBY, RACE }

data class Profile(
    val host: String = "10.0.2.2:8080", // 模擬器要透過 10.0.2.2 才能連到開發機的 localhost
    val runnerId: String = "RUNNER_01",
    val name: String = "Alex Chen",
    val country: String = "TW",
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
) {
    /** 可以離開回大廳：尚未發令，或自己已完賽，或比賽已關閉。比賽中不給一鍵離開，免得誤觸。 */
    val canLeave: Boolean get() = startAtServerTime == null || finishTimeMs != null || closed
}

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
        // 在大廳就接上跑步機，選手等發令前可以先熱身
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
            Thread {
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
            }.start()
            main.postDelayed(this, LOBBY_REFRESH_MS)
        }
    }

    /* ── 大廳 ↔ 比賽 ── */

    fun join(roomId: String) {
        main.removeCallbacks(lobbyPoll)
        val p = _state.value.profile
        engine.reset()
        sequence = 0
        client?.close()
        client = RaceClient(p.host, roomId, p.runnerId, p.name, p.country.ifBlank { null }, deviceId, this)
            .also { it.connect() }
        _state.value = clearedRace(_state.value, Screen.RACE, roomId = roomId)
        main.post(uploadTick)
    }

    fun leaveToLobby(notice: String? = null) {
        // 若尚未發令，先在背景執行緒取消報名
        val s = _state.value
        if (s.startAtServerTime == null && s.roomId.isNotEmpty()) {
            // 先取出值再進背景執行緒：下面 clearedRace() 會立刻把 roomId 清空
            val host = s.profile.host
            val roomId = s.roomId
            val runnerId = s.profile.runnerId
            Thread { cancelRegistration(http, host, roomId, runnerId, deviceId) }.start()
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

    fun nudgeSpeed(delta: Float) {
        val target = (_state.value.targetSpeed + delta).coerceIn(0f, SPEED_MAX_KMH)
        treadmill.setTargetSpeed(target)
        _state.value = _state.value.copy(targetSpeed = target)
    }

    private fun onMetric(metric: TreadmillReading) {
        lastMetric = metric
        val s = _state.value
        if (!engine.isArmed) {
            _state.value = s.copy(
                speedKmh = metric.speedKmh, cadence = metric.cadence, incline = metric.incline,
                beltStatus = metric.status,
            )
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
        _state.value = s.copy(
            speedKmh = metric.speedKmh,
            targetSpeed = target,
            incline = metric.incline,
            beltStatus = metric.status,
            distance = sample.raceDistanceM,
            pace = sample.pace,
            cadence = sample.cadence,
            finishTimeMs = sample.finishTimeMs,
        )
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
        _state.value = _state.value.copy(serverConnected = connected)
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
        // 完賽後名次已定，差距凍結在撞線當下；否則其他人繼續跑會讓「領先幅度」一路縮到 0
        if (_state.value.finishTimeMs != null) {
            _state.value = _state.value.copy(fieldSize = sorted.size)
            return
        }
        val runnerId = _state.value.profile.runnerId
        val myIndex = sorted.indexOfFirst { it.runnerId == runnerId }
        val me = sorted.getOrNull(myIndex)
        // 領先者看的是對第 2 名的領先幅度，其餘人看的是對前一名的落後幅度
        val neighbour = if (myIndex == 0) sorted.getOrNull(1) else sorted.getOrNull(myIndex - 1)
        val gapToLeader = me?.let { (sorted.firstOrNull()?.distance ?: it.distance) - it.distance }
        val previous = _state.value.gapToLeaderM
        _state.value = _state.value.copy(
            rank = me?.rank,
            fieldSize = sorted.size,
            gapToLeaderM = gapToLeader,
            gapToNeighbourM = if (me != null && neighbour != null) neighbour.distance - me.distance else null,
            leaderDistanceM = sorted.firstOrNull()?.distance,
            // 與上一次廣播相比差距是否縮小；差距變化小於一公尺視為持平
            closingOnLeader = if (gapToLeader != null && previous != null &&
                kotlin.math.abs(gapToLeader - previous) >= 1.0
            ) gapToLeader < previous else null,
        )
    }

    override fun onRaceClosed(reason: String) {
        _state.value = _state.value.copy(closed = true)
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Carbon, surface = Deep)) {
                val vm: RaceViewModel = viewModel()
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

    Column(
        Modifier.fillMaxSize().background(Carbon).padding(horizontal = 64.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(
            "FitRace", color = Color.White, fontSize = 44.sp, fontFamily = Grotesk,
            fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
        )
        Text("ATHLETE PROFILE", color = Label, fontSize = 13.sp, fontFamily = Mono, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple("伺服器", host) { v: String -> host = v },
            Triple("選手 ID", id) { v: String -> id = v },
            Triple("姓名", name) { v: String -> name = v },
            Triple("國碼 (ISO 兩碼)", country) { v: String -> country = v },
        ).forEach { (label, value, set) ->
            OutlinedTextField(
                value = value, onValueChange = set, label = { Text(label) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.enterLobby(Profile(host.trim(), id.trim(), name.trim(), country.trim().uppercase())) },
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Carbon),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                "ENTER RACE LOBBY", fontSize = 18.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            )
        }
    }
}

/* ─────────────────────────── 競速 HUD ─────────────────────────── */

/**
 * 版面依 Stitch 專案 17741436539835090440 的
 * "FitRace 10-Inch Treadmill Athlete Cockpit HUD" 實作。
 * 以 1280x800 為基準等比縮放，換機台解析度不需重排版。
 */
@Composable
private fun Hud(s: RaceUiState, vm: RaceViewModel) {
    var now by remember { mutableLongStateOf(vm.serverNow()) }
    LaunchedEffect(Unit) {
        while (true) { now = vm.serverNow(); delay(50) }
    }
    val startAt = s.startAtServerTime
    val remain = if (startAt != null) startAt - now else null
    val counting = remain != null && remain > 0

    BoxWithConstraints(Modifier.fillMaxSize().background(Carbon)) {
        val k = min(maxWidth.value / 1280f, maxHeight.value / 800f)

        Column(
            Modifier.fillMaxSize().padding(
                start = (14 * k).dp, end = (14 * k).dp, top = (14 * k).dp, bottom = (30 * k).dp,
            )
        ) {
            CockpitTopBar(s, now, startAt, counting, remain, k) { vm.leaveToLobby() }
            Spacer(Modifier.height((12 * k).dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                TelemetryPanel(s, vm, k, Modifier.weight(1.95f).fillMaxHeight())
                Spacer(Modifier.width((12 * k).dp))
                CompetitiveMatrix(s, etaText(s, now), k, Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height((12 * k).dp))
            StageDistance(s, k)
        }

        if (s.safetyKeyDetached) {
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape((6 * k).dp))
                    .background(Color(0xEB1A0A12)).border((2 * k).dp, Coral, RoundedCornerShape((6 * k).dp))
                    .padding(horizontal = (40 * k).dp, vertical = (24 * k).dp)
            ) {
                Text(
                    "SAFETY KEY DETACHED", color = Coral, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, fontSize = (34 * k).sp, letterSpacing = (3 * k).sp,
                )
            }
        }
    }
}

/* ── 頂列 ── */

@Composable
private fun CockpitTopBar(
    s: RaceUiState, now: Long, startAt: Long?, counting: Boolean, remain: Long?, k: Float,
    onLeave: () -> Unit,
) = Row(
    Modifier.fillMaxWidth().height((58 * k).dp).padding(horizontal = (6 * k).dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.width((5 * k).dp).height((26 * k).dp).background(Cyan))
    Spacer(Modifier.width((10 * k).dp))
    // 與大螢幕同一套字標處理：斜體粗體 Space Grotesk
    Text(
        "FitRace", color = Color.White, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontSize = (30 * k).sp, letterSpacing = (-0.6 * k).sp,
    )
    Spacer(Modifier.width((14 * k).dp))
    Box(Modifier.width((1 * k).dp).height((22 * k).dp).background(Hair))
    Spacer(Modifier.width((14 * k).dp))
    MonoLabel("%,dM RACE // %s".format(s.raceDistanceM.roundToInt(), s.roomId.replace('_', ' ')), Color.White, k, 13f)
    Spacer(Modifier.width((22 * k).dp))
    LinkStatus("TREADMILL LINK", if (s.treadmillConnected) beltStatusText(s.beltStatus) else "OFFLINE", s.treadmillConnected, k)
    Spacer(Modifier.width((20 * k).dp))
    LinkStatus("RACE SERVER", if (s.serverConnected) "RTT ${s.rttMs}MS" else "RECONNECTING", s.serverConnected, k)
    // 顯示 CUTOFF 計時器（正在比賽且有截止時間）
    if (s.startAtServerTime != null && s.finishTimeMs == null && !s.closed && s.cutoffAtServerTime != null) {
        val cutoffRemain = max(0L, s.cutoffAtServerTime - now)
        Spacer(Modifier.width((20 * k).dp))
        MonoLabel("CUTOFF ${fmtClock(cutoffRemain)}", Label, k, 12f)
    }
    Spacer(Modifier.weight(1f))
    if (s.canLeave) {
        Box(
            Modifier.clip(RoundedCornerShape((4 * k).dp))
                .border((1 * k).dp, Label, RoundedCornerShape((4 * k).dp))
                .clickable(onClick = onLeave)
                .padding(horizontal = (16 * k).dp, vertical = (13 * k).dp),
        ) { MonoLabel("◀ LOBBY", Color.White, k, 13f) }
        Spacer(Modifier.width((12 * k).dp))
    }
    Row(
        Modifier.clip(RoundedCornerShape((4 * k).dp)).background(Color(0xFF101C24))
            .border((1 * k).dp, Hair, RoundedCornerShape((4 * k).dp))
            .padding(horizontal = (16 * k).dp, vertical = (7 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(
            when {
                s.dnf -> "DNF"
                startAt == null -> "STANDBY"
                counting -> "GET SET"
                s.finishTimeMs != null -> "FINISHED"
                else -> "ELAPSED"
            }, Label, k, 13f,
        )
        Spacer(Modifier.width((12 * k).dp))
        Text(
            when {
                s.dnf -> "%,dM".format(s.distance.roundToInt())
                startAt == null -> "--:--"
                counting -> "T-${(remain!! / 1000) + 1}"
                s.finishTimeMs != null -> fmtClock(s.finishTimeMs - startAt)
                else -> fmtClock(now - startAt)
            },
            color = if (s.dnf) Coral else if (counting || s.finishTimeMs != null) Gold else Cyan,
            fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = (32 * k).sp, letterSpacing = (-1 * k).sp,
        )
    }
}

@Composable
private fun LinkStatus(label: String, value: String, ok: Boolean, k: Float) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size((7 * k).dp).clip(RoundedCornerShape(50)).background(if (ok) Cyan else Coral))
        Spacer(Modifier.width((8 * k).dp))
        MonoLabel("$label:", Label, k, 12f)
        Spacer(Modifier.width((6 * k).dp))
        MonoLabel(value, if (ok) Cyan else Coral, k, 12f)
    }

private fun beltStatusText(status: BeltStatus) = status.name

/* ── 左側遙測面板 ── */

@Composable
private fun TelemetryPanel(s: RaceUiState, vm: RaceViewModel, k: Float, modifier: Modifier) =
    Column(modifier.panel(k).padding((20 * k).dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SpeedArc(s, k) }
            Column(
                Modifier.width((312 * k).dp),
                verticalArrangement = Arrangement.spacedBy((16 * k).dp),
            ) {
                MetricCard("CURRENT PACE", "MIN / KM", fmtPace(s.pace), Cyan, true, k)
                MetricCard("STRIDE CADENCE", "SPM", "${s.cadence}", Color.White, false, k)
                MetricCard("MOTOR INCLINE", "% GRADE", "%+.1f".format(s.incline), Color.White, false, k)
            }
        }
        Spacer(Modifier.height((12 * k).dp))
        Box(Modifier.fillMaxWidth().height((1 * k).dp).background(Hair))
        Spacer(Modifier.height((10 * k).dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("BELT: ${beltStatusText(s.beltStatus)}", Cyan, k, 12f)
            Spacer(Modifier.weight(1f))
            MonoLabel("TARGET SPEED: %.1f KM/H".format(s.targetSpeed), Label, k, 12f)
            Spacer(Modifier.width((12 * k).dp))
            NudgeButton("−", k) { vm.nudgeSpeed(-0.5f) }
            Spacer(Modifier.width((8 * k).dp))
            NudgeButton("+", k) { vm.nudgeSpeed(0.5f) }
        }
    }

@Composable
private fun SpeedArc(s: RaceUiState, k: Float) {
    val frac = (s.speedKmh / SPEED_MAX_KMH).coerceIn(0f, 1f)
    Box(Modifier.size((300 * k).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            dottedRing(Color(0xFF2B4450), 46, 8f * k)
            speedArc(Color(0xFF223540), 1f, 14f * k)
            speedArc(Cyan, frac, 14f * k)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoLabel("CURRENT BELT SPEED", Label, k, 12f)
            Spacer(Modifier.height((4 * k).dp))
            Text(
                "%.1f".format(s.speedKmh), color = Cyan, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = (76 * k).sp, letterSpacing = (-3 * k).sp,
            )
            Text(
                "KM/H", color = Color.White, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = (24 * k).sp, letterSpacing = (3 * k).sp,
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String, unit: String, value: String, valueColor: Color, accent: Boolean, k: Float,
) = Row(
    Modifier.fillMaxWidth().height((92 * k).dp)
        .clip(RoundedCornerShape((4 * k).dp))
        .background(Color(0xFF111B22))
        .border((1 * k).dp, if (accent) Cyan.copy(alpha = .55f) else Hair, RoundedCornerShape((4 * k).dp)),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.width((4 * k).dp).fillMaxHeight().background(if (accent) Cyan else Color(0xFF2B4450)))
    Column(Modifier.padding(start = (16 * k).dp)) {
        MonoLabel(title, Label, k, 12f)
        Spacer(Modifier.height((3 * k).dp))
        Text(
            unit, color = Color.White, fontFamily = Mono,
            fontWeight = FontWeight.Bold, fontSize = (20 * k).sp, letterSpacing = (1 * k).sp,
        )
    }
    Spacer(Modifier.weight(1f))
    Text(
        value, color = valueColor, fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = (40 * k).sp, letterSpacing = (-1 * k).sp,
        modifier = Modifier.padding(end = (18 * k).dp),
    )
}

/* ── 右側名次面板 ── */

@Composable
private fun CompetitiveMatrix(s: RaceUiState, eta: String, k: Float, modifier: Modifier) =
    Column(modifier.panel(k).padding((20 * k).dp)) {
        Row(Modifier.fillMaxWidth()) {
            MonoLabel("COMPETITIVE MATRIX", Cyan, k, 13f)
            Spacer(Modifier.weight(1f))
            MonoLabel("FIELD ${s.fieldSize}", Label, k, 13f)
        }
        Spacer(Modifier.height((26 * k).dp))
        MonoLabel("CURRENT RANK", Label, k, 12f)
        Spacer(Modifier.height((6 * k).dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                s.rank?.let { "P$it" } ?: "--",
                color = if (s.rank == 1) Gold else Cyan, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = (64 * k).sp, letterSpacing = (-2 * k).sp,
            )
            Spacer(Modifier.width((10 * k).dp))
            Text(
                "/ ${s.fieldSize} ATHLETES", color = Color(0xFFB9CACB), fontFamily = Mono,
                fontWeight = FontWeight.Medium, fontSize = (20 * k).sp,
                modifier = Modifier.padding(bottom = (10 * k).dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val leading = s.rank == 1
        StatBox(
            if (leading) Gold else Coral,
            if (leading) "LEAD OVER P2" else "GAP TO LEADER (P1)",
            if (leading) fmtMetres(s.gapToNeighbourM?.let { abs(it) }) else fmtMetres(s.gapToLeaderM),
            when {
                leading -> "LEADING"
                s.closingOnLeader == true -> "CLOSING"
                s.closingOnLeader == false -> "OPENING"
                else -> "DEFICIT"
            },
            k,
        )
        Spacer(Modifier.height((12 * k).dp))
        StatBox(
            Cyan, if (leading) "NEAREST CHASER" else "GAP TO RUNNER AHEAD",
            fmtMetres(s.gapToNeighbourM), "RELATIVE", k,
        )
        Spacer(Modifier.height((12 * k).dp))
        StatBox(Gold, "PROJECTED FINISH", eta, "EST. TIME", k)
    }

@Composable
private fun StatBox(accent: Color, title: String, value: String, note: String, k: Float) = Column(
    Modifier.fillMaxWidth()
        .clip(RoundedCornerShape((4 * k).dp))
        .background(accent.copy(alpha = .07f))
        .border((1.5 * k).dp, accent, RoundedCornerShape((4 * k).dp))
        .padding(horizontal = (14 * k).dp, vertical = (10 * k).dp),
) {
    MonoLabel(title, accent, k, 12f)
    Spacer(Modifier.height((4 * k).dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            value, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold,
            fontSize = (34 * k).sp, letterSpacing = (-1 * k).sp,
        )
        Spacer(Modifier.weight(1f))
        MonoLabel(note, Label, k, 11f)
    }
}

/* ── 底部賽道 ── */

@Composable
private fun StageDistance(s: RaceUiState, k: Float) {
    val pct = (s.distance / s.raceDistanceM).coerceIn(0.0, 1.0).toFloat()
    val leaderPct = s.leaderDistanceM?.let { (it / s.raceDistanceM).coerceIn(0.0, 1.0).toFloat() }
    Column(Modifier.fillMaxWidth().padding(horizontal = (6 * k).dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            MonoLabel("STAGE DISTANCE", Label, k, 12f)
            Spacer(Modifier.width((14 * k).dp))
            Text(
                "%,d / %,d M".format(s.distance.roundToInt(), s.raceDistanceM.roundToInt()),
                color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold,
                fontSize = (30 * k).sp, letterSpacing = (-1 * k).sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "%.1f%% COMPLETED".format(pct * 100),
                color = Cyan, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (16 * k).sp,
            )
        }
        Spacer(Modifier.height((10 * k).dp))
        Box(Modifier.fillMaxWidth().height((34 * k).dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val barH = 12f * k
                val top = 14f * k
                drawRoundRect(
                    Color(0xFF16232B), topLeft = Offset(0f, top), size = Size(size.width, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2f),
                )
                if (pct > 0f) {
                    drawRoundRect(
                        (if (s.finishTimeMs != null) Gold else Cyan).copy(alpha = .25f),
                        topLeft = Offset(0f, top - 3f * k),
                        size = Size(size.width * pct, barH + 6f * k),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH),
                    )
                    drawRoundRect(
                        if (s.finishTimeMs != null) Gold else Cyan,
                        topLeft = Offset(0f, top), size = Size(size.width * pct, barH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barH / 2f),
                    )
                }
                // 每五分之一賽程一道刻度，最後五分之一為衝刺區
                for (i in 1..4) {
                    val x = size.width * (i / 5f)
                    drawLine(
                        if (i == 4) Coral.copy(alpha = .8f) else Color(0xFF3A5460),
                        Offset(x, top - 5f * k), Offset(x, top + barH + 5f * k), strokeWidth = 2f * k,
                    )
                }
                leaderPct?.let { lp ->
                    val x = size.width * lp
                    drawCircle(Gold, radius = 7f * k, center = Offset(x, top + barH / 2f))
                }
                val me = size.width * pct
                val d = 9f * k
                drawPath(
                    Path().apply {
                        moveTo(me, top + barH / 2f - d); lineTo(me + d, top + barH / 2f)
                        lineTo(me, top + barH / 2f + d); lineTo(me - d, top + barH / 2f); close()
                    },
                    Color.White,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val step = s.raceDistanceM / 5
            listOf(
                "%,dM".format((step * 1).roundToInt()),
                "%,dM".format((step * 2).roundToInt()),
                "%,dM".format((step * 3).roundToInt()),
                "%,dM SPRINT".format((step * 4).roundToInt()),
                "%,dM FINISH".format(s.raceDistanceM.roundToInt()),
            ).forEachIndexed { i, t ->
                if (i > 0) Spacer(Modifier.weight(1f))
                MonoLabel(t, if (i >= 3) Coral.copy(alpha = .8f) else Color(0xFF4A6470), k, 10f)
            }
        }
    }
}

/* ── 共用小元件 ── */

@Composable
private fun MonoLabel(text: String, color: Color, k: Float, size: Float) = Text(
    text, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold,
    fontSize = (size * k).sp, letterSpacing = (size * 0.15f * k).sp, maxLines = 1,
)

@Composable
private fun NudgeButton(label: String, k: Float, onClick: () -> Unit) = Box(
    Modifier.size((34 * k).dp)
        .clip(RoundedCornerShape((4 * k).dp))
        .background(Color(0x44123240))
        .border((1 * k).dp, Hair, RoundedCornerShape((4 * k).dp))
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Text(label, color = Cyan, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (18 * k).sp) }

/** 面板外框：細邊框加上右上與左下的轉角括號。 */
private fun Modifier.panel(k: Float, bracket: Color = Cyan, edge: Color = Color(0xFF16323C)): Modifier = this
    .clip(RoundedCornerShape((4 * k).dp))
    .background(Color(0xFF0B1219))
    .drawBehind {
        val w = 1.5f * k
        drawRect(edge, style = Stroke(width = w))
        val len = 28f * k
        val c = bracket
        drawLine(c, Offset(size.width - len, w), Offset(size.width - w, w), strokeWidth = w * 2)
        drawLine(c, Offset(size.width - w, w), Offset(size.width - w, len), strokeWidth = w * 2)
        drawLine(c, Offset(w, size.height - len), Offset(w, size.height - w), strokeWidth = w * 2)
        drawLine(c, Offset(w, size.height - w), Offset(len, size.height - w), strokeWidth = w * 2)
    }

/* ── 繪圖與格式化 ── */

private const val ARC_START = 250f
private const val ARC_SWEEP = 220f

/** 速度弧：開口在左側，自十點鐘順時針掃到五點鐘。 */
private fun DrawScope.speedArc(color: Color, fraction: Float, width: Float) {
    if (fraction <= 0f) return
    val pad = width / 2f + 26f
    val d = size.minDimension - pad * 2f
    val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
    if (color != Cyan) {
        drawArc(
            color, ARC_START, ARC_SWEEP * fraction, false, topLeft, Size(d, d),
            style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        return
    }
    drawArc(
        color.copy(alpha = .22f), ARC_START, ARC_SWEEP * fraction, false, topLeft, Size(d, d),
        style = Stroke(width * 2.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
    drawArc(
        color, ARC_START, ARC_SWEEP * fraction, false, topLeft, Size(d, d),
        style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}

/** 弧外圈的點狀刻度環。 */
private fun DrawScope.dottedRing(color: Color, count: Int, dot: Float) {
    val r = size.minDimension / 2f - 6f
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (i in 0..count) {
        val a = Math.toRadians((ARC_START + ARC_SWEEP * i / count).toDouble())
        drawCircle(
            color, radius = dot / 2f,
            center = Offset(cx + (kotlin.math.cos(a) * r).toFloat(), cy + (kotlin.math.sin(a) * r).toFloat()),
        )
    }
}

/** 協議是 04'03"，畫面用 03:15 這種寫法 */
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
    return if (r >= 0) "+$r M" else "-${abs(r)} M"
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Carbon)) {
        val k = min(maxWidth.value / 1280f, maxHeight.value / 800f)
        Column(Modifier.fillMaxSize()) {
            LobbyTopBar(s, vm, k)
            Box(Modifier.fillMaxWidth().height((1 * k).dp).background(Hair))
            s.lobbyNotice?.let { NoticeBanner(it, k) }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (rooms.isEmpty()) {
                    MonoLabel(
                        if (s.lobbyOnline) "NO RACES YET — WAITING FOR THE ORGANIZER TO OPEN A HEAT"
                        else "CONNECTING TO RACE SERVER…",
                        Label, k, 15f,
                    )
                } else {
                    LazyRow(
                        Modifier.fillMaxHeight().padding(vertical = (28 * k).dp),
                        contentPadding = PaddingValues(horizontal = (24 * k).dp),
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
    Modifier.fillMaxWidth().height((80 * k).dp).padding(horizontal = (24 * k).dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.width((6 * k).dp).height((30 * k).dp).background(Cyan))
    Spacer(Modifier.width((12 * k).dp))
    Text(
        "FitRace", color = Color.White, fontFamily = Grotesk, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontSize = (32 * k).sp, letterSpacing = (-0.6 * k).sp,
    )
    Spacer(Modifier.width((14 * k).dp))
    Text(
        "// SELECT RACE", color = Cyan, fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = (20 * k).sp, letterSpacing = (3 * k).sp,
    )
    Spacer(Modifier.weight(1f))

    // 個人資料膠囊
    Row(
        Modifier.clip(RoundedCornerShape((4 * k).dp)).background(Color(0xFF0E171E))
            .border((1 * k).dp, Hair, RoundedCornerShape((4 * k).dp))
            .padding(horizontal = (14 * k).dp, vertical = (8 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.border((1 * k).dp, Label, RoundedCornerShape((2 * k).dp))
                .padding(horizontal = (8 * k).dp, vertical = (4 * k).dp),
        ) { MonoLabel(s.profile.country.ifBlank { "--" }, Color.White, k, 15f) }
        Spacer(Modifier.width((12 * k).dp))
        Column {
            Text(
                s.profile.name, color = Color.White, fontFamily = Grotesk,
                fontWeight = FontWeight.Bold, fontSize = (19 * k).sp, maxLines = 1,
            )
            MonoLabel(s.profile.runnerId, Label, k, 11f)
        }
        Spacer(Modifier.width((16 * k).dp))
        Box(
            Modifier.clip(RoundedCornerShape((2 * k).dp))
                .border((1 * k).dp, Label, RoundedCornerShape((2 * k).dp))
                .clickable { vm.editProfile() }
                .padding(horizontal = (12 * k).dp, vertical = (8 * k).dp),
        ) { MonoLabel("EDIT PROFILE", Color.White, k, 12f) }
    }

    Spacer(Modifier.width((20 * k).dp))
    Box(Modifier.width((1 * k).dp).height((36 * k).dp).background(Hair))
    Spacer(Modifier.width((18 * k).dp))
    Box(
        Modifier.size((10 * k).dp).clip(RoundedCornerShape(50))
            .background(if (s.lobbyOnline) Cyan else Coral)
    )
    Spacer(Modifier.width((10 * k).dp))
    Column {
        MonoLabel("RACE SERVER", Label, k, 11f)
        MonoLabel(if (s.lobbyOnline) "ONLINE" else "OFFLINE", if (s.lobbyOnline) Cyan else Coral, k, 15f)
    }
}

@Composable
private fun NoticeBanner(text: String, k: Float) = Box(
    Modifier.fillMaxWidth().padding(horizontal = (24 * k).dp, vertical = (12 * k).dp)
        .clip(RoundedCornerShape((4 * k).dp))
        .background(Color(0xEB1A0A12))
        .border((1.5 * k).dp, Coral, RoundedCornerShape((4 * k).dp))
        .padding(horizontal = (18 * k).dp, vertical = (12 * k).dp),
) { MonoLabel(text, Coral, k, 14f) }

@Composable
private fun RoomCard(r: RoomInfo, serverNow: Long, k: Float, onJoin: () -> Unit) {
    val accent = statusColor(r.status)
    val finished = r.status == "FINISHED"
    val roomFull = r.capacity != null && r.runnerCount >= r.capacity
    Column(
        Modifier.width((236 * k).dp).fillMaxHeight()
            .panel(k, bracket = accent, edge = accent.copy(alpha = if (finished) .18f else .4f))
            .padding((18 * k).dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.title, color = if (finished) Label else Color.White,
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = (22 * k).sp,
                    lineHeight = (26 * k).sp,
                )
                MonoLabel(r.roomId, Label, k, 11f)
            }
            Spacer(Modifier.width((8 * k).dp))
            Box(
                Modifier.border((1 * k).dp, accent, RoundedCornerShape((2 * k).dp))
                    .background(accent.copy(alpha = .12f))
                    .padding(horizontal = (6 * k).dp, vertical = (3 * k).dp),
            ) {
                Text(
                    statusBadge(r.status), color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold,
                    fontSize = (11 * k).sp, letterSpacing = (1.5 * k).sp, lineHeight = (13 * k).sp,
                    modifier = Modifier.width((78 * k).dp),
                )
            }
        }
        Box(Modifier.padding(vertical = (14 * k).dp).fillMaxWidth().height((1 * k).dp).background(Hair))

        when (r.status) {
            "OPEN" -> {
                MonoLabel("DISTANCE TARGET", Label, k, 12f)
                BigDistance(r.raceDistanceM, Color.White, k)
                Spacer(Modifier.height((14 * k).dp))
                // 顯示參賽人數與容量
                if (r.capacity != null) {
                    InfoBox("ATHLETES ENTERED", "${r.runnerCount} / ${r.capacity}", Cyan, k)
                    Spacer(Modifier.height((8 * k).dp))
                    CapacityBar(r.runnerCount, r.capacity, k)
                } else {
                    InfoBox("ATHLETES ENTERED", "${r.runnerCount}", Cyan, k)
                }
                // 自動發令倒計時
                if (r.autoStartAtServerTime != null) {
                    Spacer(Modifier.height((8 * k).dp))
                    val secRemain = ((r.autoStartAtServerTime - serverNow + 999) / 1000).coerceAtLeast(0)
                    MonoLabel("AUTO START IN ${secRemain / 60}:${"%02d".format(secRemain % 60)}", Gold, k, 12f)
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
                MonoLabel("STAGE DISTANCE", Label, k, 12f)
                BigDistance(r.raceDistanceM, Muted, k)
                Spacer(Modifier.height((14 * k).dp))
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
                JoinButton(k, onJoin)
            }
        }
    }
}

@Composable
private fun BigDistance(m: Double, color: Color, k: Float) = Row(verticalAlignment = Alignment.Bottom) {
    Text(
        "%,d".format(m.roundToInt()), color = color, fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = (58 * k).sp, letterSpacing = (-2 * k).sp,
    )
    Spacer(Modifier.width((4 * k).dp))
    Text(
        "M", color = if (color == Muted) Muted else Cyan, fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = (18 * k).sp, modifier = Modifier.padding(bottom = (12 * k).dp),
    )
}

@Composable
private fun CapacityBar(entered: Int, capacity: Int, k: Float) {
    val segments = min(20, capacity)
    val filledSegments = min(segments, (entered * segments + capacity - 1) / capacity)
    Row(Modifier.fillMaxWidth().height((6 * k).dp), horizontalArrangement = Arrangement.spacedBy((2 * k).dp)) {
        repeat(segments) { i ->
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (i < filledSegments) Cyan else Hair)
            )
        }
    }
}

@Composable
private fun InfoBox(label: String, value: String, accent: Color, k: Float) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape((2 * k).dp)).background(Color(0xFF0E171E))
        .border((1 * k).dp, Hair, RoundedCornerShape((2 * k).dp))
        .padding((12 * k).dp),
) {
    MonoLabel(label, Label, k, 11f)
    Spacer(Modifier.height((4 * k).dp))
    Text(
        value, color = if (accent == Muted) Muted else Color.White, fontFamily = Mono,
        fontWeight = FontWeight.Bold, fontSize = (26 * k).sp,
    )
}

@Composable
private fun HighlightBox(accent: Color, label: String, value: String, sub: String?, k: Float) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape((2 * k).dp)).background(accent.copy(alpha = .08f))
        .border((1 * k).dp, accent.copy(alpha = .5f), RoundedCornerShape((2 * k).dp))
        .padding(vertical = (12 * k).dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    MonoLabel(label, accent, k, 11f)
    Text(
        value, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = (52 * k).sp, letterSpacing = (-2 * k).sp,
    )
    sub?.let { MonoLabel(it, Label, k, 10f) }
}

@Composable
private fun DistanceAndField(r: RoomInfo, accent: Color, k: Float) = Row(Modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f)) {
        MonoLabel("DISTANCE", Label, k, 11f)
        Text(
            "%,d M".format(r.raceDistanceM.roundToInt()), color = Color.White, fontFamily = Mono,
            fontWeight = FontWeight.Bold, fontSize = (20 * k).sp,
        )
    }
    Column(horizontalAlignment = Alignment.End) {
        MonoLabel("ATHLETES", Label, k, 11f)
        Text(
            "${r.runnerCount}", color = accent, fontFamily = Mono,
            fontWeight = FontWeight.Bold, fontSize = (20 * k).sp,
        )
    }
}

@Composable
private fun JoinButton(k: Float, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height((76 * k).dp)
        .clip(RoundedCornerShape((2 * k).dp))
        .background(Cyan)
        .clickable(onClick = onClick),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    Canvas(Modifier.size((14 * k).dp)) {
        drawPath(
            Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height); close() },
            Carbon,
        )
    }
    Spacer(Modifier.width((12 * k).dp))
    Text(
        "JOIN RACE", color = Carbon, fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = (17 * k).sp, letterSpacing = (2.5 * k).sp,
    )
}

@Composable
private fun LockedBox(text: String, accent: Color, k: Float) = Box(
    Modifier.fillMaxWidth().height((76 * k).dp)
        .clip(RoundedCornerShape((2 * k).dp))
        .background(Color(0xFF0E171E))
        .border((1 * k).dp, accent.copy(alpha = .45f), RoundedCornerShape((2 * k).dp)),
    contentAlignment = Alignment.Center,
) {
    Text(
        text, color = accent, fontFamily = Mono, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        fontSize = (11 * k).sp, letterSpacing = (1.5 * k).sp, lineHeight = (16 * k).sp,
    )
}

@Composable
private fun LobbyFooter(s: RaceUiState, rooms: List<RoomInfo>, k: Float) = Row(
    Modifier.fillMaxWidth().height((50 * k).dp).background(Color(0xFF070D12))
        .padding(horizontal = (24 * k).dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val open = rooms.count { it.status == "OPEN" }
    Box(Modifier.size((7 * k).dp).clip(RoundedCornerShape(50)).background(if (open > 0) Cyan else Muted))
    Spacer(Modifier.width((10 * k).dp))
    MonoLabel(
        if (rooms.isEmpty()) "NO RACES YET — WAITING FOR THE ORGANIZER TO OPEN A HEAT"
        else "${rooms.size} RACES · $open OPEN FOR ENTRY",
        Label, k, 12f,
    )
    Spacer(Modifier.weight(1f))
    MonoLabel(
        "TREADMILL LINK: " + if (s.treadmillConnected) beltStatusText(s.beltStatus) else "OFFLINE",
        if (s.treadmillConnected) Label else Coral, k, 12f,
    )
    Spacer(Modifier.width((16 * k).dp))
    Box(Modifier.width((1 * k).dp).height((18 * k).dp).background(Hair))
    Spacer(Modifier.width((16 * k).dp))
    MonoLabel("AUTO-REFRESH 2.0S", Cyan, k, 13f)
}
