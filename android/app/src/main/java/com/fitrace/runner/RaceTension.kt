package com.fitrace.runner

/**
 * 比賽名次提示（race position alerts）：從即時榜判斷「被追近」「追近前一名」「領先」等持續狀態，
 * 以及超越／被超越／登上領先等一次性事件，決定何時在畫面中央彈出提示卡。
 *
 * 純計算、不碰 Android API，以 JVM 單元測試驗證。
 */

/** 從榜單擷取、與自己相鄰的對手資訊。榜單每 400ms 一筆，距離約有 ±2m 的抖動。 */
data class Standing(
    val rank: Int,
    val fieldSize: Int,
    val aheadId: String? = null,
    val aheadRank: Int? = null,
    /** 落後前一名的公尺數（正值） */
    val aheadGapM: Double? = null,
    /** 以自己的速度追上前一名需要的時間（伺服器的 gapToAheadMs）；速度為 0 時為 null */
    val aheadGapMs: Long? = null,
    val behindId: String? = null,
    val behindRank: Int? = null,
    /** 領先後一名的公尺數（正值） */
    val behindGapM: Double? = null,
    /** 後一名以他的速度追上自己需要的時間（他的 gapToAheadMs） */
    val behindGapMs: Long? = null,
)

/** 從排序前或排序後的榜單取出自己的相鄰資訊；自己不在榜上時為 null。 */
fun standingOf(entries: List<RaceClient.Entry>, runnerId: String): Standing? {
    val sorted = entries.sortedBy { it.rank }
    val i = sorted.indexOfFirst { it.runnerId == runnerId }
    if (i < 0) return null
    val me = sorted[i]
    val ahead = sorted.getOrNull(i - 1)
    val behind = sorted.getOrNull(i + 1)
    return Standing(
        rank = me.rank,
        fieldSize = sorted.size,
        aheadId = ahead?.runnerId,
        aheadRank = ahead?.rank,
        aheadGapM = ahead?.let { it.distance - me.distance },
        aheadGapMs = ahead?.let { me.gapToAheadMs },
        behindId = behind?.runnerId,
        behindRank = behind?.rank,
        behindGapM = behind?.let { me.distance - it.distance },
        behindGapMs = behind?.gapToAheadMs,
    )
}

/** 持續狀態，依優先序由低到高：NONE < LEADING < CATCHING < CLOSING_IN。 */
enum class TensionState { NONE, LEADING, CATCHING, CLOSING_IN }

/** 一次性事件，優先於任何持續狀態。 */
enum class TensionEvent { OVERTAKE, OVERTAKEN, BECAME_LEADER }

/** 中央提示卡要顯示的內容；[seq] 每彈出一次就加一，UI 以它判斷是否為新的一張。 */
data class TensionPopup(
    val seq: Long,
    val atMs: Long,
    /** 恰有一個非 null */
    val state: TensionState? = null,
    val event: TensionEvent? = null,
    val fromRank: Int? = null,
    val toRank: Int? = null,
)

/** 單側（前一名或後一名）的差距追蹤：取樣歷史、是否處於提示中、差距停止縮小的起點。 */
data class GapTrack(
    val rivalId: String? = null,
    val samples: List<Pair<Long, Double>> = emptyList(),
    val on: Boolean = false,
    val flatSince: Long? = null,
    /** 連續幾筆判定為縮小；進入需連續 [RaceTension.ENTER_STREAK] 筆，單筆抖動不會觸發 */
    val shrinkStreak: Int = 0,
)

data class Tension(
    val state: TensionState = TensionState.NONE,
    /** 本次更新觸發的事件（只存在一次更新） */
    val event: TensionEvent? = null,
    /** 最近一次彈出的提示卡（持續保留，UI 以 seq 判斷新舊） */
    val popup: TensionPopup? = null,
    val stateSince: Long = Long.MIN_VALUE / 2,
    val lastRank: Int? = null,
    val lastField: Int? = null,
    val behind: GapTrack = GapTrack(),
    val ahead: GapTrack = GapTrack(),
    /** 每種提示（以名稱為鍵）最後一次彈出的時間，供 15 秒冷卻 */
    val lastPopupAt: Map<String, Long> = emptyMap(),
)

object RaceTension {
    /** 進入門檻：差距 ≤ 3.0 秒 */
    const val ENTER_MS = 3_000L
    /** 退出門檻：差距 > 4.0 秒 */
    const val EXIT_MS = 4_000L
    /** 差距停止縮小持續這麼久就退出 */
    const val FLAT_EXIT_MS = 3_000L
    /** 同一種提示卡最短間隔 */
    const val COOLDOWN_MS = 15_000L
    /** 任何兩張提示卡的最短間隔（動畫每秒最多切換一次） */
    const val MIN_POPUP_GAP_MS = 1_000L
    /** 持續狀態最短停留時間：rank 在兩人並肩時每 400ms 跳動，晶片不能跟著閃 */
    const val MIN_DWELL_MS = 1_000L
    /** 「現在」取最近這段時間的平均 */
    const val RECENT_MS = 1_000L
    /** 「約 2 秒前」取這段時間範圍的平均：[now-3000, now-1600] */
    const val PAST_FROM_MS = 3_000L
    const val PAST_TO_MS = 1_600L
    /** 差距縮小超過這麼多公尺才算縮小，濾掉 ±2m 抖動的平均殘差 */
    const val SHRINK_EPS_M = 1.0
    /** 進入需連續這麼多筆判定為縮小 */
    const val ENTER_STREAK = 2

