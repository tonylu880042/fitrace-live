# FitRace Live 系統架構與開發設計文件 (Agent.md)

本文件定義 **FitRace™ Live** 的室內跑步機即時連線競賽系統架構：取電競化現場賽事的節奏，搭配 FitRace 的「Deep Velocity HUD」設計語彙，讓不同場館、不同品牌的跑步機同場競速。系統涵蓋 **跑步機 Android 選手端**、**雲端競技伺服器 (Rust)**、**雲端賽事控制台 (Web)** 與 **現場大螢幕即時實況看板 (Web)**。

> FitRace 原為搭配樹莓派與 IoT gateway、服務單一健身房的本地版本；**FitRace Live** 是跨場館、上雲、可安裝在任何具 Android 主控台之跑步機的版本。

---

## 1. 系統整體架構全景 (System Architecture)

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                    全球場館 / 具 Android 主控台的智慧跑步機                     │
│                                                                               │
│  ┌───────────────────────┐                                                    │
│  │ 馬達/感測硬體 (MCU)    │                                                    │
│  └───────────┬───────────┘                                                    │
│              ▼                                                                │
│  ┌───────────────────────┐              AIDL (Binder IPC)                     │
│  │ 主控台系統服務(如 FitOS)│─────────────────────────────────────────┐          │
│  └───────────────────────┘                                          │          │
│                                                                     ▼          │
│ ┌───────────────────────────────────────────────────────────────────────────┐ │
│ │                  FitRace Live Android App (Kotlin)                        │ │
│ │  - Treadmill 介面：各品牌主控台的介接（FitOSTreadmill 為 AIDL 實作）      │ │
│ │  - RaceCalculationEngine: 歸零校正、瞬時配速換算、平滑濾波、完賽毫秒判定  │ │
│ │  - TimeSyncEngine: SNTP 往返測量，對齊伺服器基準世界時間 (Server Epoch)   │ │
│ │  - UI Dashboard: Jetpack Compose 選手第一人稱競速儀表板                    │ │
│ └─────────────────────────────────────┬─────────────────────────────────────┘ │
└───────────────────────────────────────┼───────────────────────────────────────┘
                                        │ WebSocket (2~3 Hz 節流傳輸)
                                        ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                          AWS 雲端高併發競技中樞                                │
│                                                                               │
│                  AWS Global Accelerator (全球 Anycast 骨幹加速)               │
│                                       │                                       │
│                         Network Load Balancer (NLB)                           │
│                                       │                                       │
│          ┌────────────────────────────┴────────────────────────────┐          │
│          ▼                                                         ▼          │
│   ┌───────────────────────────────┐         ┌──────────────────────────────┐  │
│   │  AWS ECS Fargate (Rust Pod 1) │         │ AWS ECS Fargate (Rust Pod 2) │  │
│   │   - Tokio + Axum 非同步引擎   │         │  - 零 GC 停頓、微秒級時鐘    │  │
│   │   - 房間排程、時序延遲補償    │         │  - 毫秒撞線判定、Token 驗證  │  │
│   └──────────────┬────────────────┘         └──────────────┬───────────────┘  │
│                  └────────────────────┬────────────────────┘                  │
│                                       ▼                                       │
│              Amazon ElastiCache for Redis (跨節點房間廣播 Pub/Sub)             │
│              - 房間即時狀態、全球/日榜/週榜排行榜 (Redis Sorted Set)           │
└───────────────────────────────────────┬───────────────────────────────────────┘
                                        │ WebSocket 唯讀廣播 (2~3 Hz)
                                        ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                        現場大螢幕即時實況看板 (Web Page)                      │
│                                                                               │
│  - 任意設備開瀏覽器即播 (智慧電視 / 筆電接投影機 / 電視牆 / OBS 直播訊號來源)   │
│  - 60fps 動態進度條補間 (GSAP / CSS Transitions + requestAnimationFrame)     │
│  - 螢幕防休眠長亮 (Screen Wake Lock API) + 斷線自動指數重連                   │
│  - 全螢幕 Kiosk 沉浸式電競暗黑科技風格 UI                                    │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 跑步機端：硬體介接與本地計算

