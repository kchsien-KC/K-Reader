/*
 * ═══════════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 旗艦純淨版 — app 模組建置腳本（Kotlin DSL）
 * ═══════════════════════════════════════════════════════════════════
 *  基底架構：開源「閱讀（Legado 3.0）」純淨重建（借鑑 Sigma / R / MD3
 *  分支之效能與防封鎖優點），不沿用任何舊版殘留補丁。
 *
 *  工具鏈（與 2025-12 Legado 基準樹完全對齊——雲端編譯一次過）：
 *   Gradle 8.11.1 / AGP 8.10.1 / Kotlin 2.2.0 / KSP / JDK 17
 *   compileSdk 35 / minSdk 23 / targetSdk 35
 *   插件：parcelize（@Parcelize 18 檔）＋androidx.room（schema）
 *   ＋ksp（Room 編譯器＋Glide 編譯器——kapt 已退役）
 *
 *  隔離規格（與原版 io.legado.app 完美並存、資料零衝突）：
 *   · applicationId = com.k.reader（套名重構由 init_k_reader.sh 一鍵完成）
 *   · FileProvider  = com.k.reader.filen（manifest 由 init 統一改寫）
 *   · 備份 / 匯出隔離目錄 = 外部儲存 KReader/（BuildConfig.BACKUP_DIR_NAME）
 *
 *  K.閱讀紀律（對照上游刻意裁剪）：
 *   · 僅保留 arm64-v8a 單一 ABI（不啟用 splits——單一通用 APK）
 *   · 語系裁切（zh-rTW / en）＋資源收縮＋R8 full mode
 *   · 零統計 SDK：不上 firebase-analytics／perf（隨 google-services 一併退場）
 *   · Cronet 採 in-tree API jar 編譯（cronetlib/），不做 build-time 下載
 *     ——執行期預設走 OkHttp；需要 Cronet 引擎時手動執行 downloadCronet
 *   · 不啟用 applicationIdSuffix（乾淨的 com.k.reader 主套名）
 * ═══════════════════════════════════════════════════════════════════
 */
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("androidx.room")
    id("com.google.devtools.ksp")
}

// ── CI 版本注入（GitHub Actions 依 Git Tag 以 -PK_VERSION_NAME / -PK_VERSION_CODE
//    傳入；本地建置使用預設值 1.2）──
val kVersionName: String = (findProperty("K_VERSION_NAME") as String?) ?: "1.2"
val kVersionCode: Int = (findProperty("K_VERSION_CODE") as String?)?.toInt() ?: 10200

// ── Cronet 版本（gradle.properties 傳入；缺省回內建值——buildConfigField 用）──
val cronetVersion: String = (findProperty("CronetVersion") as String?) ?: "128.0.6613.40"
val cronetMainVersion: String = (findProperty("CronetMainVersion") as String?) ?: "128.0.0.0"

