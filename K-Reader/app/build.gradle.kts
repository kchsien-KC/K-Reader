/*
 * ═══════════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 旗艦純淨版 — app 模組建置腳本（Kotlin DSL）
 * ═══════════════════════════════════════════════════════════════════
 *  基底架構：開源「閱讀（Legado 3.0）」純淨重建（借鑑 Sigma / R / MD3
 *  分支之效能與防封鎖優點），不沿用任何舊版殘留補丁。
 *
 *  隔離規格（與原版 io.legado.app 完美並存、資料零衝突）：
 *   · applicationId = com.k.reader
 *   · namespace     = com.k.reader（套名重構由 init_k_reader.sh 一鍵完成）
 *   · FileProvider  = com.k.reader.filen（${FILE_PROVIDER_AUTHORITY}；
 *     與 Stage 5 GoldenQuoteManager.FILE_PROVIDER_AUTHORITY 常數對齊，
 *     並由 init_k_reader.sh 統一改寫 AndroidManifest 之 authority）
 *   · 備份 / 匯出隔離目錄 = 外部儲存 KReader/（BuildConfig.BACKUP_DIR_NAME）
 *
 *  體積與效能底線（安裝檔 < 15MB、冷啟動 < 0.5s）：
 *   · 僅保留 arm64-v8a 單一 ABI（不啟用 ABI splits，直接產出單一 APK）
 *   · R8 full mode + isShrinkResources + 語系裁切（zh-rTW / en）
 *   · 零統計 SDK、零點陣圖啟動圖示、不內建大型 TTF 與離線語音包
 *
 *  工具鏈需求（root 專案檔由 init_k_reader.sh 產出）：
 *   Gradle 8.7+ / AGP 8.5.x / Kotlin 1.9.24 / JDK 17
 *   repositories：google() + mavenCentral() + maven("https://jitpack.io")
 * ═══════════════════════════════════════════════════════════════════
 */
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// ── CI 版本注入（GitHub Actions 依 Git Tag 以 -PK_VERSION_NAME / -PK_VERSION_CODE
//    傳入；本地建置使用預設值 v1.2）──
val kVersionName: String = (findProperty("K_VERSION_NAME") as String?) ?: "1.2"
val kVersionCode: Int = (findProperty("K_VERSION_CODE") as String?)?.toInt() ?: 10200

// ── 發佈簽章：keystore.properties（CI Secret 注入產生）；
//    不存在時退回 debug 簽章，確保本地無金鑰亦可完整建置 ──
val keystoreFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystoreFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.k.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.k.reader"
        minSdk = 23            // 23–25 使用純向量 fallback 圖示（零點陣圖規範）
        targetSdk = 34
        versionCode = kVersionCode
        versionName = kVersionName

        // 獨立 FileProvider 授權名稱（AndroidManifest 由 init_k_reader.sh 統一改寫為
        // com.k.reader.filen；與 GoldenQuoteManager.FILE_PROVIDER_AUTHORITY 對齊）
        manifestPlaceholders["FILE_PROVIDER_AUTHORITY"] = "${applicationId}.filen"

        // 本地備份與匯出隔離目錄（外部儲存 /KReader/，程式碼層引用）
        buildConfigField("String", "BACKUP_DIR_NAME", "\"KReader\"")
        // 專屬發佈網址（關於頁 / 更新檢查）
        buildConfigField("String", "GITHUB_RELEASE_URL", "\"https://github.com\"")

        // 極致最小化：僅保留主流 64 位元 ARM（arm64-v8a）
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // 語系裁切：僅繁體中文（台灣）與英文
        //（註：AGP 8.6+ 可改用 androidResources { localeFilters } 新 DSL）
        resourceConfigurations.addAll(listOf("zh-rTW", "en"))
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 深度混淆
            isShrinkResources = true        // 資源收縮
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 無金鑰檔時退回 debug 簽章，保證本地可建置
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // 不啟用 ABI splits：abiFilters 已鎖定單一 arm64-v8a，直接輸出單一通用 APK
    //（與上游 Legado 啟用 splits 之作法刻意相反，避免多 APK 碎片化）
    splits {
        abi {
            isEnable = false
        }
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.BACKUP_DIR_NAME 等欄位
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 本地 libs（init 腳本清理後通常為空，保留掛載點）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // ── Kotlin 協程 ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── AndroidX（與上游 Legado 3.0 對齊之最小集合）──
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")           // 應用鎖定
    implementation("androidx.browser:browser:1.8.0")               // Custom Tabs
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")                   // 聽書後台播放
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.12.0")                // 過盾 WebView（Stage 4）
    implementation("androidx.work:work-runtime-ktx:2.9.1")         // 自動備份任務（Stage 5）
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // ── Google ──
    implementation("com.google.android.material:material:1.12.0")  // MD3 介面
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ── 網路（防封鎖核心，Stage 2 擴展 UA 池 / 限流 / SSL 寬容）──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // ── 書源規則引擎 ──
    implementation("com.github.gedoor:rhino-android:1.7.14")       // JS 引擎（JitPack）
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.jayway.jsonpath:json-path:2.9.0")
    implementation("org.apache.commons:commons-text:1.12.0")

    // ── 訂閱（RSS）顯示 ──
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:image-coil:4.6.2")

    // ── 圖片載入（輕量 Coil，不引入肥大 SDK）──
    implementation("io.coil-kt:coil:2.7.0")

    // ── UI / 事件匯流排 ──
    implementation("io.github.cymchad:BaseRecyclerViewAdapterHelper:3.0.14")
    implementation("io.github.jeremyliao:live-event-bus:1.8.0")

    // ── 內建 Web 伺服器（區域網路書源編輯 / 匯入）──
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 刻意「不」引入：統計/分析 SDK、離線 TTS 語音包、內建 TTF 字體、
    // Glide 等重複功能之函式庫 —— 此為 < 15MB 體積底線的關鍵紀律。
}
