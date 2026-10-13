//! FitRace 賽事房間伺服器（Phase 1，本機執行）。
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path, Query, State,
    },
    http::StatusCode,
    response::{Html, Response},
    routing::{delete, get, post},
    Json, Router,
};
use fitrace_live::*;
use futures_util::{SinkExt, StreamExt};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::broadcast;

const BROADCAST_INTERVAL_MS: u64 = 400; // 2.5Hz（§3.4）
const COUNTDOWN_MS: i64 = 5000; // §3.2
/// 未指定時的人數上限：一排跑步機、大螢幕一頁放得下。
/// 榜單廣播的流量是人數平方，房間必須有上限。
const DEFAULT_CAPACITY: u32 = 10;
/// 重啟後先不結算的時間，讓選手端重連回報斷線期間的進度（選手端重連退避上限 10 秒）。
const RESUME_GRACE_MS: i64 = 10_000;
/// 選手 ID 與顯示名稱的字元數上限：兩者都是使用者輸入，會轉播給全房並顯示在大螢幕上。
const MAX_NAME_CHARS: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Role {
    Runner,
    Display,
}

struct TokenInfo {
    room_id: String,
    runner_id: String,
    role: Role,
}

#[derive(Serialize, Deserialize)]
struct Room {
    title: String,
    #[serde(skip, default = "room_channel")]
    tx: broadcast::Sender<String>,
    runners: HashMap<String, RunnerState>,
    start_at_ms: Option<i64>,
    race_distance_m: f64,
    capacity: u32,
    cutoff_ms: i64,
    closed_at_ms: Option<i64>,
    auto_start_at_ms: Option<i64>,
    rule_id: Option<String>,
    finish_countdown_cutoff_ms: Option<i64>,
}

fn room_channel() -> broadcast::Sender<String> {
    broadcast::channel(64).0
}

impl Room {
    /// 排程起跑（§3.2）：T_start = now + 5000ms，並通知房內所有人。回傳 `(起跑, 關門)` 時刻。
    fn schedule_start(&mut self, room_id: &str, now: i64) -> (i64, i64) {
        let start_at = now + COUNTDOWN_MS;
        let cutoff_at = start_at + self.cutoff_ms;
        self.start_at_ms = Some(start_at);
        self.auto_start_at_ms = None;
        let _ = self.tx.send(
            serde_json::to_string(&ServerMsg::RaceScheduled {
                room_id: room_id.to_string(),
                start_at_server_time: start_at,
                race_distance_meters: self.race_distance_m,
                cutoff_at_server_time: cutoff_at,
            })
            .unwrap(),
        );
        (start_at, cutoff_at)
    }

    /// 目前生效的關門時刻：已啟動衝刺關門就以它為準；已結算則沒有剩餘時間。
    fn cutoff_at(&self, start_at: i64) -> i64 {
        if self.closed_at_ms.is_some() {
            start_at
        } else {
            self.finish_countdown_cutoff_ms.unwrap_or(start_at + self.cutoff_ms)
        }
    }
}

struct AppState {
    // ponytail: 單一全域鎖，本機單場館夠用；同時開大量房間有鎖競爭時再改 per-room 鎖
    // 鎖的順序：rule_counter → rules → room_counter → rooms → tokens / db
    rooms: Mutex<HashMap<String, Room>>,
    tokens: Mutex<HashMap<String, TokenInfo>>,
    room_counter: Mutex<u32>,
    rules: Mutex<HashMap<String, Rule>>,
    rule_counter: Mutex<u32>,
    admin_key: String,
    // ponytail: 同步 SQLite 直接在 async 裡呼叫；只有結算與每秒快照會寫，量大時再移到 spawn_blocking
    db: Mutex<Connection>,
    /// 這個時刻之前不做結算（見 RESUME_GRACE_MS）
    settle_from_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Rule {
    title: String,
    distance_m: f64,
    capacity: u32,
    cutoff_minutes: u32,
    min_runners: u32,
    wait_seconds: u32,
}

/// 重啟時要還原的全部狀態。Token 不存：選手端與看板每次重連都會重新申請。
#[derive(Default, Deserialize)]
#[serde(default)]
struct Snapshot {
    rooms: HashMap<String, Room>,
    rules: HashMap<String, Rule>,
    room_counter: u32,
    rule_counter: u32,
}

fn open_db(path: &str) -> rusqlite::Result<Connection> {
    let db = Connection::open(path)?;
    db.execute_batch(
        "PRAGMA journal_mode = WAL;
         PRAGMA synchronous = NORMAL;
         CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK (id = 1), json TEXT NOT NULL);
         CREATE TABLE IF NOT EXISTS races (
             id INTEGER PRIMARY KEY, room_id TEXT NOT NULL, title TEXT NOT NULL, distance_m REAL NOT NULL,
             started_at INTEGER NOT NULL, closed_at INTEGER NOT NULL, reason TEXT NOT NULL,
             UNIQUE (room_id, started_at));
         -- duration_ms 為 NULL 即 DNF
         CREATE TABLE IF NOT EXISTS results (
             race_id INTEGER NOT NULL REFERENCES races (id), rank INTEGER NOT NULL, runner_id TEXT NOT NULL,
             name TEXT NOT NULL, country TEXT, duration_ms INTEGER, distance_m REAL NOT NULL);",
    )?;
    Ok(db)
}

