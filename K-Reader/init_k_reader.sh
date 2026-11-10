#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
#  K.閱讀 v1.2 旗艦純淨版 — Legado 3.0 一鍵套名重構腳本（init_k_reader.sh）
# ═══════════════════════════════════════════════════════════════════
#  用途：
#    對一份「純淨的開源閱讀（Legado 3.0）原始碼樹」執行套名重構，
#    一次性產出可完整編譯的 K.閱讀（com.k.reader）原始碼樹。
#
#  執行八大步驟：
#    1. 套名重構   io.legado.app → com.k.reader（目錄搬移＋全樹文字改寫）
#    2. 根專案檔   產出 settings.gradle.kts / 根 build.gradle.kts
#    3. 疊加層落地 app 模組建置腳本＋七階段全部 Kotlin／資源檔
#    4. 字串合併   values-zh-rTW/strings.xml「只增不刪」合併（保留上游既有鍵）
#    5. 授權統一   FileProvider authority 統一為 com.k.reader.filen
#    6. 圖示純淨化 刪除上游 mipmap 點陣圖殘留（貫徹零點陣圖規範）
#    7. 對齊工具鏈 Gradle Wrapper → 8.7（AGP 8.5.x / Kotlin 1.9.24 / JDK 17）
#    8. 完整性驗證 殘留引用歸零＋七階段關鍵檔到齊
#
#  用法：
#    ./init_k_reader.sh <path/to/legado-3.0-tree>
#    bash /path/to/overlay/init_k_reader.sh <path/to/legado-3.0-tree>
#    ./init_k_reader.sh --help
#
#  特性：
#    · 冪等可重跑——已轉換的樹重跑僅重新疊加，結果不變
#    · 絕不改寫 .git／build／.gradle 目錄
#    · 僅需 bash＋GNU coreutils＋python3（Linux／WSL／GitHub Runner 皆內建）
#
#  雲端編譯：轉換後的樹推送 GitHub，打上 v1.2 標籤，
#  .github/workflows/build.yml 自動產出 K_Reader_v1.2.apk。
# ═══════════════════════════════════════════════════════════════════
set -euo pipefail

OVERLAY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── 輸出輔助（非 TTY 時自動去色） ──────────────────────────────
if [ -t 1 ]; then
    C_STEP=$'\033[1;36m'; C_GOOD=$'\033[1;32m'; C_WARN=$'\033[1;33m'
    C_BAD=$'\033[1;31m';  C_OFF=$'\033[0m'
else
    C_STEP=""; C_GOOD=""; C_WARN=""; C_BAD=""; C_OFF=""
fi
step() { printf '%s── %s ──%s\n' "$C_STEP" "$1" "$C_OFF"; }
good() { printf '  %s✔%s %s\n' "$C_GOOD" "$C_OFF" "$1"; }
warn() { printf '  %s⚠%s %s\n' "$C_WARN" "$C_OFF" "$1"; }
die()  { printf '%s✘ %s%s\n' "$C_BAD" "$1" "$C_OFF" >&2; exit 1; }