### 2.0 跑步機介接（與廠商無關）

比賽邏輯只依賴 FitRace 自己定義的 `Treadmill` 介面與中性的 `TreadmillReading` 數據，不直接使用任何廠商的型別。支援新品牌的 Android 主控台，只需要多寫一個 `Treadmill` 實作。

| 實作 | 說明 |
|---|---|
| `FitOSTreadmill` | 透過 AIDL 綁定 FitOS 系統服務（下方 §2.1 為提案合約）；開發時綁定 `MockFitOSService` 模擬跑步機 |

以下 §2.1 是 **FitOS 實作**所使用的介面合約。

### 2.1 AIDL 介面合約定義

> **狀態：提案版本，尚未取得 FitOS 實際介面規格。** 以下欄位與方法為本專案預期的合約，實作 Android 端（Phase 3）前須與 FitOS 方確認並對齊。雲端協議（§3）刻意不依賴 AIDL 細節，因此規格變動只影響 Android 端內部。

- **傳輸協議**：Android Binder IPC（延遲 < 1ms，零藍牙斷線風險）
- **高可用機制**：`FitOSTreadmill` 實作 `IBinder.DeathRecipient`，若 FitOS 系統服務重啟，自動重新綁定並恢復註冊。

#### (1) `TreadmillMetric.aidl`
```aidl
package com.fitos.treadmill;

parcelable TreadmillMetric;
```
包含欄位：
- `speedKmh` (float): 即時時速（km/h）
- `totalDistanceMeters` (double): 機台總累計刻度距離（公尺）
- `incline` (float): 當前坡度百分比（如 1.0 代表 1% 坡度）
- `cadence` (int): 步頻（SPM）
- `timestamp` (long): 硬體底層時間戳記（毫秒）
- `machineStatus` (int): 機台狀態（0: 待機, 1: 運轉中, 2: 暫停/急停）

#### (2) `ITreadmillDataCallback.aidl`
```aidl
package com.fitos.treadmill;

import com.fitos.treadmill.TreadmillMetric;

oneway interface ITreadmillDataCallback {
    void onMetricUpdated(in TreadmillMetric metric);
    void onSafetyKeyTriggered(boolean isDetached);
}
```

#### (3) `IFitOSService.aidl`
```aidl
package com.fitos.treadmill;

import com.fitos.treadmill.ITreadmillDataCallback;
import com.fitos.treadmill.TreadmillMetric;

interface IFitOSService {
    boolean registerCallback(ITreadmillDataCallback callback);
    boolean unregisterCallback(ITreadmillDataCallback callback);
    TreadmillMetric getCurrentMetric();
    boolean setTargetSpeed(float speedKmh);
    boolean setTargetIncline(float incline);
}
```

### 2.2 本地計算引擎職責 (RaceCalculationEngine)
1. **起跑基準校準 (Zeroing Calibration)**：
   發令槍響時刻記錄 `baseDistance = metric.totalDistanceMeters`，競賽累計距離為 $\text{raceDistance} = \max(0.0, \text{metric.totalDistanceMeters} - baseDistance)$。
2. **配速換算與濾波 (Pace Smoothing)**：
   $\text{瞬時配速 (秒/km)} = \frac{3600}{\text{speedKmh}}$，採用移動平均（SMA）濾波消除馬達脈衝雜訊。
3. **終點線性插值 (Finish Line Interpolation)**：
   當前一取樣點 $d_1 < 5000\text{m}$ 且當前取樣點 $d_2 \ge 5000\text{m}$ 時，計算高精度理論撞線時間戳記：
   $$t_{\text{finish}} = t_1 + \frac{5000.0 - d_1}{d_2 - d_1} \times (t_2 - t_1)$$
4. **雲端上報節流 (Throttling)**：
   AIDL 頻率（5~10Hz）供本地 60fps 儀表板刷新，每 300~500ms（2~3Hz）組包上報至雲端伺服器。