fn load_snapshot(db: &Connection) -> Snapshot {
    let Ok(json) = db.query_row("SELECT json FROM state WHERE id = 1", [], |r| r.get::<_, String>(0)) else {
        return Snapshot::default();
    };
    serde_json::from_str(&json).unwrap_or_else(|e| {
        // 升級後欄位對不上就從空的開始，成績表不受影響
        eprintln!("狀態快照無法還原，從空白狀態啟動：{e}");
        Snapshot::default()
    })
}

/// 把低頻狀態（房間、報名、規則、流水號）存成一份快照，內容沒變就不寫。
// ponytail: 每秒整份序列化；房間上千時改成事件式增量寫入
fn persist(st: &AppState, last: &mut String) {
    let json = {
        let rule_counter = st.rule_counter.lock().unwrap();
        let rules = st.rules.lock().unwrap();
        let room_counter = st.room_counter.lock().unwrap();
        let rooms = st.rooms.lock().unwrap();
        json!({ "rooms": &*rooms, "rules": &*rules, "room_counter": *room_counter, "rule_counter": *rule_counter })
            .to_string()
    };
    if json == *last {
        return;
    }
    let written = st.db.lock().unwrap().execute(
        "INSERT INTO state (id, json) VALUES (1, ?1) ON CONFLICT (id) DO UPDATE SET json = excluded.json",
        [&json],
    );
    match written {
        Ok(_) => *last = json,
        Err(e) => eprintln!("狀態快照寫入失敗：{e}"),
    }
}

/// 結算時把成績落盤。同一場（房號 + 起跑時刻）只寫一次：重啟後重複結算不會多出第二份。
fn save_results(db: &mut Connection, room_id: &str, room: &Room, closed_at: i64, reason: &str) -> rusqlite::Result<()> {
    let Some(start_at) = room.start_at_ms else {
        return Ok(());
    };
    let tx = db.transaction()?;
    let inserted = tx.execute(
        "INSERT OR IGNORE INTO races (room_id, title, distance_m, started_at, closed_at, reason)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![room_id, room.title, room.race_distance_m, start_at, closed_at, reason],
    )?;
    if inserted == 1 {
        let race_id = tx.last_insert_rowid();
        let runners: Vec<RunnerState> = room.runners.values().cloned().collect();
        for e in rank(&runners, room.race_distance_m, true) {
            tx.execute(
                "INSERT INTO results (race_id, rank, runner_id, name, country, duration_ms, distance_m)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![race_id, e.rank, e.runner_id, e.name, e.country, e.finish_time_ms.map(|f| f - start_at), e.distance],
            )?;
        }
    }
    tx.commit()
}

/// 結算：標記關閉、通知房內所有人、成績落盤。
fn close_room(st: &AppState, room_id: &str, room: &mut Room, now: i64, reason: &str) {
    room.closed_at_ms = Some(now);
    let _ = room.tx.send(
        serde_json::to_string(&ServerMsg::RaceClosed {
            room_id: room_id.to_string(),
            closed_at_server_time: now,
            reason: reason.to_string(),
        })
        .unwrap(),
    );
    // 寫入失敗不擋結算：選手端與大螢幕照常收場
    if let Err(e) = save_results(&mut st.db.lock().unwrap(), room_id, room, now, reason) {
        eprintln!("成績寫入失敗 {room_id}：{e}");
    }
}

/// 每個距離取前幾名
const BEST_PER_DISTANCE: usize = 10;

/// `since` 之後各距離的最佳成績，每位選手只取最快的一次。
fn best_since(db: &Connection, since_ms: i64) -> rusqlite::Result<Value> {
    let mut stmt = db.prepare(
        "SELECT r.distance_m, s.runner_id, s.name, s.country, MIN(s.duration_ms) AS best
         FROM results s JOIN races r ON r.id = s.race_id
         WHERE s.duration_ms IS NOT NULL AND r.closed_at >= ?1
         GROUP BY r.distance_m, s.runner_id
         ORDER BY r.distance_m, best",
    )?;
    let rows = stmt.query_map([since_ms], |r| {
        Ok((r.get::<_, f64>(0)?, json!({
            "runnerId": r.get::<_, String>(1)?,
            "name": r.get::<_, String>(2)?,
            "country": r.get::<_, Option<String>>(3)?,
            "durationMs": r.get::<_, i64>(4)?,
        })))
    })?;
    let mut distances: Vec<(f64, Vec<Value>)> = Vec::new();
    for row in rows {
        let (distance_m, entry) = row?;
        if distances.last().is_none_or(|(d, _)| *d != distance_m) {
            distances.push((distance_m, Vec::new()));
        }
        let best = &mut distances.last_mut().unwrap().1;
        if best.len() < BEST_PER_DISTANCE {
            best.push(entry);
        }
    }
    Ok(json!({
        "distances": distances.into_iter()
            .map(|(d, best)| json!({ "distanceM": d, "best": best }))
            .collect::<Vec<_>>(),
    }))
}