usage() {
    sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

# ═════════════════ 0. 前置檢查 ═════════════════
case "${1:-}" in --help|-h) usage ;; esac
[ $# -ge 1 ] || die "缺少參數。用法：./init_k_reader.sh <path/to/legado-3.0-tree>（--help 看說明）"

for tool in sed grep find cp mv rm mkdir python3; do
    command -v "$tool" >/dev/null 2>&1 \
        || die "缺少必要工具：$tool（請於 Linux / WSL 環境執行）"
done
sed --version >/dev/null 2>&1 \
    || warn "偵測到非 GNU sed（macOS BSD？），建議改用 Linux / WSL 以確保改寫語法相容"

T="$(cd "$1" && pwd)" || die "目標路徑不存在：$1"
[ -d "$T/app" ] || die "目標不是 Legado 樹（缺少 app/ 目錄）：$T"
if [ "$T" = "$OVERLAY_DIR" ]; then
    die "不可對重疊層倉庫本身執行——請以 Legado 3.0 樹為目標"
fi
[ -w "$T" ] || die "目標目錄不可寫：$T"

printf 'K.閱讀 v1.2 — Legado 3.0 套名重構\n'
printf '  目標樹：%s\n  重疊層：%s\n' "$T" "$OVERLAY_DIR"

# find 剪枝參數（陣列形式，避免路徑萬用字元意外展開）
PRUNE=( -not -path '*/.git/*' -not -path '*/.gradle/*'
        -not -path '*/build/*' -not -path '*/.idea/*' )

# 七階段關鍵檔（驗證用）
KEY_FILES=(
    "app/src/main/java/com/k/reader/help/http/UserAgentPool.kt"
    "app/src/main/java/com/k/reader/help/http/SourceRateLimiter.kt"
    "app/src/main/java/com/k/reader/help/http/SslTrustHelper.kt"
    "app/src/main/java/com/k/reader/help/TraditionalChineseHelper.kt"
    "app/src/main/java/com/k/reader/help/RandomTrialManager.kt"
    "app/src/main/java/com/k/reader/help/source/BookSourceHealthManager.kt"
    "app/src/main/java/com/k/reader/help/source/RssSourceVerifyManager.kt"
    "app/src/main/java/com/k/reader/help/source/SourceGroupManager.kt"
    "app/src/main/java/com/k/reader/help/web/CloudFlareHelper.kt"
    "app/src/main/java/com/k/reader/help/clipboard/ClipboardSourceDetector.kt"
    "app/src/main/java/com/k/reader/help/tts/HttpTtsEngine.kt"
    "app/src/main/java/com/k/reader/service/HttpReadAloudService.kt"
    "app/src/main/java/com/k/reader/help/source/SmartSourceSwitcher.kt"
    "app/src/main/java/com/k/reader/help/content/CleanRuleManager.kt"
    "app/src/main/java/com/k/reader/help/storage/RecycleBinManager.kt"
    "app/src/main/java/com/k/reader/help/quote/GoldenQuoteManager.kt"
    "app/src/main/java/com/k/reader/help/statistics/ReadStatisticsManager.kt"
    "app/src/main/java/com/k/reader/help/config/EInkHelper.kt"
    "app/src/main/java/com/k/reader/help/webdav/WebDavHelper.kt"
    "app/src/main/java/com/k/reader/help/ai/AiAssistantHelper.kt"
    "app/src/main/java/com/k/reader/help/lock/PrivacyLockManager.kt"
    "app/src/main/java/com/k/reader/help/content/TextLayoutHelper.kt"
    "app/src/main/java/com/k/reader/help/update/ScheduledUpdateManager.kt"
    "app/src/main/java/com/k/reader/help/config/ColorTemperatureHelper.kt"
    "app/src/main/java/com/k/reader/help/dict/DictLookupHelper.kt"
    "app/src/main/res/values-zh-rTW/strings.xml"
    ".github/workflows/build.yml"
)

# ═════════════════ 1. 套名重構 io.legado.app → com.k.reader ═════════════════
step "1/8 套名重構 io.legado.app → com.k.reader"

legacy_count="$(find "$T" -type d -path '*/java/io/legado/app' \
    "${PRUNE[@]}" 2>/dev/null | wc -l)"

if [ "$legacy_count" -eq 0 ]; then
    good "已套名（或非 Legado 樹）——跳過重命名，僅重新疊加"
else
    # 1a. 全樹文字改寫（跳過二進位與 VCS／建置目錄）
    rewrite_count=0
    while IFS= read -r f; do
        sed -i -e 's/io\.legado\.app/com.k.reader/g' \
               -e 's#io/legado/app#com/k/reader#g' "$f"
        rewrite_count=$((rewrite_count + 1))
    done < <(grep -rlI --exclude-dir=.git --exclude-dir=.gradle \
                 --exclude-dir=build --exclude-dir=.idea \
                 -e 'io\.legado\.app' -e 'io/legado/app' "$T" 2>/dev/null || true)
    good "文字改寫：${rewrite_count} 個檔案（package／import／authority／文件）"

    # 1b. 目錄搬移（涵蓋 main／test 等所有 source set）
    find "$T" -type d -path '*/java/io/legado/app' "${PRUNE[@]}" 2>/dev/null \
    | while IFS= read -r d; do
        java_root="$(dirname "$(dirname "$(dirname "$d")")")"
        mkdir -p "$java_root/com/k/reader"
        find "$d" -mindepth 1 -maxdepth 1 2>/dev/null \
        | while IFS= read -r item; do
            mv -f "$item" "$java_root/com/k/reader/"
        done
    done
    find "$T" -type d -path '*/io/legado*' -empty -delete 2>/dev/null || true
    find "$T" -type d -name io -empty -delete 2>/dev/null || true
    good "目錄搬移：${legacy_count} 個 source set → com/k/reader/"
fi

# ═════════════════ 2. 根專案檔產出 ═════════════════
step "2/8 產出根專案檔（settings.gradle.kts / build.gradle.kts）"

cat > "$T/settings.gradle.kts" <<'EOF'
// K.閱讀 v1.2 — 根專案設定（init_k_reader.sh 產出）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "KReader"
include(":app")
EOF

cat > "$T/build.gradle.kts" <<'EOF'
// K.閱讀 v1.2 — 根專案（init_k_reader.sh 產出）
// 工具鏈：Gradle 8.7+ / AGP 8.5.x / Kotlin 1.9.24 / JDK 17
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}
EOF
good "根專案檔已產出（rootProject.name = KReader，模組 :app）"

