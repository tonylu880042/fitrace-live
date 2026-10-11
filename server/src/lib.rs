//! FitRace 競賽協議與共用計算（伺服器與 mock runner 共用）。
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;
use std::time::{SystemTime, UNIX_EPOCH};

/// 預設 5km 挑戰賽（§2.2）；發令時可另指定距離。
pub const RACE_DISTANCE_M: f64 = 5000.0;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Telemetry {
    pub room_id: String,
    pub runner_id: String,
    pub sequence_id: u64,
    pub server_sync_time: i64,
    pub race_elapsed_ms: i64,
    pub current_distance: f64,
    pub current_speed: f32,
    /// 步頻 SPM，來自 `TreadmillMetric.cadence`（§2.1）
    pub cadence: u16,
    pub current_pace: String,
    pub is_finished: bool,
    pub finish_time_ms: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RunnerStatus {
    Waiting,
    Running,
    Finished,
    Dnf,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RankEntry {
    pub rank: u32,
    pub runner_id: String,
    pub name: String,
    pub country: Option<String>,
    pub bib: Option<String>,
    pub avatar_url: Option<String>,
    pub distance: f64,
    pub pace: String,
    pub speed_kmh: f32,
    pub cadence: u16,
    pub progress_percent: f64,
    pub status: RunnerStatus,
    pub finish_time_ms: Option<i64>,
    /// 落後領先者的時間。未完賽者以自身當前速度換算距離差；速度為 0 時無從估計。
    pub gap_to_leader_ms: Option<i64>,
    /// 落後前一名的時間，演算方式同上。
    pub gap_to_ahead_ms: Option<i64>,
    pub lane: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Leaderboard {
    pub room_id: String,
    pub server_time: i64,
    pub rankings: Vec<RankEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ClientMsg {
    #[serde(rename = "PING", rename_all = "camelCase")]
    Ping { client_send_time: i64 },
    #[serde(rename = "TELEMETRY")]
    Telemetry(Telemetry),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
#[allow(clippy::large_enum_variant)] // 訊息建完即序列化送出，不會成批存放，Box 沒有好處
pub enum ServerMsg {
    #[serde(rename = "PONG", rename_all = "camelCase")]
    Pong { client_send_time: i64, server_time: i64 },
    #[serde(rename = "RACE_SCHEDULED", rename_all = "camelCase")]
    RaceScheduled {
        room_id: String,
        start_at_server_time: i64,
        race_distance_meters: f64,
        cutoff_at_server_time: i64,
    },
    #[serde(rename = "LEADERBOARD_UPDATE")]
    LeaderboardUpdate(Leaderboard),
    #[serde(rename = "RACE_CLOSED", rename_all = "camelCase")]
    RaceClosed { room_id: String, closed_at_server_time: i64, reason: String },
    #[serde(rename = "RACE_CANCELLED", rename_all = "camelCase")]
    RaceCancelled { room_id: String },
    #[serde(rename = "RUNNER_JOINED", rename_all = "camelCase")]
    RunnerJoined {
        room_id: String,
        runner_id: String,
        name: String,
        country: Option<String>,
        bib: Option<String>,
        avatar_url: Option<String>,
        lane: Option<u32>,
        field_size: usize,
        capacity: Option<u32>,
        tier: Option<String>,
        bio: Option<String>,
        pr5k: Option<String>,
        target_pace: Option<String>,
        vo2_max: Option<f32>,
        server_time: i64,
    },
}

/// 伺服器保存的選手最新狀態。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunnerState {
    pub runner_id: String,
    pub name: String,
    /// 選手註冊資訊（發 Token 時帶入），大螢幕用來顯示國別/隊伍
    pub country: Option<String>,
    pub bib: Option<String>,
    pub avatar_url: Option<String>,
    pub device_id: String,
    pub distance: f64,
    pub pace: String,
    pub speed_kmh: f32,
    pub cadence: u16,
    pub finish_time_ms: Option<i64>,
    pub lane: Option<u32>,
    pub tier: Option<String>,
    pub bio: Option<String>,
    pub pr5k: Option<String>,
    pub target_pace: Option<String>,
    pub vo2_max: Option<f32>,
}

impl RunnerState {
    /// 套用一筆遙測。完賽後比賽對該選手已結束：成績與距離都固定，不再被後續封包改動。
    pub fn apply(&mut self, t: &Telemetry, race_distance_m: f64) {
        if self.finish_time_ms.is_none() {
            self.finish_time_ms = t.finish_time_ms;
        }
        // 撞線那一筆通常略超過終點（取樣間隔內多跑的幾公尺），一律釘在賽事距離
        self.distance = if self.finish_time_ms.is_some() {
            race_distance_m
        } else {
            t.current_distance
        };
        self.pace = t.current_pace.clone();
        self.speed_kmh = t.current_speed;
        self.cadence = t.cadence;
    }
}

/// 完賽時間容許超前伺服器時鐘的上限：校時（§3.1）誤差的寬限，實測偏差更大時再調。
pub const CLOCK_SLACK_MS: i64 = 1000;

/// 信任邊界：把一筆遙測修成伺服器願意採信的樣子，整筆不可信則回傳 `None`。
/// 開跑前只留速度/步頻（大螢幕熱身顯示），距離與成績一律不算；
/// 開跑後的完賽時間必須落在起跑之後、現在之前。
pub fn vet_telemetry(mut t: Telemetry, start_at_ms: Option<i64>, now_ms: i64) -> Option<Telemetry> {
    let Some(start_at) = start_at_ms.filter(|&s| s <= now_ms) else {
        t.current_distance = 0.0;
        t.finish_time_ms = None;
        return Some(t);
    };
    let finish_ok = t.finish_time_ms.is_none_or(|f| (start_at..=now_ms + CLOCK_SLACK_MS).contains(&f));
    (t.current_distance >= 0.0 && finish_ok).then_some(t)
}

/// 首位選手完賽後，其餘選手的衝刺關門時間（§9.1）。
pub const SPRINT_CUTOFF_MS: i64 = 100_000;

#[derive(Debug, PartialEq)]
pub enum Settlement {
    Continue,
    /// 首位選手剛完賽：啟動衝刺關門倒數，值為新的關門時刻
    StartSprint(i64),
    /// 結算，值為 `RACE_CLOSED` 的 reason
    Close(&'static str),
}

/// 比賽進行中每個 tick 的結算判定。`cutoff_at_ms` 是一般關門時刻，
/// `sprint_cutoff_ms` 是已啟動的衝刺關門時刻（不會晚於一般關門）。
pub fn settle(
    finished: usize,
    total: usize,
    cutoff_at_ms: i64,
    sprint_cutoff_ms: Option<i64>,
    now_ms: i64,
) -> Settlement {
    if total > 0 && finished == total {
        Settlement::Close("ALL_FINISHED")
    } else if now_ms >= sprint_cutoff_ms.unwrap_or(cutoff_at_ms) {
        Settlement::Close("CUTOFF")
    } else if finished > 0 && sprint_cutoff_ms.is_none() {
        Settlement::StartSprint((now_ms + SPRINT_CUTOFF_MS).min(cutoff_at_ms))
    } else {
        Settlement::Continue
    }
}

#[derive(Debug, PartialEq)]
pub enum AutoStart {
    /// 還不發令；值為預定發令時刻（`None` = 人數不足，撤銷排程）
    Hold(Option<i64>),
    Go,
}

/// 自動規則（§9.2）每個 tick 對 OPEN 房間的判定：
/// 人數不足就撤銷排程、人數到了才開始倒數、額滿或倒數結束才發令。
pub fn auto_start(
    count: u32,
    min_runners: u32,
    capacity: u32,
    wait_ms: i64,
    auto_start_at_ms: Option<i64>,
    now_ms: i64,
) -> AutoStart {
    if count < min_runners {
        return AutoStart::Hold(None);
    }
    if count >= capacity {
        return AutoStart::Go;
    }
    match auto_start_at_ms {
        None => AutoStart::Hold(Some(now_ms + wait_ms)),
        Some(t) if t <= now_ms => AutoStart::Go,
        at => AutoStart::Hold(at),
    }
}

/// 房間對外的賽事狀態，給選手端大廳判斷能不能報名。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RoomStatus {
    /// 尚未發令，可報名
    Open,
    /// 已發令、倒數中
    Starting,
    /// 比賽中
    Running,
    /// 全員完賽
    Finished,
}

/// 由既有欄位推出房間狀態，不另外存狀態以免兩份資料不一致。
pub fn room_status(
    closed_at_ms: Option<i64>,
    start_at_ms: Option<i64>,
    now_ms: i64,
) -> RoomStatus {
    if closed_at_ms.is_some() {
        RoomStatus::Finished
    } else if start_at_ms.is_none() {
        RoomStatus::Open
    } else if start_at_ms.unwrap() > now_ms {
        RoomStatus::Starting
    } else {
        RoomStatus::Running
    }
}

/// 完賽者依撞線時間排前，未完賽者依距離排後。closed時未完賽者標記為DNF。
pub fn rank(runners: &[RunnerState], race_distance_m: f64, closed: bool) -> Vec<RankEntry> {
    let mut sorted: Vec<&RunnerState> = runners.iter().collect();
    sorted.sort_by(|a, b| {
        match (a.finish_time_ms, b.finish_time_ms) {
            (Some(x), Some(y)) => x.cmp(&y),
            (Some(_), None) => Ordering::Less,
            (None, Some(_)) => Ordering::Greater,
            (None, None) => b.distance.total_cmp(&a.distance),
        }
        // 同成績時以 id 決定順序，否則大螢幕榜單會在相同數據間跳動
        .then_with(|| a.runner_id.cmp(&b.runner_id))
    });
    let leader = sorted.first().map(|r| (r.distance, r.finish_time_ms));
    sorted
        .iter()
        .enumerate()
        .map(|(i, r)| RankEntry {
            rank: i as u32 + 1,
            runner_id: r.runner_id.clone(),
            name: r.name.clone(),
            country: r.country.clone(),
            bib: r.bib.clone(),
            avatar_url: r.avatar_url.clone(),
            distance: r.distance,
            pace: r.pace.clone(),
            speed_kmh: r.speed_kmh,
            cadence: r.cadence,
            gap_to_leader_ms: leader.and_then(|l| gap_ms(r, l, i)),
            gap_to_ahead_ms: i
                .checked_sub(1)
                .map(|p| (sorted[p].distance, sorted[p].finish_time_ms))
                .and_then(|ahead| gap_ms(r, ahead, i)),
            progress_percent: (r.distance / race_distance_m).clamp(0.0, 1.0),
            status: if r.finish_time_ms.is_some() {
                RunnerStatus::Finished
            } else if closed {
                RunnerStatus::Dnf
            } else if r.distance > 0.0 {
                RunnerStatus::Running
            } else {
                RunnerStatus::Waiting
            },
            finish_time_ms: r.finish_time_ms,
            lane: r.lane,
        })
        .collect()
}

/// 計算預設 Cutoff 時間（分鐘）：ceil(distanceM/1000 × 10)，最小1分。
pub fn default_cutoff_minutes(distance_m: f64) -> u32 {
    ((distance_m / 1000.0 * 10.0).ceil() as u32).max(1)
}

/// 與指定對手 `(距離, 完賽時間)` 的時間差。完賽者比成績；未完賽者把距離差除以自身當前速度換算。
fn gap_ms(r: &RunnerState, other: (f64, Option<i64>), idx: usize) -> Option<i64> {
    if idx == 0 {
        return None;
    }
    match (r.finish_time_ms, other.1) {
        (Some(mine), Some(lead)) => Some(mine - lead),
        _ => {
            let behind_m = other.0 - r.distance;
            if behind_m <= 0.0 || r.speed_kmh <= 0.1 {
                return None;
            }
            Some((behind_m / (r.speed_kmh as f64 / 3.6) * 1000.0).round() as i64)
        }
    }
}

/// 時鐘偏差估計（§3.1），回傳 `(rtt, offset)`。
/// 呼叫端應取多次採樣中 RTT 最小者，其單向延遲不對稱的誤差最小。
/// server_now = local_now + offset
pub fn clock_offset(client_send_ms: i64, server_ms: i64, client_recv_ms: i64) -> (i64, i64) {
    let rtt = client_recv_ms - client_send_ms;
    (rtt, server_ms - (client_send_ms + rtt / 2))
}

/// 終點線性插值（§2.2-3）：兩個取樣點跨越終點時的理論撞線時間。
pub fn interpolate_finish(t1: i64, d1: f64, t2: i64, d2: f64, target: f64) -> Option<i64> {
    if d1 >= target || d2 < target || d2 <= d1 {
        return None;
    }
    let frac = (target - d1) / (d2 - d1);
    Some(t1 + (frac * (t2 - t1) as f64).round() as i64)
}

/// 時速換算配速字串（§2.2-2），格式 `04'03"`。
pub fn format_pace(speed_kmh: f32) -> String {
    if speed_kmh <= 0.1 {
        return "--'--\"".to_string();
    }
    let secs = (3600.0 / speed_kmh).round() as i32;
    format!("{:02}'{:02}\"", secs / 60, secs % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runner(id: &str, distance: f64, finish: Option<i64>) -> RunnerState {
        RunnerState {
            runner_id: id.to_string(),
            name: id.to_string(),
            country: None,
            bib: None,
            avatar_url: None,
            device_id: format!("dev-{id}"),
            distance,
            pace: "04'00\"".to_string(),
            speed_kmh: 15.0,
            cadence: 180,
            finish_time_ms: finish,
            lane: None,
            tier: None,
            bio: None,
            pr5k: None,
            target_pace: None,
            vo2_max: None,
        }
    }

    #[test]
    fn finishers_outrank_runners_and_ties_are_stable() {
        let list = vec![
            runner("C", 4000.0, None),
            runner("A", 5000.0, Some(1_200_000)),
            runner("D", 4000.0, None),
            runner("B", 5000.0, Some(1_100_000)),
        ];
        let entries = rank(&list, RACE_DISTANCE_M, false);
        let ids: Vec<&str> = entries.iter().map(|e| e.runner_id.as_str()).collect();
        assert_eq!(ids, vec!["B", "A", "C", "D"]);
        assert_eq!(entries[0].status, RunnerStatus::Finished);
        assert_eq!(entries[2].status, RunnerStatus::Running);
        assert!((entries[2].progress_percent - 0.8).abs() < 1e-9);
    }

    #[test]
    fn waiting_runner_is_not_ranked_as_running() {
        let entries = rank(&[runner("A", 0.0, None)], RACE_DISTANCE_M, false);
        assert_eq!(entries[0].status, RunnerStatus::Waiting);
        assert_eq!(entries[0].progress_percent, 0.0);
    }

    fn telemetry(distance: f64, finish: Option<i64>) -> Telemetry {
        Telemetry {
            room_id: "R".into(),
            runner_id: "A".into(),
            sequence_id: 0,
            server_sync_time: 0,
            race_elapsed_ms: 0,
            current_distance: distance,
            current_speed: 18.0,
            cadence: 180,
            current_pace: "03'20\"".into(),
            is_finished: finish.is_some(),
            finish_time_ms: finish,
        }
    }

    #[test]
    fn telemetry_before_the_gun_cannot_score() {
        let (start, now) = (10_000, 9_000);
        // 倒數中送來的「完賽」封包：速度留著，距離與成績歸零
        for start_at in [None, Some(start)] {
            let t = vet_telemetry(telemetry(5000.0, Some(9_500)), start_at, now).unwrap();
            assert_eq!((t.current_distance, t.finish_time_ms), (0.0, None));
            assert_eq!(t.current_speed, 18.0);
        }
    }

    #[test]
    fn finish_time_must_fall_inside_the_race() {
        let (start, now) = (10_000, 70_000);
        let vet = |finish| vet_telemetry(telemetry(5000.0, finish), Some(start), now).is_some();
        assert!(vet(None));
        assert!(vet(Some(69_800)));
        assert!(vet(Some(now + CLOCK_SLACK_MS))); // 校時誤差寬限內
        assert!(!vet(Some(start - 1))); // 起跑前就完賽
        assert!(!vet(Some(now + CLOCK_SLACK_MS + 1))); // 未來的成績
        assert!(vet_telemetry(telemetry(-1.0, None), Some(start), now).is_none());
    }

    #[test]
    fn settlement_follows_sprint_and_cutoff_rules() {
        let (cutoff, now) = (3_000_000, 1_000_000);
        // 無人完賽、未到關門：繼續
        assert_eq!(settle(0, 3, cutoff, None, now), Settlement::Continue);
        // 首位完賽：啟動 100 秒衝刺關門，且只啟動一次
        assert_eq!(settle(1, 3, cutoff, None, now), Settlement::StartSprint(now + SPRINT_CUTOFF_MS));
        assert_eq!(settle(1, 3, cutoff, Some(now + 50_000), now), Settlement::Continue);
        // 衝刺關門不會晚於一般關門
        assert_eq!(settle(1, 3, now + 30_000, None, now), Settlement::StartSprint(now + 30_000));
        // 衝刺關門到、一般關門到、全員完賽
        assert_eq!(settle(1, 3, cutoff, Some(now), now), Settlement::Close("CUTOFF"));
        assert_eq!(settle(0, 3, now, None, now), Settlement::Close("CUTOFF"));
        assert_eq!(settle(3, 3, cutoff, Some(now + 50_000), now), Settlement::Close("ALL_FINISHED"));
        // 單人賽完賽：直接結算，不必先倒數
        assert_eq!(settle(1, 1, cutoff, None, now), Settlement::Close("ALL_FINISHED"));
    }

    #[test]
    fn auto_start_waits_for_runners_then_counts_down() {
        let now = 50_000;
        let wait = 60_000;
        // 人數不足：撤銷已排定的倒數
        assert_eq!(auto_start(1, 2, 8, wait, Some(now + 10_000), now), AutoStart::Hold(None));
        // 人數剛到：開始倒數；倒數中維持原時刻
        assert_eq!(auto_start(2, 2, 8, wait, None, now), AutoStart::Hold(Some(now + wait)));
        assert_eq!(auto_start(3, 2, 8, wait, Some(now + 10_000), now), AutoStart::Hold(Some(now + 10_000)));
        // 倒數結束或額滿：發令
        assert_eq!(auto_start(2, 2, 8, wait, Some(now), now), AutoStart::Go);
        assert_eq!(auto_start(8, 2, 8, wait, None, now), AutoStart::Go);
        // 一個人也發車（班次式：時間到有幾個人就幾個人跑）
        assert_eq!(auto_start(1, 1, 8, wait, Some(now), now), AutoStart::Go);
    }

    #[test]
    fn room_status_follows_the_race_lifecycle() {
        let now = 10_000;

        // 無closed無start → Open
        assert_eq!(room_status(None, None, now), RoomStatus::Open);
        // 無closed但start未到 → Starting
        assert_eq!(room_status(None, Some(now + 3_000), now), RoomStatus::Starting);
        // 無closed但start已過 → Running
        assert_eq!(room_status(None, Some(now - 60_000), now), RoomStatus::Running);
        // 有closed → Finished（忽略其他欄位）
        assert_eq!(room_status(Some(now - 30_000), Some(now - 60_000), now), RoomStatus::Finished);
    }

    #[test]
    fn rank_marks_dnf_when_closed() {
        let list = vec![
            runner("A", 5000.0, Some(1_000_000)),
            runner("B", 4500.0, None),
        ];
        let open = rank(&list, RACE_DISTANCE_M, false);
        assert_eq!(open[0].status, RunnerStatus::Finished);
        assert_eq!(open[1].status, RunnerStatus::Running);

        let closed = rank(&list, RACE_DISTANCE_M, true);
        assert_eq!(closed[0].status, RunnerStatus::Finished);
        assert_eq!(closed[1].status, RunnerStatus::Dnf);
    }

    #[test]
    fn default_cutoff_calculation() {
        assert_eq!(default_cutoff_minutes(1000.0), 10);
        assert_eq!(default_cutoff_minutes(5000.0), 50);
        assert_eq!(default_cutoff_minutes(50000.0), 500);
        // 最小1分
        assert_eq!(default_cutoff_minutes(50.0), 1);
    }

    #[test]
    fn distance_freezes_at_the_line_once_finished() {
        let mut r = runner("A", 0.0, None);
        r.apply(&telemetry(4990.0, None), 5000.0);
        assert_eq!(r.distance, 4990.0);

        // 撞線那一筆實際跑到 5003m，榜單上應該就是 5000m
        r.apply(&telemetry(5003.0, Some(1_000_000)), 5000.0);
        assert_eq!(r.distance, 5000.0);
        assert_eq!(r.finish_time_ms, Some(1_000_000));

        // 完賽後皮帶減速期間仍持續上報：距離與成績都不能再動
        r.apply(&telemetry(5040.0, Some(1_000_400)), 5000.0);
        assert_eq!(r.distance, 5000.0);
        assert_eq!(r.finish_time_ms, Some(1_000_000));
    }

    #[test]
    fn leader_gap_uses_time_for_finishers_and_speed_for_runners() {
        // 兩人皆完賽：直接比成績
        let done = rank(&[
            runner("A", 5000.0, Some(1_000_000)),
            runner("B", 5000.0, Some(1_002_500)),
        ], RACE_DISTANCE_M, false);
        assert_eq!(done[0].gap_to_leader_ms, None); // 領先者本身沒有落後值
        assert_eq!(done[1].gap_to_leader_ms, Some(2500));
        assert_eq!(done[1].gap_to_ahead_ms, Some(2500)); // 第 2 名的前一名就是領先者

        // 比賽中：落後 150m、時速 15km/h（4.1667 m/s）→ 約 36 秒
        let live = rank(&[runner("A", 1000.0, None), runner("B", 850.0, None)], RACE_DISTANCE_M, false);
        assert_eq!(live[1].gap_to_leader_ms, Some(36000));

        // 停在原地無從換算
        let mut stopped = runner("B", 850.0, None);
        stopped.speed_kmh = 0.0;
        let idle = rank(&[runner("A", 1000.0, None), stopped], RACE_DISTANCE_M, false);
        assert_eq!(idle[1].gap_to_leader_ms, None);
    }

    #[test]
    fn clock_offset_recovers_server_time() {
        // 本機時鐘慢 4 秒、RTT 100ms：校正後應能算出伺服器當下時間
        let (rtt, offset) = clock_offset(1000, 5050, 1100);
        assert_eq!(rtt, 100);
        assert_eq!(offset, 4000);
        assert_eq!(1100 + offset, 5100);
    }

    #[test]
    fn finish_interpolation_lands_between_samples() {
        assert_eq!(
            interpolate_finish(1000, 4990.0, 1400, 5010.0, 5000.0),
            Some(1200)
        );
        // 尚未跨越終點 / 已在終點後，皆不應產生成績
        assert_eq!(interpolate_finish(1000, 4000.0, 1400, 4500.0, 5000.0), None);
        assert_eq!(interpolate_finish(1000, 5000.0, 1400, 5100.0, 5000.0), None);
    }

    #[test]
    fn pace_formatting_matches_protocol() {
        assert_eq!(format_pace(15.0), "04'00\"");
        assert_eq!(format_pace(0.0), "--'--\"");
    }

    #[test]
    fn runner_joined_serialization_and_deserialization() {
        let msg = ServerMsg::RunnerJoined {
            room_id: "R0001".to_string(),
            runner_id: "R_ELIUD".to_string(),
            name: "Eliud Kipchoge".to_string(),
            country: Some("KE".to_string()),
            bib: Some("03".to_string()),
            avatar_url: Some("https://example.com/avatar.jpg".to_string()),
            lane: Some(3),
            field_size: 6,
            capacity: Some(8),
            tier: Some("WORLD CLASS TIER".to_string()),
            bio: Some("Marathon World Record Holder".to_string()),
            pr5k: Some("14:15.0".to_string()),
            target_pace: Some("02:50.4".to_string()),
            vo2_max: Some(84.2),
            server_time: 1700000000000,
        };
        let json_str = serde_json::to_string(&msg).unwrap();
        assert!(json_str.contains("\"type\":\"RUNNER_JOINED\""));
        assert!(json_str.contains("\"roomId\":\"R0001\""));
        assert!(json_str.contains("\"runnerId\":\"R_ELIUD\""));
        assert!(json_str.contains("\"name\":\"Eliud Kipchoge\""));
        assert!(json_str.contains("\"lane\":3"));
        // deviceId 是裝置綁定的憑證，不可出現在廣播裡
        assert!(!json_str.contains("deviceId"));
        assert!(json_str.contains("\"fieldSize\":6"));
        assert!(json_str.contains("\"capacity\":8"));
        assert!(json_str.contains("\"vo2Max\":84.2"));

        let de: ServerMsg = serde_json::from_str(&json_str).unwrap();
        match de {
            ServerMsg::RunnerJoined {
                room_id, runner_id, name, lane, field_size, capacity, vo2_max, ..
            } => {
                assert_eq!(room_id, "R0001");
                assert_eq!(runner_id, "R_ELIUD");
                assert_eq!(name, "Eliud Kipchoge");
                assert_eq!(lane, Some(3));
                assert_eq!(field_size, 6);
                assert_eq!(capacity, Some(8));
                assert_eq!(vo2_max, Some(84.2));
            }
            _ => panic!("Expected ServerMsg::RunnerJoined"),
        }
    }
}
