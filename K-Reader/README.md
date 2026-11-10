# K.閱讀 v1.2 旗艦純淨版

> 純淨・輕快・沉浸的繁體閱讀 —— 免費、開源、零廣告、零統計 SDK 的 Android 閱讀器。
> 基於開源「閱讀（Legado 3.0）」架構純淨重建，不沿用任何舊版殘留補丁。

**本倉庫是「疊加層（overlay）＋一鍵轉換腳本」，不是完整原始碼樹**——請見〔快速開始〕，
以 `init_k_reader.sh` 對一份純淨的 Legado 3.0 樹執行套名重構，即得完整可編譯之 K.閱讀原始碼。

---

## 旗艦功能一覽（六階段交付）

| 領域 | 功能 |
| --- | --- |
| 網路防封鎖 | 動態 UA 池（來源黏住＋30 分鐘輪替）、單源 QPS 限流（700ms＋抖動）、SSL 寬容重試通道 |
| 繁體中文 | OpenCC s2twp 四段式詞典引擎＋777 對保底字符表＋「」『』排版優化 |
| 探索 | 隨機抽書直達正文（首章 200 字門檻重試 3 次） |
| 書源維運 | 書源／訂閱源全鏈路併發檢核、智慧分組（型態＋網域聚攏＋失效隔離） |
| 反封鎖衝浪 | WebView 過盾（Cloudflare 5 秒盾 Cookie 輪詢＋身分配對） |
| 匯入 | 剪貼簿偵測（書源 JSON／訂閱源 JSON／來源網址三形態＋SHA-256 去重） |
| 聽書 | HTTP 聽書引擎（Edge 神經語音 WebSocket＋REST 模板引擎、64MB 語音快取） |
| 換源 | 智慧換源（Levenshtein 相似度 60% 門檻＋章節三級對齊＋150 字驗證） |
| 內容淨化 | 淨化規則包（全域／依書名／依書源三種綁定＋內建台灣廣告包） |
| 資料安全 | 回收站（書籍／書源／訂閱源／淨化規則四形態、30 天保留） |
| 文青 | 金句卡（1080×1620 青黛宣紙風格純程序化繪製＋系統分享） |
| 成長 | 閱讀統計（今日／本週／本月／累計＋14 天長條圖） |
| 裝置適配 | E-Ink 墨水屏模式（高對比純黑白＋動畫歸零＋電子紙偵測） |
| 雲端 | WebDAV 備份（PROPFIND/MKCOL/PUT/GET/DELETE＋自動增量每 12 小時） |
| AI | 閱讀助手（OpenAI 相容端點：段落摘要／文言文白話翻譯／深度解讀） |

## 與原版「閱讀」完全隔離

- `applicationId = com.k.reader`（與 `io.legado.app` 完美並存、可同時安裝）
- FileProvider 授權：`com.k.reader.filen`
- 備份／匯出隔離目錄：外部儲存 `KReader/`（與原版目錄零衝突）

## 隱私承諾

零統計 SDK、零遙測、零廣告。所有網路請求僅用於：書源／訂閱源抓取、你設定的
WebDAV 備份端點、你設定的 AI 端點。無任何背景資料回報。

## 快速開始

### 一、取得完整原始碼樹（套名重構）

```bash
# 1. 取得一份純淨的 Legado 3.0 原始碼樹（release / git clone 皆可）
# 2. 對其執行本倉庫的轉換腳本
./init_k_reader.sh /path/to/legado-3.0
# 亦可在 Legado 樹內直接執行：bash /path/to/overlay/init_k_reader.sh .
```

腳本執行八大步驟（冪等可重跑）：套名重構 `io.legado.app → com.k.reader`、
產生根專案案檔、疊加六階段交付檔、繁中字串「只增不刪」合併、FileProvider
authority 統一、mipmap 點陣圖清剿（零點陣圖規範）、Gradle Wrapper 對齊 8.7、
完整性驗證。`--help` 可看完整說明。