# ═════════════════ 3. 疊加層落地 ═════════════════
step "3/8 疊加層落地（七階段交付檔）"

for entry in app gradle.properties .github README.md init_k_reader.sh; do
    [ -e "$OVERLAY_DIR/$entry" ] || die "重疊層缺少必要項目：$entry"
done

# 先暫存既有繁中字串檔（上游原版或前次合併結果）——
# 疊加層複製會覆蓋同名檔，步驟 4 改以暫存檔為合併基底，
# 確保「只增不刪」跨多次執行成立（冪等）
STRINGS_DEST="$T/app/src/main/res/values-zh-rTW/strings.xml"
STRINGS_STASH=""
if [ -f "$STRINGS_DEST" ]; then
    STRINGS_STASH="$(mktemp)"
    cp "$STRINGS_DEST" "$STRINGS_STASH"
fi

rm -rf "$T/.github"          # 移除上游工作流，換用 K.閱讀雲端編譯流程
cp -a "$OVERLAY_DIR/app/." "$T/app/"
cp -a "$OVERLAY_DIR/.github" "$T/.github"
cp -a "$OVERLAY_DIR/gradle.properties" "$OVERLAY_DIR/README.md" \
      "$OVERLAY_DIR/init_k_reader.sh" "$T/"
chmod +x "$T/init_k_reader.sh"
if [ -f "$T/gradlew" ]; then chmod +x "$T/gradlew"; fi
good "app 建置腳本＋25 個 Kotlin 模組＋資源＋工作流＋文件已落地"

# ═════════════════ 4. 字串資源合併（只增不刪） ═════════════════
step "4/8 繁中字串合併（values-zh-rTW/strings.xml）"

mkdir -p "$T/app/src/main/res/values-zh-rTW"
BASE_XML="$(mktemp)"
if [ -n "$STRINGS_STASH" ]; then
    cp "$STRINGS_STASH" "$BASE_XML"
else
    printf '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n' > "$BASE_XML"
fi
python3 - "$OVERLAY_DIR/app/src/main/res/values-zh-rTW/strings.xml" \
            "$BASE_XML" \
            "$T/app/src/main/res/values-zh-rTW/strings.xml" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ours_path, base_path, dest_path = (Path(a) for a in sys.argv[1:4])

def load_keys(p):
    root = ET.parse(p).getroot()
    return {el.get("name"): el for el in root.findall("string")}

base = load_keys(base_path)    # 暫存之上游（或前次合併）既有鍵
add  = load_keys(ours_path)    # K.閱讀 241 鍵
merged = dict(base)
merged.update(add)              # 同名鍵以 K.閱讀版本覆寫

root = ET.Element("resources")
for el in merged.values():
    root.append(el)
try:
    ET.indent(root)             # Python 3.9+；舊版容錯跳過
except AttributeError:
    pass
ET.ElementTree(root).write(dest_path, encoding="utf-8", xml_declaration=True)

overridden = sum(1 for k in add if k in base)
print(f"    既有 {len(base)} 鍵 ＋ K.閱讀 {len(add)} 鍵"
      f"（覆寫同名 {overridden}）→ 合併後共 {len(merged)} 鍵")
PYEOF
rm -f "$STRINGS_STASH" "$BASE_XML"
good "字串合併完成——既有鍵全數保留，K.閱讀鍵新增／覆寫"

# ═════════════════ 5. FileProvider authority 統一 ═════════════════
step "5/8 FileProvider authority 統一為 com.k.reader.filen"

manifest="$(find "$T/app/src/main" -name AndroidManifest.xml 2>/dev/null | head -1 || true)"
if [ -n "$manifest" ]; then
    sed -i -E 's#android:authorities="[^"]*([Ff]ile[Pp]rovider|FILE_PROVIDER_AUTHORITY)[^"]*"#android:authorities="com.k.reader.filen"#g' "$manifest"
    good "AndroidManifest authority 已統一（其他 provider 如 androidx-startup 不受影響）"
else
    warn "未尋得 AndroidManifest.xml——跳過 authority 統一"