// ── 發佈簽章：keystore.properties（CI Secret 注入產生）；
//    不存在時退回 debug 簽章，確保本地無金鑰亦可完整建置 ──
val keystoreFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystoreFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKeystore) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.k.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.k.reader"
        minSdk = 23            // 23–25 使用純向量 fallback 圖示（零點陣圖規範）
        targetSdk = 35
        versionCode = kVersionCode
        versionName = kVersionName

        // 獨立 FileProvider 授權名稱（與 GoldenQuoteManager 常數對齊；
        // AndroidManifest 由 init_k_reader.sh 統一改寫為 com.k.reader.filen）
        manifestPlaceholders["FILE_PROVIDER_AUTHORITY"] = "${applicationId}.filen"
        manifestPlaceholders["app_name"] = "@string/app_name"

        // Cronet 版本字串（執行期引擎檢查用）
        buildConfigField("String", "Cronet_Version", "\"$cronetVersion\"")
        buildConfigField("String", "Cronet_Main_Version", "\"$cronetMainVersion\"")

        // 本地備份與匯出隔離目錄（外部儲存 /KReader/，程式碼層引用）
        buildConfigField("String", "BACKUP_DIR_NAME", "\"KReader\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.incremental" to "true",
                    "room.expandProjection" to "true",
                    "room.schemaLocation" to "$projectDir/schemas",
                )
            }
        }

        // 極致最小化：僅保留主流 64 位元 ARM（arm64-v8a）
        ndk {
            abiFilters.add("arm64-v8a")
        }

        // 語系裁切：僅繁體中文（台灣）與英文
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
                "proguard-rules.pro",        // 上游戰功規則（完整樹保留）
                "cronet-proguard-rules.pro", // Cronet 引擎規則（上游）
                "proguard-kreader.pro"       // K.閱讀 55 模組 keep 規則（疊加層）
            )
            // 無金鑰檔時退回 debug 簽章，保證本地／雲端無金鑰皆可建置
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

    // 上游 flavor 架構（manifest 之 APP_CHANNEL_VALUE 佔位符依賴）
    flavorDimensions.add("mode")
    productFlavors {
        create("app") {
            dimension = "mode"
            manifestPlaceholders["APP_CHANNEL_VALUE"] = "app"
        }
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.BACKUP_DIR_NAME 等欄位
        viewBinding = true   // 上源 ViewBinding（body 佈局直達）
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Room schema 匯出（androidx.room 插件）
room {
    schemaDirectory("$projectDir/schemas")
}

// Room 之 KSP 參數（與上游一致）
ksp {
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
    arg("room.generateKotlin", "false")
}

dependencies {
    // ── Desugaring（java.time 等 new API on minSdk 23）──
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // ── Kotlin 協程 ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ── 圖像處理 Toolkit（上游同款）──
    implementation("com.github.TomasValenta:renderscript-intrinsics-replacement-toolkit:8eaa829ddd")

    // ── AndroidX（與上游 Legado 基準對齊）──
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.2.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.webkit:webkit:1.13.0")

    // ── Google ──
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.google.code.gson:gson:2.13.1")

    // ── Lifecycle／Media ──
    implementation("androidx.lifecycle:lifecycle-common-java8:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")

    // ── Splitties（上游同款）──
    implementation("com.louiscad.splitties:splitties-appctx:3.0.0")
    implementation("com.louiscad.splitties:splitties-systemservices:3.0.0")
    implementation("com.louiscad.splitties:splitties-views:3.0.0")

    // ── Room（KSP 編譯器——kapt 退役）──
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // ── 事件匯流排 ──
    implementation("com.github.michaellee123:LiveEventBus:1.8.14")

    // ── 書源規則引擎（modules 子專案＋三方庫）──
    implementation("org.jsoup:jsoup:1.16.2")
    implementation("com.jayway.jsonpath:json-path:2.9.0")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    implementation(project(":modules:book"))
    implementation(project(":modules:rhino"))

    // ── 網路（防封鎖核心）──
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation(fileTree(mapOf("dir" to "cronetlib", "include" to listOf("*.jar", "*.aar"))))
    implementation("com.google.protobuf:protobuf-javalite:4.26.1")

    // ── Glide（KSP 編譯器＋SVG＋整合件）──
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    implementation("com.github.bumptech.glide:recyclerview-integration:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.github.qoqa:glide-svg:4.0.2")

    // ── Web 伺服器（區域網路書源編輯）──
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // ── 二維碼／顏色選擇／壓縮解壓（上游同款）──
    implementation("com.github.jenly1314:zxing-lite:3.3.0")
    implementation("com.jaredrummler:colorpicker:1.1.0")
    implementation("me.zhanghai.android.libarchive:library:1.1.6")

    // ── Apache／MarkDown／簡繁轉換／加解密（書源生態）──
    implementation("org.apache.commons:commons-text:1.13.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:image-glide:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("com.github.liuyueyi.quick-chinese-transfer:quick-transfer-core:0.2.16")
    implementation("cn.hutool:hutool-crypto:5.8.22")

    // ── K.閱讀增益模組追加（上游未用、55 模組所需）──
    implementation("androidx.biometric:biometric:1.1.0")           // 隱私鎖（Stage 7）
    implementation("androidx.work:work-runtime-ktx:2.9.1")         // 自動追更／WebDAV 排程

    // 刻意「不」引入：firebase-analytics／perf（零統計 SDK 紀律）、
    // leakcanary、測試組——此為純淨版之關鍵紀律。
}
