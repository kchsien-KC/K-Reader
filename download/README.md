# K.閱讀 v1.2 旗艦純淨版 — 階段交付檔清單

以純淨開源「閱讀（Legado 3.0）」架構為起點，六階段逐段交付。
套名為 Stage 6 `init_k_reader.sh` 重構後之 `com.k.reader`（applicationId：`com.k.reader`）。

## Stage 1 — 專案識別與編譯配置（已完成）

| 檔案 | 說明 |
| --- | --- |
| `app/build.gradle.kts` | applicationId `com.k.reader`、arm64-v8a 單 ABI、R8 full mode、語系裁切 zh-rTW/en |
| `gradle.properties` | JVM / R8 full mode / 建置快取參數 |
| `app/proguard-rules.pro` | R8 keep 規則（Rhino / Gson / Room / OkHttp…） |
| `app/src/main/res/values-zh-rTW/strings.xml` | 241 鍵全繁中字串（Stage 2~5 直接引用） |
| `res/drawable/ic_launcher_{background,foreground,monochrome}.xml` | 提案 A「青黛宣紙」純向量圖示三件層 |
| `res/mipmap*/ic_launcher*.xml` | 自適應圖示包裝層（零點陣圖） |

## Stage 2 — 防封鎖三件套＋簡轉繁＋隨機試讀（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- | --- |
| `app/src/main/java/com/k/reader/help/http/UserAgentPool.kt` | 144 | 動態 UA 池：來源黏住、30 分鐘輪替、瀏覽器級標頭 |
| `app/src/main/java/com/k/reader/help/http/SourceRateLimiter.kt` | 112 | 單源 QPS 限流：700ms 間隔 + ±18% 抖動、suspend 等待 |
| `app/src/main/java/com/k/reader/help/http/SslTrustHelper.kt` | 150 | SSL 寬容重試通道 + TLSv1.2/1.3 相容層 |
| `app/src/main/java/com/k/reader/help/TraditionalChineseHelper.kt` | 455 | OpenCC s2twp 四段式詞典引擎 + 777 對內建保底字符表 + 「」『』排版優化 |
| `app/src/main/java/com/k/reader/help/RandomTrialManager.kt` | 403 | 隨機抽書直達正文、首章 200 字門檻重試 3 次、試讀條綁定、離場清除 |
| `app/src/main/res/layout/view_trial_bar.xml` | 77 | 試讀條（換一本試讀／一鍵加入書架） |

壓縮包：`K_Reader_Stage2_files.zip`（6 檔，路徑即 Legado 3.0 樹落點）。

## Stage 3 — 書源／訂閱源完整鏈路檢核與智慧分組（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- | --- |
| `app/src/main/java/com/k/reader/help/source/BookSourceHealthManager.kt` | 515 | 書源三步全鏈路併發檢核（搜尋→目錄→首章正文 150 字門檻）；Semaphore×6 併發×單源限流；疑似封鎖換身分重試、SSL 類重試；respondTime 寫回；失敗自動歸入「失效」分組 |
| `app/src/main/java/com/k/reader/help/source/RssSourceVerifyManager.kt` | 390 | 訂閱源檢核：GET＋瀏覽器級完整標頭＋動態 Referer（站點根推導）；Feed 嗅探 RSS／Atom／JSON／HTML 四型；SSL 寬容通道重試 |
| `app/src/main/java/com/k/reader/help/source/SourceGroupManager.kt` | 402 | 智慧分組：型態＋名稱關鍵字分類（小說／有聲／漫畫／資訊／圖庫／影音／其他）、依網域聚攏鏡像源、失效分組同步與一鍵清空；冪等置換、自訂群組恆保留、標籤零硬編碼 |
| `app/src/main/res/layout/dialog_source_verify.xml` | 63 | 一鍵併發檢核進度對話框（水平進度條＋狀態列＋當前源/步驟明細） |

壓縮包：`K_Reader_Stage3_files.zip`（4 檔，路徑即 Legado 3.0 樹落點）。

## Stage 4 — WebView 過盾＋剪貼簿偵測＋HTTP 聽書＋智慧換源（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- |
| `app/src/main/java/com/k/reader/help/web/CloudFlareHelper.kt` | 362 | WebView 過盾：盾頁特徵偵測（HTML 標記＋403/429/503）；內嵌可互動過盾對話框（500ms Cookie 輪詢、2 分鐘逾時）；cf_clearance 寫回系統 CookieManager 並 flush 持久化；過盾 UA 另表鎖定（身分配對——UA 池輪替不失效 Cookie） |
| `app/src/main/java/com/k/reader/help/clipboard/ClipboardSourceDetector.kt` | 406 | 剪貼簿偵測：三形態分類（書源 JSON／訂閱源 JSON／來源網址）；SHA-256 永久去重＋30 秒冷卻防打擾；僅前景 onResume 讀取一次（Android 10+ 限制）；URL 形態抓取全程 Stage 2 三件套護航；匯入自動補智慧分組標籤 |
| `app/src/main/java/com/k/reader/help/tts/HttpTtsEngine.kt` | 623 | HTTP 聽書引擎：Edge 神經語音 WebSocket 協定（Sec-MS-GEC 權杖＋二進位音訊幀解析）＋泛用 REST 模板引擎（{{speakText}} 等四模板＋自訂標頭＋SSML POST）；段落→句讀二級分段（單段 300 字）；64MB 語音快取（重聽零流量）；三件套防封鎖護航 |
| `app/src/main/java/com/k/reader/service/HttpReadAloudService.kt` | 529 | HTTP 聽書前台服務：章節→分段→逐段合成播放迴圈（PlaybackParams 語速音調即時套用）；朗讀完自動下一章；MediaSession 媒體鍵＋通知列控制＋AudioFocus 焦點禮讓；單段失敗跳段、連續 3 段失敗停止回報 |
| `app/src/main/java/com/k/reader/help/source/SmartSourceSwitcher.kt` | 615 | 智慧換源：候選源啟用＋可搜尋＋非當前＋非失效分組（respondTime 升序取前 50）；Semaphore×6 併發同名搜尋；Levenshtein 相似度門檻 60%＋綜合評分（相似 70%＋延遲 30%）；章節三級對齊＋對齊章 150 字驗證；書籍欄位寫回＋閱讀頁原地重載 |
| `app/src/main/res/layout/view_clipboard_bar.xml` | 82 | 剪貼簿提示條（偵測文案／忽略本次／一鍵匯入；預設 gone 零干擾、純主題屬性日夜間適配、RTL 友善） |

壓縮包：`K_Reader_Stage4_files.zip`（6 檔，路徑即 Legado 3.0 樹落點）。

## 待續階段

- Stage 5：淨化規則包、回收站、金句卡、閱讀統計、E-Ink、WebDAV、AI 助手
- Stage 6：README、GitHub Actions（Tag 觸發雲端編譯 `K_Reader_v1.2.apk`）、init_k_reader.sh
