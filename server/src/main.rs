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
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::broadcast;

const BROADCAST_INTERVAL_MS: u64 = 400; // 2.5Hz（§3.4）
const COUNTDOWN_MS: i64 = 5000; // §3.2

#[derive(Debug, Clone, Copy, PartialEq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Role {
    Runner,
    Display,
}

struct TokenInfo {
    room_id: String,
    runner_id: String,
    name: String,
    country: Option<String>,
    bib: Option<String>,
    avatar_url: Option<String>,
    device_id: String,
    role: Role,
}

struct Room {
    title: String,
    tx: broadcast::Sender<String>,
    runners: HashMap<String, RunnerState>,
    start_at_ms: Option<i64>,
    race_distance_m: f64,
    capacity: Option<u32>,
    cutoff_ms: i64,
    closed_at_ms: Option<i64>,
    auto_start_at_ms: Option<i64>,
    rule_id: Option<String>,
}

#[derive(Default)]
struct AppState {
    // ponytail: 單一全域鎖，本機單場館夠用；同時開大量房間有鎖競爭時再改 per-room 鎖
    rooms: Mutex<HashMap<String, Room>>,
    tokens: Mutex<HashMap<String, TokenInfo>>,
    room_counter: Mutex<u32>,
    rules: Mutex<HashMap<String, Rule>>,
    rule_counter: Mutex<u32>,
    admin_key: String,
}

#[derive(Debug, Clone)]
struct Rule {
    title: String,
    distance_m: f64,
    capacity: Option<u32>,
    cutoff_minutes: u32,
    min_runners: u32,
    wait_seconds: u32,
}

#[tokio::main]
async fn main() {
    let admin_key = std::env::var("FITRACE_ADMIN_KEY").unwrap_or_else(|_| {
        let key = format!("{:032x}", rand::random::<u128>());
        println!("管理金鑰 {}", key);
        key
    });

    let state = Arc::new(AppState {
        rooms: Mutex::new(HashMap::new()),
        tokens: Mutex::new(HashMap::new()),
        room_counter: Mutex::new(0),
        rules: Mutex::new(HashMap::new()),
        rule_counter: Mutex::new(0),
        admin_key,
    });

    // 啟動排程器
    tokio::spawn(scheduler_task(state.clone()));

    let app = Router::new()
        .route("/rooms", get(list_rooms))
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

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080").await.unwrap();
    println!("FitRace server  http://127.0.0.1:8080");
    println!("大螢幕看板      http://127.0.0.1:8080/display?room=R0001");
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
    let role = req.role.unwrap_or(Role::Runner);
    let now = now_ms();
    let runner_id = req.runner_id.clone();
    let name = req.name.clone().unwrap_or_else(|| runner_id.clone());
    let device_id_opt = req.device_id.clone();

    if role == Role::Runner {
        let device_id = device_id_opt.as_deref().unwrap_or("").trim();
        if device_id.is_empty() {
            return Err((
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "DEVICE_ID_REQUIRED" })),
            ));
        }

        let rooms = st.rooms.lock().unwrap();
        let room = rooms.get(&room_id).ok_or_else(|| (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "ROOM_NOT_FOUND" })),
        ))?;

        // 檢查此選手是否已在此房間
        if let Some(existing) = room.runners.get(&runner_id) {
            if existing.device_id != device_id {
                return Err((
                    StatusCode::CONFLICT,
                    Json(json!({ "error": "RUNNER_ID_IN_USE" })),
                ));
            }
            // 同設備重新入場：OK，在下面發 Token
        } else {
            // 新報名：檢查房間狀態
            let status = room_status(room.closed_at_ms, room.start_at_ms, now);
            if status != RoomStatus::Open {
                return Err((
                    StatusCode::CONFLICT,
                    Json(json!({ "error": "REGISTRATION_CLOSED", "status": status })),
                ));
            }

            // 檢查容量
            if let Some(cap) = room.capacity {
                if room.runners.len() >= cap as usize {
                    return Err((
                        StatusCode::CONFLICT,
                        Json(json!({ "error": "ROOM_FULL" })),
                    ));
                }
            }

            // 檢查選手是否在其他非完賽房間
            let in_other_room = rooms.iter().any(|(other_id, other_room)| {
                other_id != &room_id
                    && {
                        let other_status = room_status(other_room.closed_at_ms, other_room.start_at_ms, now);
                        other_status != RoomStatus::Finished && other_room.runners.contains_key(&runner_id)
                    }
            });

            if in_other_room {
                return Err((
                    StatusCode::CONFLICT,
                    Json(json!({ "error": "RUNNER_ID_IN_USE" })),
                ));
            }

            // 新增選手到房間
            drop(rooms);
            let mut rooms = st.rooms.lock().unwrap();
            rooms.get_mut(&room_id).unwrap().runners.insert(
                runner_id.clone(),
                RunnerState {
                    runner_id: runner_id.clone(),
                    name: name.clone(),
                    country: req.country.clone(),
                    bib: req.bib.clone(),
                    avatar_url: req.avatar_url.clone(),
                    device_id: device_id.to_string(),
                    distance: 0.0,
                    pace: "--'--\"".to_string(),
                    speed_kmh: 0.0,
                    cadence: 0,
                    finish_time_ms: None,
                },
            );
        }
    } else {
        // DISPLAY token：房間必須存在
        let rooms = st.rooms.lock().unwrap();
        rooms.get(&room_id).ok_or_else(|| (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "ROOM_NOT_FOUND" })),
        ))?;
    }

    let token = format!("{:032x}", rand::random::<u128>());
    st.tokens.lock().unwrap().insert(
        token.clone(),
        TokenInfo {
            room_id: room_id.clone(),
            runner_id,
            name,
            country: req.country,
            bib: req.bib,
            avatar_url: req.avatar_url,
            device_id: device_id_opt.unwrap_or_default(),
            role,
        },
    );
    Ok(Json(json!({ "token": token, "roomId": room_id })))
}

