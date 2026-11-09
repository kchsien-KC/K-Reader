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

## Stage 2 — 防封鎖三件套＋簡轉繁＋隨機試讀（本階段）

| 檔案 | 行數 | 說明 |
| --- | --- | --- | --- |
| `app/src/main/java/com/k/reader/help/http/UserAgentPool.kt` | 144 | 動態 UA 池：來源黏住、30 分鐘輪替、瀏覽器級標頭 |
| `app/src/main/java/com/k/reader/help/http/SourceRateLimiter.kt` | 112 | 單源 QPS 限流：700ms 間隔 + ±18% 抖動、suspend 等待 |
| `app/src/main/java/com/k/reader/help/http/SslTrustHelper.kt` | 150 | SSL 寬容重試通道 + TLSv1.2/1.3 相容層 |
| `app/src/main/java/com/k/reader/help/TraditionalChineseHelper.kt` | 455 | OpenCC s2twp 四段式詞典引擎 + 777 對內建保底字符表 + 「」『』排版優化 |
| `app/src/main/java/com/k/reader/help/RandomTrialManager.kt` | 403 | 隨機抽書直達正文、首章 200 字門檻重試 3 次、試讀條綁定、離場清除 |
| `app/src/main/res/layout/view_trial_bar.xml` | 77 | 試讀條（換一本試讀／一鍵加入書架） |

壓縮包：`K_Reader_Stage2_files.zip`（6 檔，路徑即 Legado 3.0 樹落點）。

## 待續階段

- Stage 3：書源／訂閱源完整鏈路檢核與智慧分組（直接消費 Stage 2 三件套）
- Stage 4：WebView 過盾、剪貼簿偵測、HTTP 聽書、智慧換源
- Stage 5：淨化規則包、回收站、金句卡、閱讀統計、E-Ink、WebDAV、AI 助手
- Stage 6：README、GitHub Actions（Tag 觸發雲端編譯 `K_Reader_v1.2.apk`）、init_k_reader.sh