    /**
     * 以一筆新榜單推進狀態機。
     * @param active 只有比賽進行中（RaceUiState.canAdjustSpeed）才作用；否則回到 NONE，不出事件。
     * @param standing 自己在本筆榜單中的相鄰資訊；不在榜上時為 null
     */
    fun next(prev: Tension, standing: Standing?, active: Boolean, nowMs: Long): Tension {
        if (!active || standing == null) return inactive(prev)

        // 一次性事件：重連／加入後的第一筆不比，人數變動時不比
        val event = when {
            prev.lastRank == null || prev.lastField == null -> null
            prev.lastField != standing.fieldSize -> null
            standing.rank < prev.lastRank && standing.rank == 1 -> TensionEvent.BECAME_LEADER
            standing.rank < prev.lastRank -> TensionEvent.OVERTAKE
            standing.rank > prev.lastRank -> TensionEvent.OVERTAKEN
            else -> null
        }

        val behind = track(prev.behind, standing.behindId, standing.behindGapM, standing.behindGapMs, nowMs)
        val ahead = track(prev.ahead, standing.aheadId, standing.aheadGapM, standing.aheadGapMs, nowMs)

        val wanted = when {
            behind.on -> TensionState.CLOSING_IN
            ahead.on -> TensionState.CATCHING
            standing.rank == 1 && standing.fieldSize > 1 -> TensionState.LEADING
            else -> TensionState.NONE
        }
        val canChange = nowMs - prev.stateSince >= MIN_DWELL_MS
        val state = if (wanted != prev.state && canChange) wanted else prev.state
        val entered = state != prev.state && state != TensionState.NONE

        var popup = prev.popup
        var cooldowns = prev.lastPopupAt
        val lastAny = prev.popup?.atMs
        val gapOk = lastAny == null || nowMs - lastAny >= MIN_POPUP_GAP_MS
        fun ready(key: String) = gapOk && (cooldowns[key]?.let { nowMs - it >= COOLDOWN_MS } ?: true)
        val seq = (prev.popup?.seq ?: 0L) + 1

        if (event != null && ready(event.name)) {
            popup = TensionPopup(seq, nowMs, event = event, fromRank = prev.lastRank, toRank = standing.rank)
            cooldowns = cooldowns + (event.name to nowMs)
            // 登上領先與「領先」狀態是同一張卡，共用冷卻
            if (event == TensionEvent.BECAME_LEADER) cooldowns = cooldowns + (TensionState.LEADING.name to nowMs)
        } else if (entered && ready(state.name)) {
            popup = TensionPopup(seq, nowMs, state = state)
            cooldowns = cooldowns + (state.name to nowMs)
            if (state == TensionState.LEADING) cooldowns = cooldowns + (TensionEvent.BECAME_LEADER.name to nowMs)
        }

        return prev.copy(
            state = state,
            event = event,
            popup = popup,
            stateSince = if (state != prev.state) nowMs else prev.stateSince,
            lastRank = standing.rank,
            lastField = standing.fieldSize,
            behind = behind,
            ahead = ahead,
            lastPopupAt = cooldowns,
        )
    }

    /** WebSocket 重連：下一筆榜單只當基準，不比名次（斷線期間的名次變化不算超越）。 */
    fun onReconnect(prev: Tension): Tension = prev.copy(lastRank = null, lastField = null, event = null)

    /** 非比賽中：清掉狀態與歷史，但保留冷卻與最後一張卡的序號，避免重新進入時重播。 */
    private fun inactive(prev: Tension) = Tension(
        popup = prev.popup,
        lastPopupAt = prev.lastPopupAt,
        stateSince = if (prev.state != TensionState.NONE) Long.MIN_VALUE / 2 else prev.stateSince,
    )

    private fun track(prev: GapTrack, rivalId: String?, gapM: Double?, gapMs: Long?, now: Long): GapTrack {
        if (rivalId == null || gapM == null) return GapTrack()
        val same = rivalId == prev.rivalId
        val samples = (if (same) prev.samples.filter { now - it.first <= PAST_FROM_MS } else emptyList()) +
            (now to gapM)
        val shrinking = shrinking(samples, now)
        val streak = if (shrinking == true) (if (same) prev.shrinkStreak else 0) + 1 else 0
        return if (!same || !prev.on) {
            // 進入：差距 ≤ 3 秒，且連續兩筆都比約 2 秒前縮小
            val on = gapMs != null && gapMs <= ENTER_MS && streak >= ENTER_STREAK
            GapTrack(rivalId, samples, on = on, shrinkStreak = streak)
        } else {
            // 維持：差距 ≤ 4 秒，且「停止縮小」未持續 3 秒
            val flatSince = if (shrinking == true) null else (prev.flatSince ?: now)
            val on = gapMs != null && gapMs <= EXIT_MS && (flatSince == null || now - flatSince < FLAT_EXIT_MS)
            GapTrack(rivalId, samples, on = on, flatSince = if (on) flatSince else null, shrinkStreak = streak)
        }
    }

    /** 最近 1 秒的平均差距比約 2 秒前（1.6~3 秒前）的平均小超過 1m；資料不足時為 null。 */
    internal fun shrinking(samples: List<Pair<Long, Double>>, now: Long): Boolean? {
        val recent = samples.filter { now - it.first < RECENT_MS }.map { it.second }
        val past = samples.filter { now - it.first in PAST_TO_MS..PAST_FROM_MS }.map { it.second }
        if (recent.isEmpty() || past.isEmpty()) return null
        return recent.average() < past.average() - SHRINK_EPS_M
    }
}
