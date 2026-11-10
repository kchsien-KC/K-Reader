# K.閱讀 v1.2 旗艦純淨版 — 階段交付檔清單

以純淨開源「閱讀（Legado 3.0）」架構為起點，八階段逐段交付（Stage 1~6 原規劃＋Stage 7／8 兩包旗艦增益）。
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

## Stage 5 — 淨化規則包＋回收站＋金句卡＋閱讀統計＋E-Ink＋WebDAV＋AI 助手（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- |
| `app/src/main/java/com/k/reader/help/content/CleanRuleManager.kt` | 306 | 淨化規則包：三種綁定範圍（全域／依書名／依書源）；內建台灣常見廣告淨化規則包（11 條正則、簡繁並寫、冪等安裝）；正則預編譯快取（ConcurrentHashMap，命中零編譯）；單規則 512 上限＋壞規則自動跳過；刪除規則先進回收站；匯入匯出 JSON |
| `app/src/main/java/com/k/reader/help/storage/RecycleBinManager.kt` | 221 | 回收站：書籍／書源／訂閱源／淨化規則四形態存還（Gson payload）；30 天逾期自潔＋200 條容量上限；還原走 insertOrUpdate 冪等寫回；一鍵清空 |
| `app/src/main/java/com/k/reader/help/quote/GoldenQuoteManager.kt` | 373 | 文青金句卡片：1080×1620「青黛宣紙」風格純程序化繪製（StaticLayout 自動換行＋字級 54→34 收縮）；金句簿 CRUD（filesDir JSON）；MediaStore 落 Pictures/KReader/；FileProvider（com.k.reader.filen）系統分享 |
| `app/src/main/java/com/k/reader/help/statistics/ReadStatisticsManager.kt` | 273 | 閱讀統計：日為單位累計時長／字數（單次 60 分鐘掛機截斷）；今日／本週／本月／累計四期匯總＋完本集；14 天長條圖資料（零填空）；stats_hour_fmt／minute_fmt／words_fmt 格式化；365 天自潔 |
| `app/src/main/java/com/k/reader/help/config/EInkHelper.kt` | 119 | 墨水屏模式：高對比純黑白（白底黑字）；視窗動畫歸零（setWindowAnimations(0)）；翻頁建議「無動畫」；電子紙裝置偵測（型號關鍵字）＋首次啟動自動開啟；旗標跨啟動還原 |
| `app/src/main/java/com/k/reader/help/webdav/WebDavHelper.kt` | 601 | WebDAV 雲端備份：Basic 驗證＋PROPFIND/MKCOL/PUT/GET/DELETE 五動詞（XML 容錯正則解析）；三件套護航（限流＋動態 UA＋SSL 寬容——NAS 自簽日常）；備份 zip（書源＋訂閱源＋書籍含進度）落 /KReader/backup/；自動增量：WorkManager 每 12 小時＋保留 10 份逾限自舊刪；本地備份 Download/KReader/ |
| `app/src/main/java/com/k/reader/help/ai/AiAssistantHelper.kt` | 342 | AI 閱讀助手：OpenAI 相容端點（網址＋金鑰＋模型自填、端點自動正規化）；三動作（段落摘要／文言文白話翻譯／深度解讀——系統提示詞繁中寫死屬功能資料）；60 秒逾時＋1024 token 上限；三件套護航；結果一鍵複製 |
| `app/src/main/res/layout/dialog_ai_assistant.xml` | 162 | AI 助手對話框：輸入預填選中文字＋三動作按鈕（摘要實心主色、翻譯／解讀外框）＋ai_running 狀態列（預設 gone）＋捲動結果區＋一鍵複製；純主題屬性、RTL 友善 |

壓縮包：`K_Reader_Stage5_files.zip`（8 檔，路徑即 Legado 3.0 樹落點）。