5. **里程表歸零保護 (Odometer Reset Guard)**：
   FitOS 服務若在賽中重啟，`totalDistanceMeters` 會從 0 重新起算，直接套用歸零公式會讓選手整場已跑距離消失。
   引擎偵測到競賽距離回退時重設基準並從原處接續（已跑距離在物理上不可能減少）。
   同時 `FitOSTreadmill` 會在重新綁定後補送最後一次的目標速度——重啟後的服務是全新實例，速度為 0。
   兩者皆已於模擬器上以 `kill -9` 實測驗證。
6. **完賽即停 (Stop at Finish)**：
   達成賽事距離那一刻，比賽對該選手就結束了，各端依序停下：
   - **跑步機**：引擎在撞線那一筆取樣回報 `justFinished`（只觸發一次），App 隨即呼叫 `setTargetSpeed(0)`。減速曲線由 FitOS／馬達控制器負責，模擬跑步機為每秒 2 km/h（18 km/h 約 9 秒停妥）。
   - **成績**：距離釘在賽事距離（撞線那一筆通常多跑幾公尺，一律記為 5,000m）、配速凍結在撞線當下；皮帶減速期間持續上報也不會再變動。
   - **對手差距**：完賽後名次已定，差距凍結在撞線當下，不再隨仍在跑的對手變動。
   - **伺服器**：`RunnerState::apply` 在收到完賽封包後同樣把距離釘住，不依賴客戶端是否正確處理。
   - **大螢幕**：全員完賽即停錶，停在最後一位的撞線時間。
   賽事距離一律以伺服器 `RACE_SCHEDULED` 宣告的 `raceDistanceMeters` 為準，App 不再寫死 5,000m。

### 2.3 選手端 HUD 版面

版面由 **Google Stitch** 設計：專案 `17741436539835090440`、畫面 **FitRace 10-Inch Treadmill Athlete Cockpit HUD**（`42c501f3aa72416a9d720a99035596bc`），套用專案內的 `Deep Velocity HUD` 設計系統。以 Jetpack Compose 實作，弧線與賽道條全部用 `Canvas.drawArc` / `drawPath` 自繪，無圖表函式庫。

- **頂列**：FitRace 斜體字標、賽事與房間、跑步機與伺服器連線狀態、右側 ELAPSED 計時艙（開賽前顯示倒數、完賽後顯示成績，皆為金色）
- **左面板**（帶轉角括號）：巨大速度弧錶配點狀刻度環為視覺主體；右欄三張遙測卡（當前配速／步頻／坡度）；底部狀態列顯示皮帶狀態與目標速度
- **右面板**：Competitive Matrix — 名次、落後領先者公尺數（珊瑚紅）、與前一名的公尺差（青藍）、預估完賽時間（金色）
- **底部**：5,000m 賽道條，每 1,000m 一道刻度、4,000m 標為衝刺區，白色菱形是自己、金色圓點是領先者的真實位置

字型依設計系統：字標與標題 Space Grotesk、所有數值與標籤 JetBrains Mono，兩者皆以 `res/font/` 隨 APK 打包（不走 Downloadable Fonts 以免依賴 Play 服務）。整張 HUD 以 1280×800 為基準等比縮放。

Stitch 生成稿中有四個欄位是硬體量不到或憑空生成的，已替換為真實資料：

| 設計稿欄位 | 本專案做法 |
|---|---|
| `SYNC 0.8ms` / `DUAL-LINK 60FPS` | 換成 §3.1 校時實測的 RTT，與 `machineStatus` 對應的皮帶狀態 |
| `BELT TRACTION: OPTIMAL 99.4%` | 無此感測值，換成 `TreadmillMetric.machineStatus`（IDLE／RUNNING／PAUSED） |
| `TACTICAL MODE: FRONT PACK CHASE` | 無此概念，換成透過 AIDL `setTargetSpeed` 設定的目標速度 |
| `SPLIT 4K` | 無意義，換成場上人數 |