#[tokio::main]
async fn main() {
    let admin_key = std::env::var("FITRACE_ADMIN_KEY").unwrap_or_else(|_| {
        let key = format!("{:032x}", rand::random::<u128>());
        println!("管理金鑰 {}", key);
        key
    });

    let db_path = std::env::var("FITRACE_DB")
        .unwrap_or_else(|_| concat!(env!("CARGO_MANIFEST_DIR"), "/fitrace.db").to_string());
    let db = open_db(&db_path).expect("無法開啟資料庫");
    let snap = load_snapshot(&db);
    let restored: Vec<String> = snap.rooms.keys().cloned().collect();

    let state = Arc::new(AppState {
        rooms: Mutex::new(snap.rooms),
        tokens: Mutex::new(HashMap::new()),
        room_counter: Mutex::new(snap.room_counter),
        rules: Mutex::new(snap.rules),
        rule_counter: Mutex::new(snap.rule_counter),
        admin_key,
        db: Mutex::new(db),
        settle_from_ms: now_ms() + RESUME_GRACE_MS,
    });
    println!("資料庫 {db_path}，還原 {} 個房間", restored.len());
    for room_id in restored {
        tokio::spawn(broadcast_loop(state.clone(), room_id));
    }

    // 啟動排程器
    tokio::spawn(scheduler_task(state.clone()));

    let app = Router::new()
        .route("/rooms", get(list_rooms))
        .route("/results/best", get(best_results))
        .route("/rooms/{room_id}/tokens", post(issue_token))
        .route("/rooms/{room_id}/runners/{runner_id}", delete(delete_runner))
        .route("/ws", get(ws_upgrade))
        .route("/display", get(display))
        .route("/admin", get(admin_page))
        .route("/admin/rooms", get(admin_list_rooms).post(admin_create_room))
        .route("/admin/rooms/{room_id}/start", post(admin_start_race))
        .route("/admin/rooms/{room_id}/end", post(admin_end_race))
        .route("/admin/rooms/{room_id}", delete(admin_delete_room))
        .route("/admin/rules", get(admin_list_rules).post(admin_create_rule))
        .route("/admin/rules/{rule_id}", delete(admin_delete_rule))
        .with_state(state);

    let port: u16 = std::env::var("FITRACE_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await.unwrap();
    println!("FitRace server  http://127.0.0.1:{port}");
    println!("大螢幕看板      http://127.0.0.1:{port}/display");
    axum::serve(listener, app).await.unwrap();
}

/// 由伺服器直接供應大螢幕頁面，省掉第二個靜態伺服器與 CORS；Phase 4 上雲後改由 CDN 託管。
async fn display() -> Result<Html<String>, StatusCode> {
    // 路徑在編譯期固定（不受 cwd 影響），內容在執行期讀取（改 HTML 不用重新編譯）
    const PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../web/display.html");
    tokio::fs::read_to_string(PATH)
        .await
        .map(Html)
        .map_err(|_| StatusCode::NOT_FOUND)
}

/// 供應管理頁面。
async fn admin_page() -> Result<Html<String>, StatusCode> {
    const PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../web/admin.html");
    tokio::fs::read_to_string(PATH)
        .await
        .map(Html)
        .map_err(|_| StatusCode::NOT_FOUND)
}

#[derive(Deserialize)]
struct SinceQuery {
    since: Option<i64>,
}

/// 大螢幕待機畫面用的最佳成績榜；`since` 由看板依當地時區帶入（例如今天零點）。
async fn best_results(
    Query(q): Query<SinceQuery>,
    State(st): State<Arc<AppState>>,
) -> Result<Json<Value>, StatusCode> {
    best_since(&st.db.lock().unwrap(), q.since.unwrap_or(0))
        .map(Json)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

#[derive(Deserialize)]
struct DeleteRunnerQuery {
    #[serde(rename = "deviceId")]
    device_id: Option<String>,
}

/// 取消選手報名（房間開放時可用）。
async fn delete_runner(
    Path((room_id, runner_id)): Path<(String, String)>,
    Query(q): Query<DeleteRunnerQuery>,
    State(st): State<Arc<AppState>>,
) -> Result<StatusCode, StatusCode> {
    let device_id = q.device_id.as_deref().unwrap_or("");
    let now = now_ms();

    let mut rooms = st.rooms.lock().unwrap();
    let room = rooms.get_mut(&room_id).ok_or(StatusCode::NOT_FOUND)?;

    let status = room_status(room.closed_at_ms, room.start_at_ms, now);
    if status != RoomStatus::Open {
        return Err(StatusCode::CONFLICT);
    }

    if let Some(runner) = room.runners.get(&runner_id) {
        if runner.device_id != device_id {
            return Err(StatusCode::FORBIDDEN);
        }
    } else {
        return Err(StatusCode::NOT_FOUND);
    }

    room.runners.remove(&runner_id);
    drop(rooms);
    st.tokens.lock().unwrap().retain(|_, info| !(info.room_id == room_id && info.runner_id == runner_id));
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TokenReq {
    runner_id: String,
    name: Option<String>,
    country: Option<String>,
    bib: Option<String>,
    avatar_url: Option<String>,
    device_id: Option<String>,
    role: Option<Role>,
    lane: Option<u32>,
    tier: Option<String>,
    bio: Option<String>,
    pr5k: Option<String>,
    target_pace: Option<String>,
    vo2_max: Option<f32>,
}

/// 大廳：列出所有房間與其狀態，選手端據此決定哪些比賽可以報名。
async fn list_rooms(State(st): State<Arc<AppState>>) -> Json<Value> {
    let now = now_ms();
    let rooms = st.rooms.lock().unwrap();
    let mut list: Vec<Value> = rooms
        .iter()
        .map(|(id, r)| {
            json!({
                "roomId": id,
                "title": r.title,
                "status": room_status(r.closed_at_ms, r.start_at_ms, now),
                "raceDistanceMeters": r.race_distance_m,
                "capacity": r.capacity,
                "runnerCount": r.runners.len(),
                "cutoffMs": r.cutoff_ms,
                "startAtServerTime": r.start_at_ms,
                "autoStartAtServerTime": r.auto_start_at_ms,
                "closedAtServerTime": r.closed_at_ms,
            })
        })
        .collect();
    list.sort_by(|a, b| a["roomId"].as_str().cmp(&b["roomId"].as_str()));
    Json(json!({ "serverTime": now, "rooms": list }))
}

/// 核發房間 Token（§3.3）。選手 Token 可寫，看板 Token 唯讀。
async fn issue_token(
    Path(room_id): Path<String>,
    State(st): State<Arc<AppState>>,
    Json(req): Json<TokenReq>,
) -> Result<Json<Value>, (StatusCode, Json<Value>)> {
    let err = |code: StatusCode, error: &str| (code, Json(json!({ "error": error })));
    let role = req.role.unwrap_or(Role::Runner);
    let now = now_ms();
    let runner_id = req.runner_id;
    if runner_id.trim().is_empty() || runner_id.chars().count() > MAX_NAME_CHARS {
        return Err(err(StatusCode::BAD_REQUEST, "INVALID_RUNNER_ID"));
    }
    // 空白名稱視同沒給，改用選手 ID 顯示
    let name = req.name.filter(|n| !n.trim().is_empty());
    if name.as_ref().is_some_and(|n| n.chars().count() > MAX_NAME_CHARS) {
        return Err(err(StatusCode::BAD_REQUEST, "INVALID_NAME"));
    }
    let device_id = req.device_id.as_deref().unwrap_or("").trim().to_string();
    if role == Role::Runner && device_id.is_empty() {
        return Err(err(StatusCode::BAD_REQUEST, "DEVICE_ID_REQUIRED"));
    }

    // rooms 鎖一路握到 Token 寫入：報名與發 Token 是同一個原子操作
    let mut rooms = st.rooms.lock().unwrap();
    // 一個選手 ID 同時只能在一場未結束的比賽中（§9.3）
    let busy_elsewhere = role == Role::Runner
        && rooms.iter().any(|(other_id, other)| {
            other_id != &room_id
                && other.runners.contains_key(&runner_id)
                && room_status(other.closed_at_ms, other.start_at_ms, now) != RoomStatus::Finished
        });
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| err(StatusCode::NOT_FOUND, "ROOM_NOT_FOUND"))?;

    if role == Role::Runner {
        if let Some(existing) = room.runners.get(&runner_id) {
            // 重連：只有原本報名的裝置能沿用
            if existing.device_id != device_id {
                return Err(err(StatusCode::CONFLICT, "RUNNER_ID_IN_USE"));
            }
        } else {
            // 新報名
            let status = room_status(room.closed_at_ms, room.start_at_ms, now);
            if status != RoomStatus::Open {
                return Err((
                    StatusCode::CONFLICT,
                    Json(json!({ "error": "REGISTRATION_CLOSED", "status": status })),
                ));
            }
            if room.runners.len() >= room.capacity as usize {
                return Err(err(StatusCode::CONFLICT, "ROOM_FULL"));
            }
            if busy_elsewhere {
                return Err(err(StatusCode::CONFLICT, "RUNNER_ID_IN_USE"));
            }

            let lane = req.lane.or_else(|| {
                let used: std::collections::HashSet<u32> =
                    room.runners.values().filter_map(|r| r.lane).collect();
                (1..=100).find(|l| !used.contains(l))
            });
            let name = name.unwrap_or_else(|| runner_id.clone());
            room.runners.insert(
                runner_id.clone(),
                RunnerState {
                    runner_id: runner_id.clone(),
                    name: name.clone(),
                    country: req.country.clone(),
                    bib: req.bib.clone(),
                    avatar_url: req.avatar_url.clone(),
                    device_id,
                    distance: 0.0,
                    pace: "--'--\"".to_string(),
                    speed_kmh: 0.0,
                    cadence: 0,
                    finish_time_ms: None,
                    lane,
                    tier: req.tier.clone(),
                    bio: req.bio.clone(),
                    pr5k: req.pr5k.clone(),
                    target_pace: req.target_pace.clone(),
                    vo2_max: req.vo2_max,
                },
            );
            let joined_msg = ServerMsg::RunnerJoined {
                room_id: room_id.clone(),
                runner_id: runner_id.clone(),
                name,
                country: req.country,
                bib: req.bib,
                avatar_url: req.avatar_url,
                lane,
                field_size: room.runners.len(),
                capacity: Some(room.capacity),
                tier: req.tier,
                bio: req.bio,
                pr5k: req.pr5k,
                target_pace: req.target_pace,
                vo2_max: req.vo2_max,
                server_time: now,
            };
            if let Ok(payload) = serde_json::to_string(&joined_msg) {
                let _ = room.tx.send(payload);
            }
        }
    }

    let token = format!("{:032x}", rand::random::<u128>());
    st.tokens.lock().unwrap().insert(
        token.clone(),
        TokenInfo { room_id: room_id.clone(), runner_id, role },
    );
    Ok(Json(json!({ "token": token, "roomId": room_id })))
}

/// 手動發令：僅 OPEN 且至少 1 人（§9.2）。
async fn start_race(
    Path(room_id): Path<String>,
    State(st): State<Arc<AppState>>,
) -> Result<Json<Value>, StatusCode> {
    let mut rooms = st.rooms.lock().unwrap();
    let room = rooms.get_mut(&room_id).ok_or(StatusCode::NOT_FOUND)?;

    let now = now_ms();
    let status = room_status(room.closed_at_ms, room.start_at_ms, now);
    if status != RoomStatus::Open || room.runners.is_empty() {
        return Err(StatusCode::CONFLICT);
    }

    let (start_at, cutoff_at) = room.schedule_start(&room_id, now);
    Ok(Json(json!({ "startAtServerTime": start_at, "raceDistanceMeters": room.race_distance_m, "cutoffAtServerTime": cutoff_at })))
}

#[derive(Deserialize)]
struct WsQuery {
    token: String,
}

use axum::http::HeaderMap;

fn check_admin_auth(st: &AppState, headers: &HeaderMap) -> Result<(), StatusCode> {
    let auth = headers
        .get("authorization")
        .and_then(|h| h.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;

    if !auth.starts_with("Bearer ") {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let key = &auth[7..];
    if key != st.admin_key {
        return Err(StatusCode::UNAUTHORIZED);
    }

    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AdminCreateRoomReq {
    title: String,
    distance_m: f64,
    capacity: Option<u32>,
    cutoff_minutes: Option<u32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AdminCreateRuleReq {
    title: String,
    distance_m: f64,
    capacity: Option<u32>,
    cutoff_minutes: Option<u32>,
    min_runners: Option<u32>,
    wait_seconds: Option<u32>,
}

/// 管理員列出所有房間與選手。
async fn admin_list_rooms(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, StatusCode> {
    check_admin_auth(&st, &headers)?;

    let now = now_ms();
    let rooms = st.rooms.lock().unwrap();
    let mut list: Vec<Value> = rooms
        .iter()
        .map(|(id, r)| {
            let entrants: Vec<Value> = rank(&r.runners.values().cloned().collect::<Vec<_>>(), r.race_distance_m, r.closed_at_ms.is_some())
                .into_iter()
                .map(|e| json!({
                    "runnerId": e.runner_id,
                    "name": e.name,
                    "country": e.country,
                    "distance": e.distance,
                    "status": e.status,
                    "finishTimeMs": e.finish_time_ms,
                }))
                .collect();
            json!({
                "roomId": id,
                "title": r.title,
                "status": room_status(r.closed_at_ms, r.start_at_ms, now),
                "raceDistanceMeters": r.race_distance_m,
                "capacity": r.capacity,
                "runnerCount": r.runners.len(),
                "cutoffMs": r.cutoff_ms,
                "startAtServerTime": r.start_at_ms,
                "autoStartAtServerTime": r.auto_start_at_ms,
                "closedAtServerTime": r.closed_at_ms,
                "entrants": entrants,
            })
        })
        .collect();
    list.sort_by(|a, b| a["roomId"].as_str().cmp(&b["roomId"].as_str()));
    Ok(Json(json!({ "serverTime": now, "rooms": list })))
}

/// 管理員建立房間。
async fn admin_create_room(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(req): Json<AdminCreateRoomReq>,
) -> Result<(StatusCode, Json<Value>), StatusCode> {
    check_admin_auth(&st, &headers)?;

    if !(100.0..=50_000.0).contains(&req.distance_m)
        || req.capacity.is_some_and(|c| !(1..=100).contains(&c))
        || req.cutoff_minutes.is_some_and(|c| !(1..=300).contains(&c))
    {
        return Err(StatusCode::BAD_REQUEST);
    }

    let cutoff_minutes = req.cutoff_minutes
        .unwrap_or_else(|| fitrace_live::default_cutoff_minutes(req.distance_m));
    let cutoff_ms = (cutoff_minutes as i64) * 60 * 1000;

    let capacity = req.capacity.unwrap_or(DEFAULT_CAPACITY);
    let room_id = create_room(&st, req.title.clone(), req.distance_m, capacity, cutoff_ms);

    let now = now_ms();
    let rooms = st.rooms.lock().unwrap();
    let room = &rooms[&room_id];

    Ok((StatusCode::CREATED, Json(json!({
        "roomId": &room_id,
        "title": room.title,
        "status": room_status(room.closed_at_ms, room.start_at_ms, now),
        "raceDistanceMeters": room.race_distance_m,
        "capacity": room.capacity,
        "runnerCount": 0,
        "cutoffMs": room.cutoff_ms,
        "startAtServerTime": room.start_at_ms,
    }))))
}

/// 管理員發起比賽。
async fn admin_start_race(
    Path(room_id): Path<String>,
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, StatusCode> {
    check_admin_auth(&st, &headers)?;
    start_race(Path(room_id), State(st)).await
}

/// 管理員結束比賽。
async fn admin_end_race(
    Path(room_id): Path<String>,
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<StatusCode, StatusCode> {
    check_admin_auth(&st, &headers)?;

    let now = now_ms();
    let mut rooms = st.rooms.lock().unwrap();
    let room = rooms.get_mut(&room_id).ok_or(StatusCode::NOT_FOUND)?;

    let status = room_status(room.closed_at_ms, room.start_at_ms, now);
    if status != RoomStatus::Starting && status != RoomStatus::Running {
        return Err(StatusCode::CONFLICT);
    }

    close_room(&st, &room_id, room, now, "ORGANIZER");

    Ok(StatusCode::NO_CONTENT)
}

/// 管理員刪除房間。
async fn admin_delete_room(
    Path(room_id): Path<String>,
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<StatusCode, StatusCode> {
    check_admin_auth(&st, &headers)?;

    let now = now_ms();
    let mut rooms = st.rooms.lock().unwrap();
    let room = rooms.get(&room_id).ok_or(StatusCode::NOT_FOUND)?;

    let status = room_status(room.closed_at_ms, room.start_at_ms, now);
    if status == RoomStatus::Starting || status == RoomStatus::Running {
        return Err(StatusCode::CONFLICT);
    }

    if status == RoomStatus::Open {
        let room = &rooms[&room_id];
        let _ = room.tx.send(
            serde_json::to_string(&ServerMsg::RaceCancelled {
                room_id: room_id.clone(),
            })
            .unwrap(),
        );
    }

    rooms.remove(&room_id);
    drop(rooms);
    st.tokens.lock().unwrap().retain(|_, info| info.room_id != room_id);
    Ok(StatusCode::NO_CONTENT)
}

/// 管理員列出所有規則。
async fn admin_list_rules(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, StatusCode> {
    check_admin_auth(&st, &headers)?;

    let rules = st.rules.lock().unwrap();
    let mut list: Vec<Value> = rules
        .iter()
        .map(|(id, r)| json!({
            "ruleId": id,
            "title": r.title,
            "distanceM": r.distance_m,
            "capacity": r.capacity,
            "cutoffMinutes": r.cutoff_minutes,
            "minRunners": r.min_runners,
            "waitSeconds": r.wait_seconds,
        }))
        .collect();
    list.sort_by(|a, b| a["ruleId"].as_str().cmp(&b["ruleId"].as_str()));
    Ok(Json(json!({ "rules": list })))
}

/// 管理員建立規則。
async fn admin_create_rule(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(req): Json<AdminCreateRuleReq>,
) -> Result<(StatusCode, Json<Value>), StatusCode> {
    check_admin_auth(&st, &headers)?;

    if !(100.0..=50_000.0).contains(&req.distance_m)
        || req.capacity.is_some_and(|c| !(1..=100).contains(&c))
        || req.cutoff_minutes.is_some_and(|c| !(1..=300).contains(&c))
        || req.min_runners.is_some_and(|c| !(1..=100).contains(&c))
        || req.wait_seconds.is_some_and(|c| c > 3600)
    {
        return Err(StatusCode::BAD_REQUEST);
    }

    let cutoff_minutes = req.cutoff_minutes
        .unwrap_or_else(|| fitrace_live::default_cutoff_minutes(req.distance_m));
    let min_runners = req.min_runners.unwrap_or(1);
    let wait_seconds = req.wait_seconds.unwrap_or(60);

    let mut counter = st.rule_counter.lock().unwrap();
    *counter += 1;
    let rule_id = format!("A{:02}", counter);

    let rule = Rule {
        title: req.title,
        distance_m: req.distance_m,
        capacity: req.capacity.unwrap_or(DEFAULT_CAPACITY),
        cutoff_minutes,
        min_runners,
        wait_seconds,
    };

    st.rules.lock().unwrap().insert(rule_id.clone(), rule.clone());

    Ok((StatusCode::CREATED, Json(json!({
        "ruleId": rule_id,
        "title": rule.title,
        "distanceM": rule.distance_m,
        "capacity": rule.capacity,
        "cutoffMinutes": rule.cutoff_minutes,
        "minRunners": rule.min_runners,
        "waitSeconds": rule.wait_seconds,
    }))))
}

/// 管理員刪除規則。
async fn admin_delete_rule(
    Path(rule_id): Path<String>,
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<StatusCode, StatusCode> {
    check_admin_auth(&st, &headers)?;

    st.rules.lock().unwrap().remove(&rule_id)
        .ok_or(StatusCode::NOT_FOUND)?;

    // 規則沒了，它名下還在等人的房間不會再有人發令，一併取消
    let now = now_ms();
    let mut rooms = st.rooms.lock().unwrap();
    let orphans: Vec<String> = rooms
        .iter()
        .filter(|(_, r)| {
            r.rule_id.as_deref() == Some(rule_id.as_str())
                && room_status(r.closed_at_ms, r.start_at_ms, now) == RoomStatus::Open
        })
        .map(|(id, _)| id.clone())
        .collect();
    for room_id in &orphans {
        if let Some(room) = rooms.remove(room_id) {
            let _ = room.tx.send(
                serde_json::to_string(&ServerMsg::RaceCancelled { room_id: room_id.clone() }).unwrap(),
            );
        }
    }
    drop(rooms);
    st.tokens.lock().unwrap().retain(|_, info| !orphans.contains(&info.room_id));

    Ok(StatusCode::NO_CONTENT)
}

/// 排程器：自動管理根據規則開啟的房間。
async fn scheduler_task(st: Arc<AppState>) {
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    let mut last_snapshot = String::new();
    loop {
        tick.tick().await;
        let now = now_ms();
        persist(&st, &mut last_snapshot);

        // 為每條規則確保有一個Open房間
        {
            let rules = st.rules.lock().unwrap();
            for (rule_id, rule) in rules.iter() {
                let has_open = {
                    let rooms = st.rooms.lock().unwrap();
                    rooms.values().any(|r| {
                        r.rule_id.as_ref() == Some(rule_id)
                            && room_status(r.closed_at_ms, r.start_at_ms, now) == RoomStatus::Open
                    })
                };

                if !has_open {
                    let room_count = {
                        let rooms = st.rooms.lock().unwrap();
                        rooms.values()
                            .filter(|r| r.rule_id.as_ref() == Some(rule_id))
                            .count()
                    };
                    let title = format!("{} #{}", rule.title, room_count + 1);
                    let cutoff_ms = (rule.cutoff_minutes as i64) * 60 * 1000;
                    let room_id = create_room(&st, title, rule.distance_m, rule.capacity, cutoff_ms);
                    st.rooms.lock().unwrap().get_mut(&room_id).unwrap().rule_id = Some(rule_id.clone());
                }
            }
        }

        // 檢查每個由規則管理的 OPEN 房間是否該發令。
        // 鎖的順序與上方一致（先 rules 後 rooms），避免死結。
        let rules = st.rules.lock().unwrap();
        let mut rooms = st.rooms.lock().unwrap();
        for (room_id, room) in rooms.iter_mut() {
            let Some(rule) = room.rule_id.as_ref().and_then(|id| rules.get(id)) else {
                continue;
            };
            if room_status(room.closed_at_ms, room.start_at_ms, now) != RoomStatus::Open {
                continue;
            }
            let decision = auto_start(
                room.runners.len() as u32,
                rule.min_runners,
                room.capacity,
                rule.wait_seconds as i64 * 1000,
                room.auto_start_at_ms,
                now,
            );
            match decision {
                AutoStart::Hold(at) => room.auto_start_at_ms = at,
                AutoStart::Go => {
                    room.schedule_start(room_id, now);
                }
            }
        }
    }
}

async fn ws_upgrade(
    ws: WebSocketUpgrade,
    Query(q): Query<WsQuery>,
    State(st): State<Arc<AppState>>,
) -> Result<Response, StatusCode> {
    // ponytail: Token 不設單次失效，否則 §5.2 的自動重連無法沿用同一 Token；上雲前補 TTL
    let (room_id, runner_id, role) = {
        let tokens = st.tokens.lock().unwrap();
        let info = tokens.get(&q.token).ok_or(StatusCode::UNAUTHORIZED)?;
        (info.room_id.clone(), info.runner_id.clone(), info.role)
    };
    Ok(ws.on_upgrade(move |sock| handle_socket(sock, st, room_id, runner_id, role)))
}

async fn handle_socket(sock: WebSocket, st: Arc<AppState>, room_id: String, runner_id: String, role: Role) {
    let (mut sink, mut stream) = sock.split();

    let (mut brx, scheduled) = {
        let rooms = st.rooms.lock().unwrap();
        let Some(room) = rooms.get(&room_id) else {
            return;
        };
        if role == Role::Runner && !room.runners.contains_key(&runner_id) {
            return;
        }
        (room.tx.subscribe(), room.start_at_ms.map(|t| (t, room.race_distance_m, room.cutoff_at(t))))
    };

    // 已排程就補發，讓重連或晚進場的看板端跟上同一個起跑時間
    if let Some((start_at, distance, cutoff_at)) = scheduled {
        let msg = serde_json::to_string(&ServerMsg::RaceScheduled {
            room_id: room_id.clone(),
            start_at_server_time: start_at,
            race_distance_meters: distance,
            cutoff_at_server_time: cutoff_at,
        })
        .unwrap();
        if sink.send(Message::text(msg)).await.is_err() {
            return;
        }
    }

    loop {
        tokio::select! {
            out = brx.recv() => match out {
                Ok(txt) => {
                    if sink.send(Message::text(txt)).await.is_err() { break; }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            },
            inbound = stream.next() => {
                let Some(Ok(msg)) = inbound else { break };
                let Message::Text(txt) = msg else { continue };
                match serde_json::from_str::<ClientMsg>(&txt) {
                    Ok(ClientMsg::Ping { client_send_time }) => {
                        let pong = serde_json::to_string(&ServerMsg::Pong {
                            client_send_time,
                            server_time: now_ms(),
                        }).unwrap();
                        if sink.send(Message::text(pong)).await.is_err() { break; }
                    }
                    Ok(ClientMsg::Telemetry(t)) => {
                        // 信任邊界：只接受與 Token 綁定的 runnerId/roomId，其餘一律丟棄
                        if role != Role::Runner || t.runner_id != runner_id || t.room_id != room_id {
                            continue;
                        }
                        let now = now_ms();
                        let mut rooms = st.rooms.lock().unwrap();
                        if let Some(room) = rooms.get_mut(&room_id) {
                            // 房間已關閉時忽略遙測
                            if room.closed_at_ms.is_some() {
                                continue;
                            }
                            let Some(t) = vet_telemetry(t, room.start_at_ms, now) else {
                                continue;
                            };
                            let distance = room.race_distance_m;
                            if let Some(state) = room.runners.get_mut(&runner_id) {
                                state.apply(&t, distance);
                            }
                        }
                    }
                    Err(_) => continue,
                }
            }
        }
    }
    // ponytail: 斷線不移除選手，其最後狀態留在榜上（等同「掉線凍結」）
}

fn create_room(st: &Arc<AppState>, title: String, distance_m: f64, capacity: u32, cutoff_ms: i64) -> String {
    let mut counter = st.room_counter.lock().unwrap();
    *counter += 1;
    let room_id = format!("R{:04}", counter);

    let mut rooms = st.rooms.lock().unwrap();
    rooms.insert(
        room_id.clone(),
        Room {
            title,
            tx: room_channel(),
            runners: HashMap::new(),
            start_at_ms: None,
            race_distance_m: distance_m,
            capacity,
            cutoff_ms,
            closed_at_ms: None,
            auto_start_at_ms: None,
            rule_id: None,
            finish_countdown_cutoff_ms: None,
        },
    );
    drop(rooms);
    tokio::spawn(broadcast_loop(st.clone(), room_id.clone()));
    room_id
}

/// 定頻廣播榜單（§3.4），並處理比賽結算。
async fn broadcast_loop(st: Arc<AppState>, room_id: String) {
    let mut tick = tokio::time::interval(Duration::from_millis(BROADCAST_INTERVAL_MS));
    loop {
        tick.tick().await;
        let now = now_ms();

        let mut rooms = st.rooms.lock().unwrap();
        let Some(room) = rooms.get_mut(&room_id) else {
            return;
        };

        // 比賽進行中才需要結算判定；剛重啟時先等選手端重連
        let running_since = room
            .start_at_ms
            .filter(|&t| t <= now && room.closed_at_ms.is_none() && now >= st.settle_from_ms);
        if let Some(start_at) = running_since {
            let finished = room.runners.values().filter(|r| r.finish_time_ms.is_some()).count();
            let cutoff_at = start_at + room.cutoff_ms;
            match settle(finished, room.runners.len(), cutoff_at, room.finish_countdown_cutoff_ms, now) {
                Settlement::Continue => {}
                Settlement::StartSprint(sprint_cutoff) => {
                    room.finish_countdown_cutoff_ms = Some(sprint_cutoff);
                    let _ = room.tx.send(
                        serde_json::to_string(&ServerMsg::RaceScheduled {
                            room_id: room_id.clone(),
                            start_at_server_time: start_at,
                            race_distance_meters: room.race_distance_m,
                            cutoff_at_server_time: sprint_cutoff,
                        })
                        .unwrap(),
                    );
                }
                Settlement::Close(reason) => close_room(&st, &room_id, room, now, reason),
            }
        }

        // 移除10分鐘前已關閉的房間
        if room.closed_at_ms.is_some_and(|t| now >= t + 10 * 60 * 1000) {
            rooms.remove(&room_id);
            drop(rooms);
            st.tokens.lock().unwrap().retain(|_, info| info.room_id != room_id);
            return;
        }

        if room.tx.receiver_count() == 0 {
            continue;
        }

        let snapshot: Vec<RunnerState> = room.runners.values().cloned().collect();
        let is_closed = room.closed_at_ms.is_some();
        let payload = serde_json::to_string(&ServerMsg::LeaderboardUpdate(Leaderboard {
            room_id: room_id.clone(),
            server_time: now,
            rankings: rank(&snapshot, room.race_distance_m, is_closed),
        }))
        .unwrap();
        let _ = room.tx.send(payload);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn room(start_at: i64, runners: &[(&str, Option<i64>)]) -> Room {
        let runners = runners.iter().map(|&(id, finish_time_ms)| {
            (id.to_string(), RunnerState {
                runner_id: id.to_string(),
                name: id.to_string(),
                country: None,
                bib: None,
                avatar_url: None,
                device_id: format!("dev-{id}"),
                distance: if finish_time_ms.is_some() { 1000.0 } else { 400.0 },
                pace: "04'00\"".to_string(),
                speed_kmh: 15.0,
                cadence: 180,
                finish_time_ms,
                lane: None,
                tier: None,
                bio: None,
                pr5k: None,
                target_pace: None,
                vo2_max: None,
            })
        });
        Room {
            title: "Test".to_string(),
            tx: room_channel(),
            runners: runners.collect(),
            start_at_ms: Some(start_at),
            race_distance_m: 1000.0,
            capacity: DEFAULT_CAPACITY,
            cutoff_ms: 600_000,
            closed_at_ms: None,
            auto_start_at_ms: None,
            rule_id: None,
            finish_countdown_cutoff_ms: None,
        }
    }

    #[test]
    fn results_are_saved_once_and_best_is_per_runner() {
        let mut db = open_db(":memory:").unwrap();
        let first = room(1_000, &[("A", Some(71_000)), ("B", Some(81_000)), ("C", None)]);
        save_results(&mut db, "R0001", &first, 100_000, "CUTOFF").unwrap();
        // 重啟後同一場再結算一次，不能多出第二份成績
        save_results(&mut db, "R0001", &first, 100_500, "CUTOFF").unwrap();
        save_results(&mut db, "R0002", &room(200_000, &[("A", Some(265_000))]), 300_000, "ALL_FINISHED").unwrap();
        let rows: i64 = db.query_row("SELECT COUNT(*) FROM results", [], |r| r.get(0)).unwrap();
        assert_eq!(rows, 4);

        // A 取較快的那次（65s），C 沒完賽不上榜
        let best = best_since(&db, 0).unwrap();
        let list: Vec<(&str, i64)> = best["distances"][0]["best"].as_array().unwrap().iter()
            .map(|e| (e["runnerId"].as_str().unwrap(), e["durationMs"].as_i64().unwrap()))
            .collect();
        assert_eq!(best["distances"][0]["distanceM"], 1000.0);
        assert_eq!(list, vec![("A", 65_000), ("B", 80_000)]);

        // since 之前結算的場次不算
        let later = best_since(&db, 200_000).unwrap();
        assert_eq!(later["distances"][0]["best"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn snapshot_restores_state_and_tolerates_garbage() {
        let db = open_db(":memory:").unwrap();
        assert!(load_snapshot(&db).rooms.is_empty());

        let mut rooms = HashMap::new();
        rooms.insert("R0007".to_string(), room(1_000, &[("A", Some(71_000)), ("B", None)]));
        let json = json!({ "rooms": rooms, "room_counter": 7 }).to_string();
        db.execute("INSERT INTO state (id, json) VALUES (1, ?1)", [&json]).unwrap();

        let snap = load_snapshot(&db);
        let restored = &snap.rooms["R0007"];
        assert_eq!((snap.room_counter, snap.rule_counter), (7, 0));
        assert_eq!(restored.start_at_ms, Some(1_000));
        assert_eq!(restored.runners["A"].finish_time_ms, Some(71_000));
        assert_eq!(restored.runners["B"].device_id, "dev-B");

        // 壞掉的快照不能讓伺服器起不來
        db.execute("UPDATE state SET json = '{\"rooms\": 42}'", []).unwrap();
        assert!(load_snapshot(&db).rooms.is_empty());
    }
}