## Stage 6 — README＋GitHub Actions 雲端編譯＋init_k_reader.sh（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- |
| `README.md` | 138 | 專案總覽：旗艦功能一覽（六階段 15 領域）、與原版完全隔離規格、隱私承諾（零統計 SDK／零遙測）；本地建置（init 用法＋gradlew 命令）與雲端編譯（Tag 推送＋四簽章 Secrets 表＋版本規則）指引；建置需求工具鏈表；六階段交付對照；GPL-3.0 授權聲明 |
| `.github/workflows/build.yml` | 140 | Tag（`v*`）觸發雲端編譯：JDK 17 temurin＋Gradle 建置快取；防呆檢查（偵測未轉換 Legado 樹即快速失敗並指引）；四 Secrets 注入正式簽章（缺省自動退回 debug 簽章）；Tag→versionName/versionCode（v1.2→10200 公式）；R8 full 編譯 → `K_Reader_v<版本>.apk`＋SHA-256 校驗檔 → 建置產物上傳＋GitHub Release 發佈（僅 Tag 觸發） |
| `init_k_reader.sh` | 337 | Legado 3.0 一鍵套名重構（八大步驟，冪等可重跑）：`io.legado.app → com.k.reader` 文字＋目錄雙軌改寫（涵蓋 main／test source set、絕不觸碰 .git）；根專案檔產出（KReader／AGP 8.5.2／Kotlin 1.9.24）；六階段疊加層落地；繁中字串「只增不刪」合併（暫存基底——疊加覆蓋不吞上游鍵）；FileProvider authority 統一 `com.k.reader.filen`；mipmap 點陣圖清剿；Gradle wrapper 對齊 8.7；殘留歸零＋關鍵檔到齊之完整性驗證 |
| `app/build.gradle.kts`（整合修正版） | 217 | FileProvider authority 由 `com.k.reader.fileprovider` 統一為 `com.k.reader.filen`——與 Stage 5 `GoldenQuoteManager.FILE_PROVIDER_AUTHORITY` 常數一字不差對齊（於疊加時覆寫 Stage 1 版本） |

壓縮包：`K_Reader_Stage6_files.zip`（4 檔，路徑即 Legado 3.0 樹落點；`app/build.gradle.kts` 為整合修正版，套用時覆蓋 Stage 1 版本）。