另外設計稿沒有涵蓋、但實際需要的狀態已補上：開賽倒數、完賽成績、安全鑰匙拔除警示，以及一組 −/+ 速度鍵（模擬跑步機需要操作入口，真機是實體按鍵，接真機時可移除）。

`GAP TO LEADER` 的 `CLOSING` / `OPENING` 標示由前端比較前後兩次榜單廣播的差距算出，變化小於一公尺視為持平。

---

## 3. 即時連線競技精度協議 (Race Synchronization Protocol)

為確保全球選手連線時的絕對公平性，消除不同設備時鐘偏差與跨國網路延遲差異：

### 3.1 房間級 SNTP 時鐘對齊
- 進入房間後，App 與伺服器進行 5 次 Ping-Pong RTT 採樣：
  $$\text{RTT} = T_{\text{client\_recv}} - T_{\text{client\_send}}$$
  $$\text{Clock Offset} = T_{\text{server}} - \left(T_{\text{client\_send}} + \frac{\text{RTT}}{2}\right)$$
- 測算各機台與伺服器的時鐘偏差，將全場時鐘統一至 **$\pm 5\text{ms}$** 精度內。

### 3.2 統一排程發令起跑 (Scheduled Countdown)
- 伺服器排程起跑時間戳 $T_{\text{start}} = \text{ServerNow} + 5000\text{ms}$。
- 各機台依對齊後的時鐘同步倒數 `3... 2... 1... GO`，在 $T_{\text{start}}$ 當下統一開跑計時，**徹底解決誰先收到指令誰先跑的不公問題**。

### 3.3 連線身分驗證 (Connection Auth)
- 選手端與看板端連線 WebSocket 時，需於連線 URL/Header 帶入伺服器核發的一次性房間 Token（選手 Token 可寫、看板 Token 唯讀），伺服器驗證 Token 對應的 `roomId`/`runnerId` 後才允許加入房間並接受上報。
- 未帶有效 Token 或 Token 與宣稱的 `runnerId` 不符者，拒絕連線，避免任意冒名上報假數據。

### 3.4 數據通訊封包規範 (WebSocket DTO)

#### 選手上報封包 (`RunnerTelemetryPacket`)
```json
{
  "type": "TELEMETRY",
  "roomId": "ROOM_101",
  "runnerId": "RUNNER_01",
  "sequenceId": 4821,
  "serverSyncTime": 1727055000250,
  "raceElapsedMs": 35210,
  "currentDistance": 142.30,
  "currentSpeed": 14.8,
  "cadence": 182,
  "currentPace": "04'03\"",
  "isFinished": false,
  "finishTimeMs": null
}
```

#### 伺服器廣播榜單封包 (`LeaderboardBroadcastPacket`)
```json
{
  "type": "LEADERBOARD_UPDATE",
  "roomId": "ROOM_101",
  "serverTime": 1727055000500,
  "rankings": [
    {
      "rank": 1,
      "runnerId": "RUNNER_01",
      "name": "Alex Chen",
      "country": "TW",
      "bib": "251",
      "avatarUrl": "https://…/alex-chen.jpg",
      "distance": 142.30,
      "pace": "04'03\"",
      "speedKmh": 14.8,
      "cadence": 182,
      "progressPercent": 0.028,
      "status": "RUNNING",
      "finishTimeMs": null,
      "gapToLeaderMs": null,
      "gapToAheadMs": null
    }
  ]
}
```

---

## 4. 伺服器端：AWS + Rust 高性能架構

### 4.1 技術棧選型
- **開發語言**：**Rust**（2024 Edition）— 選型理由：效能優先；開發由 Coding Agent 主導實作與除錯，語言生態成熟度非主要限制因素。
- **非同步 Runtime**：**Tokio**
- **Web / WebSocket 框架**：**Axum** + **tokio-tungstenite**
- **記憶體快取與廣播**：**fred** / **redis-rs** (Redis Pub/Sub + Sorted Sets)
- **序列化**：**serde** / **serde_json**
- **高精度時間**：**quanta** / **chrono**