### 二、本地編譯

```bash
cd /path/to/legado-3.0     # 已轉換的樹
./gradlew :app:assembleRelease
# 產物：app/build/outputs/apk/release/app-release.apk
# 無 keystore.properties 時自動以 debug 簽章建置（本地自用無需任何金鑰）
```

### 三、雲端編譯（GitHub Actions — 推薦）

將轉換後的樹推送至 GitHub，打上標籤即自動編譯發佈：

```bash
git add -A && git commit -m "K.閱讀 v1.2"
git tag v1.2 && git push origin main --tags
# → .github/workflows/build.yml 自動產出 K_Reader_v1.2.apk 並發佈 GitHub Release
```

版本規則：Tag `v1.2` → versionName `1.2`／versionCode `10200`；
`v1.2.1` → `1.2.1`／`10201`。非 Tag 觸發（手動執行）使用預設版本。

正式發佈簽章（選配）——於倉庫 Settings → Secrets 設定：

| Secret | 內容 |
| --- | --- |
| `KREADER_KEYSTORE_BASE64` | keystore（.jks）檔案之 base64 編碼 |
| `KREADER_KEYSTORE_PASSWORD` | keystore 密碼 |
| `KREADER_KEY_ALIAS` | 金鑰別名 |
| `KREADER_KEY_PASSWORD` | 金鑰密碼 |

未設定時自動退回 debug 簽章（僅供自用安裝）。

## 建置需求

| 項目 | 版本 |
| --- | --- |
| JDK | 17（temurin 相容） |
| Gradle | 8.7+（轉換腳本自動對齊 wrapper） |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34（minSdk 23） |
| ABI | 僅 arm64-v8a（單一通用 APK） |

體積與效能底線：安裝檔 < 15MB、冷啟動 < 0.5s——R8 full mode＋資源收縮＋
語系裁切（zh-rTW／en）＋零點陣圖啟動圖示＋不內建大型 TTF／離線語音包。

## 本倉庫結構（疊加層）

```
├── init_k_reader.sh                # 一鍵套名重構腳本（Stage 6）
├── README.md                       # 本文件（Stage 6）
├── gradle.properties               # 全域建置參數（R8 full mode 等）
├── .github/workflows/build.yml     # Tag 觸發雲端編譯（Stage 6）
└── app/
    ├── build.gradle.kts            # applicationId／簽章／依賴（Stage 1）
    ├── proguard-rules.pro          # R8 keep 規則（Stage 1）
    └── src/main/
        ├── java/com/k/reader/…     # 20 個 Kotlin 模組（Stage 2~5）
        └── res/                    # 圖示／布局／241 鍵繁中字串（Stage 1~5）
```

## 六階段交付對照

| 階段 | 範圍 |
| --- | --- |
| Stage 1 | 專案識別與編譯配置（隔離 applicationId／純向量圖示／繁中字串） |
| Stage 2 | 防封鎖三件套＋簡轉繁＋隨機試讀 |
| Stage 3 | 書源／訂閱源完整鏈路檢核與智慧分組 |
| Stage 4 | WebView 過盾＋剪貼簿偵測＋HTTP 聽書＋智慧換源 |
| Stage 5 | 淨化規則包＋回收站＋金句卡＋閱讀統計＋E-Ink＋WebDAV＋AI 助手 |
| Stage 6 | README＋GitHub Actions 雲端編譯＋init_k_reader.sh（本階段） |

Stage 6 整合修正：`app/build.gradle.kts` 之 FileProvider authority 統一為
`com.k.reader.filen`（與 Stage 5 `GoldenQuoteManager` 常數對齊）。

## 授權

上游「閱讀（Legado）」以 GPL-3.0 授權釋出；本專案為其衍生作品，
同樣以 [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html) 釋出——
原始碼隨時可取得、可自由使用與修改，衍生作品須維持同授權。
