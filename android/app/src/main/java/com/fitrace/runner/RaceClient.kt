package com.fitrace.runner

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

/**
 * 雲端連線：Token 取得、時鐘對齊（§3.1）、遙測上報與榜單接收（§3.4）。
 * 協議與 `server/src/lib.rs` 的 DTO 對應，JSON 直接用 Android 內建的 org.json，不另外拉序列化函式庫。
 */
class RaceClient(
    private val host: String,
    private val roomId: String,
    private val runnerId: String,
    private val name: String,
    private val country: String?,
    private val deviceId: String,
    private val listener: Listener,
    private val http: OkHttpClient = OkHttpClient(),
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onClockSynced(offsetMs: Long, rttMs: Long)
        fun onRaceScheduled(startAtServerTime: Long, raceDistanceM: Double, cutoffAtServerTime: Long?)
        fun onLeaderboard(entries: List<Entry>)
        /** 報名被拒，error 碼：REGISTRATION_CLOSED、ROOM_FULL、RUNNER_ID_IN_USE、ROOM_NOT_FOUND */
        fun onJoinRejected(reason: String)
        fun onRaceClosed(reason: String)
        fun onRaceCancelled()
    }

    data class Entry(
        val rank: Int,
        val runnerId: String,
        val name: String,
        val distance: Double,
        val pace: String,
        val status: String,
        val gapToLeaderMs: Long?,
        /** 落後前一名的距離 ÷ 自己目前速度；領先者或速度為 0 時為 null */
        val gapToAheadMs: Long? = null,
        val country: String? = null,
        val avatarUrl: String? = null,
        val speedKmh: Float = 0f,
        val cadence: Int = 0,
        val progressPercent: Double = 0.0,
        val finishTimeMs: Long? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    // 由主執行緒寫、OkHttp 執行緒讀；關閉後一律不再回呼，避免舊房間的訊息汙染新的一場
    @Volatile private var closed = false
    private var backoffMs = 500L
    private var bestRtt = Long.MAX_VALUE

    @Volatile var clockOffsetMs = 0L
        private set

    /** 已對齊到伺服器基準的當下時間（§3.1）。 */
    fun serverNow(): Long = System.currentTimeMillis() + clockOffsetMs

    /** 每個 RaceClient 只服務一場；close() 之後不會再連線，換房間請建立新實例。 */
    fun connect() {
        if (closed) return
        Thread {
            val token = try {
                requestToken()
            } catch (e: RegistrationClosed) {
                // 報名截止不是暫時性錯誤，重試也沒用
                post { listener.onJoinRejected(e.error) }
                return@Thread
            } catch (e: Exception) {
                null
            }
            if (token == null) {
                main.post { scheduleRetry() }
                return@Thread
            }
            val req = Request.Builder().url("ws://$host/ws?token=$token").build()
            ws = http.newWebSocket(req, socketListener)
        }.start()
    }

    fun close() {
        closed = true
        ws?.close(1000, null)
        ws = null
    }

    /** 2~3Hz 組包上報（§2.2-4）。 */
    fun sendTelemetry(
        sequenceId: Long,
        raceElapsedMs: Long,
        distance: Double,
        speedKmh: Float,
        cadence: Int,
        pace: String,
        finishTimeMs: Long?,
    ) {
        val packet = JSONObject()
            .put("type", "TELEMETRY")
            .put("roomId", roomId)
            .put("runnerId", runnerId)
            .put("sequenceId", sequenceId)
            .put("serverSyncTime", serverNow())
            .put("raceElapsedMs", raceElapsedMs)
            .put("currentDistance", Math.round(distance * 100.0) / 100.0)
            .put("currentSpeed", speedKmh.toDouble())
            .put("cadence", cadence)
            .put("currentPace", pace)
            .put("isFinished", finishTimeMs != null)
            .put("finishTimeMs", finishTimeMs ?: JSONObject.NULL)
        ws?.send(packet.toString())
    }

    private fun requestToken(): String {
        val body = JSONObject()
            .put("runnerId", runnerId)
            .put("name", name)
            .put("role", "RUNNER")
            .put("bib", runnerId)
            .put("deviceId", deviceId)
            .apply { country?.takeIf { it.isNotBlank() }?.let { put("country", it) } }
            .toString()
        val req = Request.Builder()
            .url("http://$host/rooms/$roomId/tokens")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 404) {
                throw RegistrationClosed("ROOM_NOT_FOUND")
            }
            if (res.code == 409) {
                val error = runCatching { JSONObject(text).getString("error") }.getOrDefault("REGISTRATION_CLOSED")
                throw RegistrationClosed(error)
            }
            require(res.isSuccessful) { "token ${res.code}" }
            return JSONObject(text).getString("token")
        }
    }

    private class RegistrationClosed(val error: String) : Exception()

    /** 只在客戶端未關閉時回呼，執行時再檢查一次（關閉可能發生在排隊期間）。 */
    private fun post(block: () -> Unit) {
        if (!closed) main.post { if (!closed) block() }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 500
            bestRtt = Long.MAX_VALUE
            post { listener.onConnectionChanged(true) }
            // §3.1：5 次 Ping-Pong 取樣
            repeat(SYNC_SAMPLES) { i ->
                main.postDelayed({
                    webSocket.send(
                        JSONObject().put("type", "PING")
                            .put("clientSendTime", System.currentTimeMillis()).toString()
                    )
                }, i * 120L)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "PONG" -> handlePong(msg)
                "RACE_SCHEDULED" -> {
                    val startAt = msg.getLong("startAtServerTime")
                    val dist = msg.optDouble("raceDistanceMeters", 5000.0)
                    val cutoff = if (msg.isNull("cutoffAtServerTime")) null else msg.getLong("cutoffAtServerTime")
                    post { listener.onRaceScheduled(startAt, dist, cutoff) }
                }
                "LEADERBOARD_UPDATE" -> {
                    val entries = parseRankings(msg)
                    post { listener.onLeaderboard(entries) }
                }
                "RACE_CLOSED" -> {
                    val reason = msg.getString("reason")
                    post { listener.onRaceClosed(reason) }
                }
                "RACE_CANCELLED" -> {
                    post { listener.onRaceCancelled() }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            post {
                listener.onConnectionChanged(false)
                scheduleRetry()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            post {
                listener.onConnectionChanged(false)
                scheduleRetry()
            }
        }
    }

    private fun handlePong(msg: JSONObject) {
        val sent = msg.getLong("clientSendTime")
        val serverTime = msg.getLong("serverTime")
        val rtt = System.currentTimeMillis() - sent
        // 取 RTT 最小的樣本，其單向延遲不對稱造成的誤差最小
        if (rtt < bestRtt) {
            bestRtt = rtt
            clockOffsetMs = serverTime - (sent + rtt / 2)
            post { listener.onClockSynced(clockOffsetMs, rtt) }
        }
    }

    private fun parseRankings(msg: JSONObject): List<Entry> {
        val arr = msg.optJSONArray("rankings") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                rank = o.getInt("rank"),
                runnerId = o.getString("runnerId"),
                name = o.getString("name"),
                distance = o.getDouble("distance"),
                pace = o.getString("pace"),
                status = o.getString("status"),
                gapToLeaderMs = if (o.isNull("gapToLeaderMs")) null else o.getLong("gapToLeaderMs"),
                gapToAheadMs = if (!o.has("gapToAheadMs") || o.isNull("gapToAheadMs")) null else o.getLong("gapToAheadMs"),
                country = if (o.has("country") && !o.isNull("country")) o.getString("country") else null,
                avatarUrl = if (o.has("avatarUrl") && !o.isNull("avatarUrl")) o.getString("avatarUrl") else null,
                speedKmh = if (o.has("speedKmh")) o.getDouble("speedKmh").toFloat() else 0f,
                cadence = if (o.has("cadence")) o.getInt("cadence") else 0,
                progressPercent = if (o.has("progressPercent")) o.getDouble("progressPercent") else 0.0,
                finishTimeMs = if (o.has("finishTimeMs") && !o.isNull("finishTimeMs")) o.getLong("finishTimeMs") else null,
            )
        }
    }

    /** §5.2 的指數退避，選手端同樣適用。 */
    private fun scheduleRetry() {
        if (closed) return
        // 排隊期間若已 close()，connect() 開頭的檢查會讓它直接放棄
        main.postDelayed({ connect() }, backoffMs)
        backoffMs = min(backoffMs * 2, 10_000L)
    }

    private companion object {
        const val TAG = "RaceClient"
        const val SYNC_SAMPLES = 5
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** 大廳上的一個房間（GET /rooms）。 */
data class RoomInfo(
    val roomId: String,
    /** OPEN / STARTING / RUNNING / FINISHED */
    val status: String,
    val raceDistanceM: Double,
    val runnerCount: Int,
    val startAtServerTime: Long?,
    val title: String = roomId.replace('_', ' '),
    val capacity: Int? = null,
    val autoStartAtServerTime: Long? = null,
)

/** 讀取大廳清單，回傳 (伺服器時間, 房間)。會阻塞，須在背景執行緒呼叫。 */
fun fetchRooms(http: OkHttpClient, host: String): Pair<Long, List<RoomInfo>> {
    http.newCall(Request.Builder().url("http://$host/rooms").build()).execute().use { res ->
        require(res.isSuccessful) { "rooms ${res.code}" }
        val body = JSONObject(res.body?.string().orEmpty())
        val arr = body.getJSONArray("rooms")
        val rooms = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RoomInfo(
                roomId = o.getString("roomId"),
                status = o.getString("status"),
                raceDistanceM = o.getDouble("raceDistanceMeters"),
                runnerCount = o.getInt("runnerCount"),
                startAtServerTime = if (o.isNull("startAtServerTime")) null else o.getLong("startAtServerTime"),
                title = o.optString("title", o.getString("roomId").replace('_', ' ')),
                capacity = if (o.isNull("capacity")) null else o.getInt("capacity"),
                autoStartAtServerTime = if (o.isNull("autoStartAtServerTime")) null else o.getLong("autoStartAtServerTime"),
            )
        }
        return body.getLong("serverTime") to rooms
    }
}

/** 取消報名。會阻塞，須在背景執行緒呼叫。不檢查結果，錯誤忽略。 */
fun cancelRegistration(http: OkHttpClient, host: String, roomId: String, runnerId: String, deviceId: String) {
    runCatching {
        // 選手 ID 是手打的，可能含斜線或空白，交給 HttpUrl 逐段編碼
        val url = "http://$host/".toHttpUrl().newBuilder()
            .addPathSegments("rooms").addPathSegment(roomId)
            .addPathSegment("runners").addPathSegment(runnerId)
            .addQueryParameter("deviceId", deviceId)
            .build()
        val req = Request.Builder().url(url).delete().build()
        http.newCall(req).execute().use { }
    }
}