### 4.2 AWS 部署架構 (Serverless Container) — Phase 4 目標態

> **MVP 部署（Phase 1-3）**：伺服器直接在本機 Mac 上跑，不需要 NLB / Global Accelerator / ECS Fargate。待 Phase 1-3 驗證完協議、精度與體驗後，Phase 4 才依下列架構部署上雲。
>
> **Redis 亦延後至 Phase 4**：Redis 在架構中的職責是「跨節點房間廣播」與「持久化排行榜」，單進程 MVP 兩者都不需要——房間狀態放記憶體，廣播用 `tokio::sync::broadcast`。等 Phase 4 真的跑多個 Pod 時再換上 Redis Pub/Sub。
>
> 本機執行（`server/`）：
> ```bash
> FITRACE_ADMIN_KEY=devkey cargo run --bin fitrace_live   # 啟動伺服器 :8080（不設金鑰則啟動時隨機產生並印出）
> ```
> - 雲端控制台：`http://127.0.0.1:8080/admin`（輸入管理金鑰），在此建立比賽、發令、設定自動規則（§9）
> - 大螢幕：`http://127.0.0.1:8080/display?room=R0001`
> - 假選手（房間須先由控制台建立）：`cargo run --bin mock_runner -- R0001 R1 "Alex" 240 TW`
- **AWS Global Accelerator**：Anycast IP 入口，全球流量經由 AWS 專屬骨幹網傳輸，大幅降低抖動與跨國丟包。
- **Network Load Balancer (NLB)**：4 層負載均衡，高效維持 TCP/WebSocket 長連線。
- **AWS ECS Fargate (ARM64 / Graviton)**：
  - 多階段建置 Docker 映像檔（< 20MB）。
  - 單容器記憶體開銷僅約 20~30MB，零 GC 停頓，支撐數萬長連線。
- **Amazon ElastiCache for Redis**：
  - 房間狀態管理與 Pub/Sub 扇出。
  - `ZADD` 維護全球 5km 挑戰賽即時日榜/週榜/總榜。

---

## 5. 現場大螢幕：Web 即時實況看板

### 5.0 設計來源與資料對應

大螢幕版面復刻 **FitRace 世界錦標賽 Leaderboard** 設計稿（Stitch 專案 `17741436539835090440` 的 FitRace 品牌版）。版面規格：

- **冠軍列**：金→珊瑚紅漸層外框加外暈、金色盾形名次徽章、金框大頭照、珊瑚紅膠囊進度條、標題後接金色完成百分比、右側圓弧速度儀表（滿刻度 25 km/h）
- **其他名次**：`RANK` 標籤加大字名次、深色卡片、青藍膠囊進度條、`SPLIT` 欄位兩行 `GAP`（對領先者／對前一名）
- **共用**：斜體 FitRace 字標、青藍漸層分隔線、碼表圖示與總計時、右上場次膠囊；全站字型 Space Grotesk，數值一律 `tabular-nums` 避免高頻刷新跳動

設計稿需要的欄位中有四個是原協議沒有的，處理方式：

| 設計稿欄位 | 本專案做法 |
|---|---|
| 速度儀表 | 選手端本來就有上報 `currentSpeed`，榜單封包補上 `speedKmh` 轉發 |
| GAP（兩行） | 伺服器端計算 `gapToLeaderMs` / `gapToAheadMs`：完賽者比成績，比賽中者以「距離差 ÷ 自身當前速度」換算時間 |
| 選手大頭照 | 發 Token 時可帶 `avatarUrl`；未提供則以姓名縮寫色塊替代，不放假圖 |
| 國旗 | `country` 需為 **ISO 3166-1 alpha-2**（如 `FR`/`JP`），前端轉區域指示符號 emoji。Windows 字型不含國旗字符，該平台會退化成兩個字母 |

另註：`cadence`（步頻）已納入協議（§2.1 硬體本來就提供），但此版面沒有顯示它的位置；設計稿上也沒有心率欄位，而 `TreadmillMetric` 本來就沒有心率感測值。

