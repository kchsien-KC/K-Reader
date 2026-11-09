# ═══════════════════════════════════════════════════════════════════
#  K.閱讀 v1.2 — R8 發佈保留規則（app/proguard-rules.pro）
#  對應 applicationId / namespace：com.k.reader
#  原則：僅保留「反射 / 序列化 / JS 引擎 / WebView 橋接」剛需，
#        其餘全數交由 R8 full mode 深度最佳化（< 15MB 體積底線）。
# ═══════════════════════════════════════════════════════════════════

# ── 基礎屬性（Gson 泛型簽名 / 可讀堆疊追蹤）──
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-allowaccessmodification

# ── Kotlin ──
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ── @Keep 註解（明確標記之類別一律保留）──
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <init>();
    @androidx.annotation.Keep <methods>;
}

# ── Gson 資料模型（書籍 / 書源 / 訂閱源 / 淨化規則 / 閱讀進度，
#    皆以反射 + Unsafe 生成，欄位不可改名）──
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,includedescriptorclasses class com.k.reader.data.entities.** { *; }
-keep,includedescriptorclasses class com.k.reader.data.entities.**$* { *; }

# ── 書源 JS 引擎（Rhino 於執行期以類別名稱反射綁定）──
-keep class com.k.reader.help.JsExtensions { *; }
-keep class com.k.reader.help.JsExtensions$* { *; }
-keep class com.k.reader.help.coroutine.Coroutine { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.**

# ── HTTP 聽書引擎（書源 httpTTS 以類別名稱反射實例化，Stage 4）──
-keep class com.k.reader.tts.** { *; }

# ── WebView JS 橋接（過盾 Cookie 回寫介面，Stage 4）──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── 列舉（Gson / Bundle 反射取值）──
-keepclassmembers enum com.k.reader.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ── OkHttp / Okio / TLS 相容（SSL 寬容放行需保留之 javax 標記）──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ── jsoup / json-path / slf4j（json-path 傳入之 logging 抽象）──
-dontwarn org.jsoup.**
-dontwarn org.slf4j.**
-dontwarn org.slf4j.spi.**
-dontwarn com.sun.jmx.**
-dontwarn com.google.errorprone.**
-dontwarn com.jayway.jsonpath.**
-dontwarn net.minidev.**

# ── LiveEventBus / BRVAH / Markwon / Coil / nanohttpd
#    出廠 consumer rules 已內建保險，此處僅抑制雜訊 ──
-dontwarn io.github.jeremyliao.**
-dontwarn io.github.cymchad.**
-dontwarn fi.iki.elonen.**

# 註：刻意「不」以 assumenosideeffects 移除 android.util.Log ——
#     書源除錯（日誌頁）是台灣書源社群的核心工作流，
#     移除日誌的縮身收益 < 1% 卻會破壞除錯體驗，故全數保留。
