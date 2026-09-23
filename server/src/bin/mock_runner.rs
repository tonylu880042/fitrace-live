//! 假選手：Phase 1-2 唯一的數據來源，同時是 Android 端（Phase 3）校時與撞線邏輯的參考實作。
//!
//! 用法：mock_runner <roomId> <runnerId> [name] [paceSecPerKm]
//!   cargo run --bin mock_runner -- ROOM_101 RUNNER_01 "Alex Chen" 240
use fitrace_live::*;
use futures_util::{SinkExt, StreamExt};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_tungstenite::tungstenite::Message;

const HOST: &str = "127.0.0.1:8080";
const SYNC_SAMPLES: usize = 5; // §3.1
const REPORT_INTERVAL_MS: u64 = 400; // 2~3Hz（§2.2-4）

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let room_id = args.get(1).cloned().unwrap_or_else(|| "ROOM_101".into());
    let runner_id = args.get(2).cloned().unwrap_or_else(|| "RUNNER_01".into());
    let name = args.get(3).cloned().unwrap_or_else(|| runner_id.clone());
    let pace_sec_per_km: f64 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(240.0);
    let country = args.get(5).cloned();
    let avatar = args.get(6).cloned();
    let base_kmh = 3600.0 / pace_sec_per_km;

    let token = request_token(&room_id, &runner_id, &name, country.as_deref(), avatar.as_deref()).await;
    let url = format!("ws://{HOST}/ws?token={token}");
    let (mut ws, _) = tokio_tungstenite::connect_async(&url)
        .await
        .expect("WebSocket 連線失敗");
    println!("[{runner_id}] connected, base {base_kmh:.1} km/h ({})", format_pace(base_kmh as f32));

    // §3.1 校時：取 RTT 最小的樣本作為時鐘偏差估計（受單向延遲不對稱影響最小）
    let mut offset_ms = 0i64;
    let mut best_rtt = i64::MAX;
    let mut start_at: Option<i64> = None;
    let mut race_distance = RACE_DISTANCE_M;
    let mut samples = 0;
    while samples < SYNC_SAMPLES {
        let sent = now_ms();
        let ping = serde_json::to_string(&ClientMsg::Ping { client_send_time: sent }).unwrap();
        ws.send(Message::text(ping)).await.unwrap();
        while let Some(Ok(msg)) = ws.next().await {
            let Message::Text(txt) = msg else { continue };
            match serde_json::from_str::<ServerMsg>(&txt) {
                Ok(ServerMsg::Pong { client_send_time, server_time }) => {
                    let (rtt, offset) = clock_offset(client_send_time, server_time, now_ms());
                    if rtt < best_rtt {
                        best_rtt = rtt;
                        offset_ms = offset;
                    }
                    samples += 1;
                    break;
                }
                // 連線時伺服器會補發目前排程；已經開跑的舊排程不追，等下一次發令
                Ok(ServerMsg::RaceScheduled { start_at_server_time, race_distance_meters, .. })
                    if start_at_server_time > now_ms() + offset_ms =>
                {
                    start_at = Some(start_at_server_time);
                    race_distance = race_distance_meters;
                }
                _ => continue,
            }
        }
    }
    println!("[{runner_id}] clock synced: offset {offset_ms}ms, best RTT {best_rtt}ms");

    // 等待發令（§3.2）
    while start_at.is_none() {
        let Some(Ok(msg)) = ws.next().await else {
            return;
        };
        let Message::Text(txt) = msg else { continue };
        if let Ok(ServerMsg::RaceScheduled { start_at_server_time, race_distance_meters, .. }) =
            serde_json::from_str(&txt)
        {
            if start_at_server_time > now_ms() + offset_ms {
                start_at = Some(start_at_server_time);
                race_distance = race_distance_meters;
            }
        }
    }
    let start_at = start_at.unwrap();
    let server_now = || now_ms() + offset_ms;
    let wait = (start_at - server_now()).max(0) as u64;
    println!("[{runner_id}] GO in {wait}ms, {race_distance}m");
    tokio::time::sleep(Duration::from_millis(wait)).await;

    // 依配速推進距離。正弦擾動讓各選手名次會互相交換，且同參數可重現。
    let phase = runner_id.bytes().map(|b| b as f64).sum::<f64>() % 6.283;
    let mut distance = 0.0f64;
    // 從「現在」起算而非從發令時刻，否則中途加入已開賽的房間會一次補出好幾公里
    let mut prev = (server_now().max(start_at), 0.0f64);
    let mut seq = 0u64;
    loop {
        tokio::time::sleep(Duration::from_millis(REPORT_INTERVAL_MS)).await;
        let t = server_now();
        let elapsed_ms = t - start_at;
        let elapsed_s = elapsed_ms as f64 / 1000.0;
        let speed_kmh = base_kmh * (1.0 + 0.05 * (elapsed_s * 0.3 + phase).sin());
        distance += speed_kmh / 3.6 * ((t - prev.0) as f64 / 1000.0);

        let finish_time_ms =
            interpolate_finish(prev.0, prev.1, t, distance, race_distance);
        let packet = ClientMsg::Telemetry(Telemetry {
            room_id: room_id.clone(),
            runner_id: runner_id.clone(),
            sequence_id: seq,
            server_sync_time: t,
            race_elapsed_ms: elapsed_ms,
            current_distance: (distance * 100.0).round() / 100.0,
            current_speed: speed_kmh as f32,
            // 真機的步頻來自 TreadmillMetric.cadence；此處以速度粗略推估
            cadence: (150.0 + speed_kmh.min(25.0) * 1.8) as u16,
            current_pace: format_pace(speed_kmh as f32),
            is_finished: finish_time_ms.is_some(),
            finish_time_ms,
        });
        if ws
            .send(Message::text(serde_json::to_string(&packet).unwrap()))
            .await
            .is_err()
        {
            break;
        }
        if let Some(finish) = finish_time_ms {
            println!(
                "[{runner_id}] FINISHED {:.3}s",
                (finish - start_at) as f64 / 1000.0
            );
            break;
        }
        prev = (t, distance);
        seq += 1;
    }
}

/// ponytail: 手寫最小 HTTP POST，省掉一個 HTTP client 依賴（只打本機固定端點）
async fn request_token(
    room_id: &str,
    runner_id: &str,
    name: &str,
    country: Option<&str>,
    avatar: Option<&str>,
) -> String {
    let body = serde_json::json!({
        "runnerId": runner_id, "name": name, "role": "RUNNER",
        "country": country, "bib": runner_id, "avatarUrl": avatar,
        "deviceId": format!("mock-{}", runner_id),
    })
    .to_string();
    let req = format!(
        "POST /rooms/{room_id}/tokens HTTP/1.1\r\nHost: {HOST}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let mut stream = tokio::net::TcpStream::connect(HOST)
        .await
        .expect("伺服器未啟動？先執行 cargo run --bin fitrace_live");
    stream.write_all(req.as_bytes()).await.unwrap();
    let mut resp = String::new();
    stream.read_to_string(&mut resp).await.unwrap();
    let json_body = resp.split("\r\n\r\n").nth(1).expect("HTTP 回應格式異常");
    let parsed = serde_json::from_str::<serde_json::Value>(json_body).unwrap();
    parsed["token"]
        .as_str()
        .map(|s| s.to_string())
        .unwrap_or_else(|| {
            eprintln!("{}", json_body);
            std::process::exit(1);
        })
}