## Stage 7 — 隱私鎖＋台灣排版規範＋自動追更＋護眼色溫＋字詞快查（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- |
| `app/src/main/java/com/k/reader/help/lock/PrivacyLockManager.kt` | 346 | 隱私鎖：BiometricPrompt 生物辨識優先＋PIN 碼備援（4–6 位數字）；固定鹽＋SHA-256 雜湊儲存（明文永不落地）；鎖定時機三檔（退出立即／背景 30 秒／背景 5 分鐘）；連續失敗 5 次 30 秒冷卻（計數與截止時間戳跨啟動——重啟不可繞過）；lockIfNeeded 單一閘口掛 MainActivity.onResume |
| `app/src/main/java/com/k/reader/help/content/TextLayoutHelper.kt` | 272 | 台灣排版規範引擎：避頭尾禁則（行首／行尾禁則字元表＋U+2060 WORD JOINER 零寬黏連——斷行引擎不落刀）；連續標點擠壓建議（密度→textScaleX 0.92 微縮）；首行縮排兩全形字；連續空行合併；冪等設計（重複套用不疊加）；StaticLayout 預覽量測 |
| `app/src/main/java/com/k/reader/help/update/ScheduledUpdateManager.kt` | 254 | 自動追更排程：WorkManager 週期三檔（6／12／24 小時）；條件閘（僅 Wi-Fi→UNMETERED、僅充電→setRequiresCharging）；深夜靜默窗（23:00–07:00 retry 順延）；performUpdateCycle 閘門與結果記錄（上次追更 N 本有新章）；KEEP／UPDATE 雙策略（啟動不重起算、設定變更即時生效） |
| `app/src/main/java/com/k/reader/help/config/ColorTemperatureHelper.kt` | 293 | 護眼色溫：Tanner Helland 色溫→RGB 演算法（2800–6500K 連續可調、零資源檔）；三模式（手動／日落自動——月份餘弦內插日落時刻 17:15–18:45 不定位零隱私／停用）；亮度聯動（<30% 再暖 400K）；ColorMatrix 對角濾波掛 Paint；墨水屏（EInkHelper 串接）自動跳過 |
| `app/src/main/java/com/k/reader/help/dict/DictLookupHelper.kt` | 293 | 字詞快查三源：內建 60 條精選成語典（離線零延遲）＋萌典 API（教育部《重編國語辭典修訂本》釋義前 3 條）＋維基百科 REST 摘要；三件套護航（瀏覽器級標頭＋單源限流＋SSL 寬容重試）；24 小時 TTL 快取（重查零流量）；三源獨立成敗不互相污染 |
| `app/src/main/res/layout/dialog_privacy_pin.xml` | 67 | PIN 解鎖對話框：Material TextInputLayout（OutlinedBox＋counter 6 位）＋數字密碼鍵盤＋解鎖說明列；純主題屬性、RTL 友善；不可逃逸（setOnCancelListener 重開） |
| `app/src/main/res/layout/dialog_dict_lookup.xml` | 107 | 字詞快查對話框：查詢詞大字標題（主色）＋dict_searching 狀態列（預設 gone）＋320dp 上限捲動結果區（行距 1.5）＋一鍵複製釋義實心鈕；與 dialog_ai_assistant 同款視覺語言 |
| `app/src/main/res/values-zh-rTW/strings.xml`（更新版） | 341 | Stage 7 新增 45 鍵（241→286，只增不刪）：隱私鎖 12 鍵＋排版規範 7 鍵＋自動追更 10 鍵＋護眼色溫 8 鍵＋字詞快查 8 鍵；套用時覆蓋 Stage 1 版本（init 腳本「只增不刪」合併自動吸納上游既有鍵） |
| `init_k_reader.sh`（整合修正版） | 342 | Stage 7 擴充：關鍵檔清單納入五個新 Kotlin 模組（25 檔驗證）＋「七階段」字樣同步；套用時覆蓋 Stage 6 版本 |
| `README.md`（整合修正版） | 145 | Stage 7 收錄：旗艦功能一覽 20 領域（＋隱私防護／排版美學／自動化運維／護眼／字詞快查）、25 Kotlin 模組／286 鍵結構樹、七階段交付對照；套用時覆蓋 Stage 6 版本 |

壓縮包：`K_Reader_Stage7_files.zip`（10 檔，路徑即 Legado 3.0 樹落點；`strings.xml`／`init_k_reader.sh`／`README.md` 為整合修正版，套用時覆蓋前階段版本）。

## Stage 8 — 書內全文搜尋＋劃線筆記本＋閱讀目標＋定時日夜切換＋書架整理（已完成）