fi

# ═════════════════ 6. 啟動圖示點陣圖清剿 ═════════════════
step "6/8 mipmap 點陣圖殘留清剿（零點陣圖規範）"

raster_count="$(find "$T/app/src/main/res" -path '*/mipmap*' -type f \
    \( -name '*.png' -o -name '*.jpg' -o -name '*.webp' \) 2>/dev/null | wc -l)"
if [ "$raster_count" -gt 0 ]; then
    find "$T/app/src/main/res" -path '*/mipmap*' -type f \
        \( -name '*.png' -o -name '*.jpg' -o -name '*.webp' \) -delete
    good "已刪除 ${raster_count} 個上游點陣圖（純向量 ic_launcher XML 全版本覆蓋）"
else
    good "無點陣圖殘留"
fi

# ═════════════════ 7. Gradle Wrapper 對齊 8.7 ═════════════════
step "7/8 Gradle Wrapper 對齊 8.7"

wrapper_props="$T/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$wrapper_props" ]; then
    sed -i -E 's#^distributionUrl=.*#distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip#' "$wrapper_props"
    good "distributionUrl → gradle-8.7-bin.zip"
else
    warn "缺少 gradle/wrapper/gradle-wrapper.properties——請手動執行 gradle wrapper"
fi
if [ ! -x "$T/gradlew" ]; then
    warn "缺少 gradlew——請手動執行 gradle wrapper 產生"
fi

# ═════════════════ 8. 完整性驗證 ═════════════════
step "8/8 完整性驗證"

FAIL=0
# 我方文檔／設定（init 腳本、README、建置腳本、工作流）合法提及舊包名
# io.legado.app（說明套名來源與防呆檢測），不計入殘留檢查
leftover="$(grep -rlI --exclude-dir=.git --exclude-dir=.gradle \
                 --exclude-dir=build --exclude-dir=.idea \
                 --exclude=init_k_reader.sh --exclude=README.md \
                 --exclude=build.gradle.kts --exclude=build.yml \
                 -e 'io\.legado\.app' "$T" 2>/dev/null | head -3 || true)"
if [ -n "$leftover" ]; then
    printf '  %s✘%s 殘留 io.legado.app 引用：\n' "$C_BAD" "$C_OFF"
    while IFS= read -r f; do printf '      %s\n' "$f"; done <<< "$leftover"
    FAIL=1
else
    good "io.legado.app 殘留引用：0"
fi

missing=()
for f in "${KEY_FILES[@]}"; do
    [ -f "$T/$f" ] || missing+=("$f")
done
if [ "${#missing[@]}" -eq 0 ]; then
    good "七階段關鍵檔 ${#KEY_FILES[@]}/${#KEY_FILES[@]} 到齊"
else
    for f in "${missing[@]}"; do
        printf '  %s✘%s 缺少：%s\n' "$C_BAD" "$C_OFF" "$f"
    done
    FAIL=1
fi

grep -q 'include(":app")' "$T/settings.gradle.kts" \
    || { printf '  %s✘%s settings.gradle.kts 未包含 :app 模組\n' "$C_BAD" "$C_OFF"; FAIL=1; }
grep -q 'com.k.reader.filen' "$T/app/build.gradle.kts" \
    || { printf '  %s✘%s app/build.gradle.kts 缺少 filen 授權設定\n' "$C_BAD" "$C_OFF"; FAIL=1; }
[ -f "$T/README.md" ] || { printf '  %s✘%s 缺少 README.md\n' "$C_BAD" "$C_OFF"; FAIL=1; }
[ ! -e "$T/app/src/main/java/io" ] \
    || { printf '  %s✘%s io/ 舊目錄仍存在\n' "$C_BAD" "$C_OFF"; FAIL=1; }

[ "$FAIL" -eq 0 ] || die "完整性驗證未通過——請檢查上方 ✘ 項目"

# ═════════════════ 收尾報告 ═════════════════
printf '\n'
good "K.閱讀 v1.2 套名重構完成"
printf '\n'
printf '  本地建置：\n'
printf '    cd %s\n' "$T"
printf '    ./gradlew :app:assembleRelease\n'
printf '    產物：app/build/outputs/apk/release/app-release.apk\n'
printf '\n'
printf '  雲端編譯（GitHub Actions）：\n'
printf '    git add -A && git commit -m "K.閱讀 v1.2"\n'
printf '    git tag v1.2 && git push origin main --tags\n'
printf '    → 自動產出並發佈 K_Reader_v1.2.apk（詳見 README.md）\n'