### 5.1 設計理念
- **Zero-Install**：任何設備瀏覽器全螢幕（F11 / Kiosk 模式）直接播放，免安裝維護 APK。
- **雲端換膚與熱更新**：主題、贊助商 Logo 與賽道樣式雲端即時更換。
- **OBS / 轉播整合**：支援作為轉播軟體 Browser Source 疊加 HUD 計分板。

### 5.2 核心關鍵實作
1. **螢幕常亮**：使用 `navigator.wakeLock.request('screen')` 阻斷螢幕休眠。
2. **平滑補間 (Dead Reckoning + GSAP)**：
   收到 2Hz 的 WebSocket 榜單數據時，透過 `requestAnimationFrame` 與 GSAP 進行位置與進度條補間動畫，呈現絲滑 60fps 效果。
3. **自動斷線指數退避重連**：確保會場 Wi-Fi 不穩時自動復原。
4. **唯讀權限隔離**：使用 Read-Only 房間 Token 連線，確保賽事安全性。

---

## 6. 專案開發階段劃分 (Milestones)

開發順序為 **Rust 伺服器 → Web 大螢幕 → Android 選手端**。原因：FitOS AIDL 實際規格尚未取得（§2.1），Android 端無法定案；而雲端協議（§3）不依賴 AIDL 細節，可先獨立開發並以 mock runner 驗證。

1. **Phase 1: Rust 伺服器原型與時鐘對齊 (Server MVP)**
   - 搭建 Tokio + Axum WebSocket 賽事房間服務（本機 Mac 執行）。
   - 實作房間 Token 驗證（§3.3）、SNTP 校時協議與排程倒數發令（§3.1-3.2）。
   - **Mock Runner**：同一 crate 下的第二個 bin target（`cargo run --bin mock_runner`），複用相同 DTO struct，依指定配速產生假 telemetry 並以 2~3Hz 上報。多開幾個 process 即可模擬整個房間，是 Phase 1-2 唯一的數據來源。
2. **Phase 2: Web 大螢幕即時榜單 (Display MVP)**
   - 打造 Dark/Neon 電競風大螢幕頁面。
   - 實作平滑動畫補間與斷線重連，以 mock runner 驅動驗證。
   - 此階段結束即可對客戶做完整賽事 Demo（假選手、真榜單、真動畫）。
3. **Phase 3: Android 選手端與跑步機介接 (Client MVP)**
   - 前置條件：取得並確認 FitOS AIDL 實際規格。
   - 建立 AIDL 介面與 Mock 模擬服務（支援在一般平板/模擬器除錯）。
   - 實作 `RaceCalculationEngine`（起跑校正、配速換算、撞線插值）。
4. **Phase 4: AWS 雲端部署與整合端到端測試 (Integration & Scale)**
   - Phase 1-3 皆於本機 Mac 完成開發與測試（本機 Server + 本機/Docker Redis），驗證協議正確性與競賽精度。
   - Phase 4 才撰寫 Dockerfile 並部署至 AWS ECS Fargate + NLB + Global Accelerator（§4.2 為此階段目標架構，非 MVP 起點）。
   - 進行跨國延遲補償與高併發連線壓力測試。

---

## 7. 前端視覺設計系統 (UI Design System - FitRace™)

本專案已於 **Google Stitch** 建立專屬設計專案：
- **產品品牌與 Logo**：**FitRace™**
- **Stitch Project ID**：`17741436539835090440`
- **設計語言**：**Deep Velocity HUD**（FitRace 電競級賽事氛圍）
- **調色盤標準**：
  - 基底底色：黑曜石碳黑 `#060B11`、深海暗夜 `#0E141A`
  - 競速主色：電光青藍 `#00F0FF`（正常運動指標、配速指引、已跑進度）
  - 衝刺強調：霓虹珊瑚紅 `#FF3366`（衝刺區間、高心率警戒、落後縮短）
  - 領先榮譽：世界冠軍金 `#FFD700`（P1 領先者外框、金牌徽章）