/// 排程起跑（§3.2）：T_start = ServerNow + 5000ms，並重置上一場成績。
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

    let start_at = now + COUNTDOWN_MS;
    let cutoff_at = start_at + room.cutoff_ms;
    room.start_at_ms = Some(start_at);
    room.auto_start_at_ms = None;
    for r in room.runners.values_mut() {
        r.distance = 0.0;
        r.pace = "--'--\"".to_string();
        r.speed_kmh = 0.0;
        r.cadence = 0;
        r.finish_time_ms = None;
    }
    let distance = room.race_distance_m;
    let _ = room.tx.send(
        serde_json::to_string(&ServerMsg::RaceScheduled {
            room_id: room_id.clone(),
            start_at_server_time: start_at,
            race_distance_meters: distance,
            cutoff_at_server_time: cutoff_at,
        })
        .unwrap(),
    );
    Ok(Json(json!({ "startAtServerTime": start_at, "raceDistanceMeters": distance, "cutoffAtServerTime": cutoff_at })))
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
        || req.capacity.map_or(false, |c| !(1..=100).contains(&c))
        || req.cutoff_minutes.map_or(false, |c| !(1..=300).contains(&c))
    {
        return Err(StatusCode::BAD_REQUEST);
    }

    let cutoff_minutes = req.cutoff_minutes
        .unwrap_or_else(|| fitrace_live::default_cutoff_minutes(req.distance_m));
    let cutoff_ms = (cutoff_minutes as i64) * 60 * 1000;

    let room_id = create_room(&st, req.title.clone(), req.distance_m, req.capacity, cutoff_ms);

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

    room.closed_at_ms = Some(now);
    let _ = room.tx.send(
        serde_json::to_string(&ServerMsg::RaceClosed {
            room_id: room_id.clone(),
            closed_at_server_time: now,
            reason: "ORGANIZER".to_string(),
        })
        .unwrap(),
    );

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
        || req.capacity.map_or(false, |c| !(1..=100).contains(&c))
        || req.cutoff_minutes.map_or(false, |c| !(1..=300).contains(&c))
        || req.min_runners.map_or(false, |c| !(1..=100).contains(&c))
        || req.wait_seconds.map_or(false, |c| c > 3600)
    {
        return Err(StatusCode::BAD_REQUEST);
    }

    let cutoff_minutes = req.cutoff_minutes
        .unwrap_or_else(|| fitrace_live::default_cutoff_minutes(req.distance_m));
    let min_runners = req.min_runners.unwrap_or(2);
    let wait_seconds = req.wait_seconds.unwrap_or(60);

    let mut counter = st.rule_counter.lock().unwrap();
    *counter += 1;
    let rule_id = format!("A{:02}", counter);

    let rule = Rule {
        title: req.title,
        distance_m: req.distance_m,
        capacity: req.capacity,
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

    Ok(StatusCode::NO_CONTENT)
}

/// 排程器：自動管理根據規則開啟的房間。
async fn scheduler_task(st: Arc<AppState>) {
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    loop {
        tick.tick().await;
        let now = now_ms();

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

        // 檢查每個由規則管理的 OPEN 房間，分三步：人數不足就撤銷排程、
        // 人數到了才排定開跑時間、額滿或時間到才真正發令。
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
            let count = room.runners.len() as u32;
            if count < rule.min_runners {
                room.auto_start_at_ms = None;
                continue;
            }
            let full = room.capacity.is_some_and(|cap| count >= cap);
            let due = room.auto_start_at_ms.is_some_and(|t| t <= now);
            if room.auto_start_at_ms.is_none() && !full {
                room.auto_start_at_ms = Some(now + rule.wait_seconds as i64 * 1000);
                continue;
            }
            if !(full || due) {
                continue;
            }
            let start_at = now + COUNTDOWN_MS;
            room.start_at_ms = Some(start_at);
            room.auto_start_at_ms = None;
            let _ = room.tx.send(
                serde_json::to_string(&ServerMsg::RaceScheduled {
                    room_id: room_id.clone(),
                    start_at_server_time: start_at,
                    race_distance_meters: room.race_distance_m,
                    cutoff_at_server_time: start_at + room.cutoff_ms,
                })
                .unwrap(),
            );
        }
    }
}