| 檔案 | 行數 | 說明 |
| --- | --- | --- |
| `app/src/main/java/com/k/reader/help/search/FullTextSearchHelper.kt` | 259 | 書內全文搜尋：章節內容注入介面（ContentLoader——不綁資料庫，無快取章節自動跳過）；雙針簡繁並搜（原關鍵字＋繁化關鍵字同時在原文上定位，位置零漂移）；純文字／正則雙模式（PatternSyntaxException 容錯＋正則預編譯 ConcurrentHashMap 快取）；前後各 20 字摘錄；300 筆上限護欄滿額即停；onProgress 每章進度回報（IO 執行緒） |
| `app/src/main/java/com/k/reader/help/note/HighlightNoteManager.kt` | 292 | 劃線筆記本：章內字元範圍＋內容快照＋三色分類（金句黃／疑問藍／重點紅）＋批註；金句卡直達（委託 GoldenQuoteManager——裱卡不必重選字）；Markdown（# 書名/## 章節/> 劃線）／純文字雙匯出，MediaStore 落 Downloads/KReader/；全簿 500 條容量最舊淘汰 |
| `app/src/main/java/com/k/reader/help/goal/ReadingGoalManager.kt` | 300 | 閱讀目標與提醒：分鐘／字數二擇一目標（0=停用）；進度直讀 ReadStatisticsManager.summaryToday（數據單源不另記帳）；streak 連續天數（今天沒讀不歸零）＋最長紀錄跨啟動持久化；達成通知（同日一次）＋晚間提醒（21:00 預設可調、整點比對）；NotificationChannel 可整類關閉，零後台服務 |
| `app/src/main/java/com/k/reader/help/config/DayNightScheduleHelper.kt` | 217 | 定時日夜切換：三模式（停用／固定時刻 20:00→06:00 可調／日出日落緯度概算——月份餘弦內插零定位權限，與色溫日落估算同族）；pollThemeChange 形態翻轉偵測（翻頁時分鐘級檢查，翻轉才 recreate）；墨水屏互斥一律日間；套用形態跨啟動還原 |
| `app/src/main/java/com/k/reader/help/shelf/ShelfOrganizeHelper.kt` | 224 | 書架整理助手：三桶體檢（完本／連載在讀／冷凍——30 天未翻頁預設可調）；五鍵排序（最近閱讀/加入時間/書名/作者）純函式零副作用；冷凍一鍵歸檔（群組編號冪等確保、避開既有分組）；Book 欄位寬容取樣（髒欄位不炸體檢） |
| `app/src/main/res/layout/dialog_fulltext_search.xml` | 137 | 全文搜尋對話框：Material TextInputLayout 關鍵字欄（64 字計數）＋正則開關＋fulltext_searching_zero 狀態列（預設 gone）＋320dp 捲動結果區＋搜尋全書實心鈕；純主題屬性、RTL 友善 |
| `app/src/main/res/layout/dialog_note_editor.xml` | 153 | 劃線筆記編輯對話框：劃線內容捲動預填＋批註多行輸入＋三色 RadioGroup 單選＋儲存（實心）／生成金句卡（外框）雙動作；與 AI 助手／字詞快查同款視覺語言 |
| `app/src/main/res/values-zh-rTW/strings.xml`（更新版） | 410 | Stage 8 新增 59 鍵（286→345，只增不刪）：全文搜尋 14 鍵＋劃線筆記 13 鍵＋閱讀目標 13 鍵＋日夜切換 6 鍵＋書架整理 13 鍵；套用時覆蓋 Stage 7 版本（init 腳本「只增不刪」合併自動吸納上游既有鍵） |
| `init_k_reader.sh`（整合修正版） | 347 | Stage 8 擴充：關鍵檔清單納入五個新 Kotlin 模組（30 檔驗證）＋「八階段」字樣同步；套用時覆蓋 Stage 7 版本 |
| `README.md`（整合修正版） | 152 | Stage 8 收錄：旗艦功能一覽 25 領域（＋檢索/筆記/習慣/主題/書架維運）、30 Kotlin 模組／345 鍵結構樹、八階段交付對照；套用時覆蓋 Stage 7 版本 |

壓縮包：`K_Reader_Stage8_files.zip`（10 檔，路徑即 Legado 3.0 樹落點；`strings.xml`／`init_k_reader.sh`／`README.md` 為整合修正版，套用時覆蓋前階段版本）。

## 交付完結

八階段全數交付完成（Stage 1~6 原規劃＋Stage 7／8 兩包旗艦增益）。終態使用流程：`init_k_reader.sh` 對純淨 Legado 3.0 樹套名重構 → 推送 GitHub → `git tag v1.2` → Actions 自動產出並發佈 `K_Reader_v1.2.apk`。