- **字型規範**：
  - 抬頭、品牌標識與選手姓名：`Space Grotesk`（現代幾何運動風格）
  - 數據與碼表：`JetBrains Mono`（等寬數值，高頻刷新零跳動）

---

## 8. 已明確排除的範圍 (Deferred Scope)

以下項目為**刻意排除**，非遺漏。待 Demo 展示結果與客戶討論後再決定是否納入：

1. **持久化資料庫與帳號系統**
   - 目前僅有 Redis 保存房間即時狀態與排行榜（易失性記憶體）；不做選手帳號、歷史成績、長期統計的持久化儲存。
   - 重啟伺服器即失去歷史資料，Demo 階段可接受。
   - **重新評估時機**：Demo 後與客戶確認是否需要「選手回訪查看個人歷史紀錄」需求。

2. **Token 核發端點的存取控制**
   - `POST /rooms/{roomId}/tokens` 目前**完全不需驗證**，任何能連到伺服器的人都能為任意 `runnerId` 申請 Token。
   - 這表示 §3.3 的「冒名上報」防護在目前階段可被繞過：連線後的 Token↔runnerId 綁定檢查是有效的（已實測），但取得 Token 這一關是敞開的。
   - 本機 Demo 可接受。**重新評估時機**：對外開放連線之前（Phase 4 上雲，或任何非本機的展示），屆時應改為由場館後台／賽事管理系統簽發，或至少加上管理金鑰。

3. **防作弊審計機制**
   - §3.3 的房間 Token 驗證僅解決「冒名上報」問題，不處理「真人操作跑步機作弊」（如手動竄改機台數據、異常加速偵測、速度/距離一致性稽核）。
   - **重新評估時機**：進入正式賽事營運階段、或客戶提出賽果公信力要求時。



---

## 9. 賽事管理 (Race Management)

2026-09-23 與使用者討論後定案。角色分為**管理方**（雲端控制台）與**參賽方**（Android 跑步機）；大螢幕只供觀看。

### 9.1 比賽生命週期

`OPEN`（報名中）→ `STARTING`（5 秒倒數）→ `RUNNING` → `FINISHED`（已結算）→ 結算 10 分鐘後自動移除。

- 比賽只能由雲端建立（手動或自動規則），選手端與大螢幕不再能順帶建房；房號由伺服器產生（`R0001`…）。
- 建立時即決定：名稱、距離、人數上限（可不設）、關門時間（預設每公里 10 分鐘，可改）。
- 結算條件：全員完賽、到達關門時間、或管理方「立即結束」。未完賽者記 **DNF**，排名在完賽者之後、依已跑距離排序。
- 不做「準備好」確認：雲端發令即開跑。

### 9.2 管理方（雲端控制台 `/admin`）

- 權限：單一管理金鑰（環境變數 `FITRACE_ADMIN_KEY`；未設定則啟動時隨機產生並印出）。不分帳號、不分場館，所有比賽全域可見。
- 手動：建立、發令（僅 OPEN 且至少 1 人）、立即結束、取消（僅未發令）、移除已結算的比賽。
- 自動規則：每條規則維持一場 OPEN 比賽；報名滿 `minRunners`（預設 2）後倒數 `waitSeconds`（預設 60）自動發令，額滿則立即發令；發令後自動開下一場。手動與自動並存。

### 9.3 參賽方（Android）

- 身分（最輕量）：手打選手 ID，App 產生並保存裝置代碼；同一選手 ID 在比賽中綁定該裝置，他台裝置使用會被拒。個人資料存本機。
- 報名：額滿或已發令不可報名；一個選手 ID 同時只能在一場未結束的比賽中。
- 取消報名：發令前「回大廳」即取消。App 當掉未回來者維持報名，關門時記 DNF（不做斷線自動取消，避免槍響時網路抖動被踢出）。
- 關門時間到：仍在跑的選手皮帶自動停下並顯示 DNF；先完賽者可直接回大廳。

### 9.4 本輪不做

移除選手、大螢幕自動跟隨下一場、選手端全場成績表、成績永久保存（§8）。