async fn ws_upgrade(
    ws: WebSocketUpgrade,
    Query(q): Query<WsQuery>,
    State(st): State<Arc<AppState>>,
) -> Result<Response, StatusCode> {
    // ponytail: Token 不設單次失效，否則 §5.2 的自動重連無法沿用同一 Token；上雲前補 TTL
    let (room_id, role, seed) = {
        let tokens = st.tokens.lock().unwrap();
        let info = tokens.get(&q.token).ok_or(StatusCode::UNAUTHORIZED)?;
        (
            info.room_id.clone(),
            info.role,
            RunnerState {
                runner_id: info.runner_id.clone(),
                name: info.name.clone(),
                country: info.country.clone(),
                bib: info.bib.clone(),
                avatar_url: info.avatar_url.clone(),
                device_id: info.device_id.clone(),
                distance: 0.0,
                pace: "--'--\"".to_string(),
                speed_kmh: 0.0,
                cadence: 0,
                finish_time_ms: None,
            },
        )
    };
    Ok(ws.on_upgrade(move |sock| handle_socket(sock, st, room_id, role, seed)))
}

async fn handle_socket(sock: WebSocket, st: Arc<AppState>, room_id: String, role: Role, seed: RunnerState) {
    let runner_id = seed.runner_id.clone();
    let (mut sink, mut stream) = sock.split();

    let (mut brx, scheduled) = {
        let rooms = st.rooms.lock().unwrap();
        let Some(room) = rooms.get(&room_id) else {
            return;
        };
        if role == Role::Runner && !room.runners.contains_key(&runner_id) {
            return;
        }
        let cutoff = room.closed_at_ms.is_none().then_some(room.cutoff_ms).unwrap_or(0);
        (room.tx.subscribe(), room.start_at_ms.map(|t| (t, room.race_distance_m, t + cutoff)))
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
                        let mut rooms = st.rooms.lock().unwrap();
                        if let Some(room) = rooms.get_mut(&room_id) {
                            // 房間已關閉時忽略遙測
                            if room.closed_at_ms.is_some() {
                                continue;
                            }
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

fn create_room(st: &Arc<AppState>, title: String, distance_m: f64, capacity: Option<u32>, cutoff_ms: i64) -> String {
    let mut counter = st.room_counter.lock().unwrap();
    *counter += 1;
    let room_id = format!("R{:04}", counter);

    let mut rooms = st.rooms.lock().unwrap();
    let (tx, _) = broadcast::channel(64);
    rooms.insert(
        room_id.clone(),
        Room {
            title,
            tx,
            runners: HashMap::new(),
            start_at_ms: None,
            race_distance_m: distance_m,
            capacity,
            cutoff_ms,
            closed_at_ms: None,
            auto_start_at_ms: None,
            rule_id: None,
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

        {
            let mut rooms = st.rooms.lock().unwrap();
            let Some(room) = rooms.get_mut(&room_id) else {
                return;
            };

            // 檢查是否需要結算：房間未關閉且比賽已開始
            if room.closed_at_ms.is_none() && room.start_at_ms.is_some() {
                let status = room_status(room.closed_at_ms, room.start_at_ms, now);
                let should_close = match status {
                    RoomStatus::Running => {
                        // 全員完賽或超過Cutoff時間
                        let all_finished = !room.runners.is_empty() &&
                            room.runners.values().all(|r| r.finish_time_ms.is_some());
                        let cutoff_reached = room.start_at_ms.is_some() &&
                            now >= room.start_at_ms.unwrap() + room.cutoff_ms;
                        all_finished || cutoff_reached
                    }
                    _ => false,
                };

                if should_close {
                    room.closed_at_ms = Some(now);
                    let reason = if !room.runners.is_empty() &&
                        room.runners.values().all(|r| r.finish_time_ms.is_some()) {
                        "ALL_FINISHED"
                    } else {
                        "CUTOFF"
                    };
                    let _ = room.tx.send(
                        serde_json::to_string(&ServerMsg::RaceClosed {
                            room_id: room_id.clone(),
                            closed_at_server_time: now,
                            reason: reason.to_string(),
                        })
                        .unwrap(),
                    );
                }
            }
        }

        let mut rooms = st.rooms.lock().unwrap();
        let Some(room) = rooms.get_mut(&room_id) else {
            return;
        };

        // 移除10分鐘前已關閉的房間
        if let Some(closed_at) = room.closed_at_ms {
            if now >= closed_at + 10 * 60 * 1000 {
                drop(rooms);
                st.rooms.lock().unwrap().remove(&room_id);
                return;
            }
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
