#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 6 檔案驗證器

Stage 6 範圍（README 規格）：
  · 專案 README（K-Reader/README.md）
  · GitHub Actions Tag 觸發雲端編譯 K_Reader_v1.2.apk（.github/workflows/build.yml）
  · init_k_reader.sh 一鍵套名重構腳本
  · 跨階段整合修正：app/build.gradle.kts FileProvider authority 統一為 com.k.reader.filen

驗證策略（三層）：
  1. 靜態檢查——檔案／語法（bash -n）／YAML 結構／內容規格斷言
  2. 跨階段整合——工作流↔build.gradle 屬性對齊、authority 常數對齊、關鍵檔到齊
  3. 功能實測——構造模擬 Legado 3.0 樹，實跑 init_k_reader.sh 兩次
     （冪等性），斷言套名／合併／授權／工具鏈／防誤傷全鏈路結果
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
DL = Path("/home/z/my-project/download")
STAGE6_FILES = [
    "README.md",
    ".github/workflows/build.yml",
    "init_k_reader.sh",
    "app/build.gradle.kts",
]
ZIP_PATH = DL / "K_Reader_Stage6_files.zip"
FORBIDDEN = ["TODO", "FIXME", "XXX", "待补充", "待補充", "【"]

errors, warnings = [], []


def check(cond, msg):
    if not cond:
        errors.append(msg)
    return bool(cond)


# ═══════════ 1. 檔案存在與大小 ═══════════
for rel in STAGE6_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 500, f"檔案過小（可疑不完整）：{rel}")

# ═══════════ 2. init_k_reader.sh 靜態檢查 ═══════════
script = ROOT / "init_k_reader.sh"
if script.is_file():
    raw = script.read_text(encoding="utf-8")
    check("\r" not in raw, "init_k_reader.sh 不應含 CRLF 換行")
    check(raw.startswith("#!/usr/bin/env bash"), "init_k_reader.sh 缺少 bash shebang")
    check(os.access(script, os.X_OK), "init_k_reader.sh 應有可執行權限")
    check("set -euo pipefail" in raw, "init_k_reader.sh 應 set -euo pipefail 嚴格模式")
    for bad in FORBIDDEN:
        check(bad not in raw, f"init_k_reader.sh 含禁用標記：{bad}")

    # bash -n 語法檢查（不執行）
    r = subprocess.run(["bash", "-n", str(script)],
                       capture_output=True, text=True)
    check(r.returncode == 0,
          f"init_k_reader.sh bash -n 語法錯誤：{r.stderr.strip()[:200]}")

    # --help 輸出（含八大步驟、用法；不含程式碼行）
    r = subprocess.run(["bash", str(script), "--help"],
                       capture_output=True, text=True)
    check(r.returncode == 0, "--help 應正常退出（exit 0）")
    out = r.stdout
    check("八大步驟" in out, "--help 應說明八大步驟")
    check("用法" in out, "--help 應含用法說明")
    check("set -euo pipefail" not in out, "--help 不應溢出程式碼本體（sed 範圍正確）")

    # 八大步驟齊備
    for i, kw in [
        (1, "套名重構"), (2, "根專案檔"), (3, "疊加層落地"),
        (4, "字串合併"), (5, "authority 統一"), (6, "點陣圖"),
        (7, "Gradle Wrapper 對齊"), (8, "完整性驗證"),
    ]:
        check(f"{i}/8" in raw, f"init_k_reader.sh 缺少步驟 {i}/8（{kw}）")

    # 關鍵邏輯錨點
    for needle, desc in [
        ("s/io\\.legado\\.app/com.k.reader/g", "缺少套名文字改寫 sed"),
        ("s#io/legado/app#com/k/reader#g", "缺少路徑形式改寫 sed"),
        ("*/java/io/legado/app", "缺少 io/legado/app 目錄偵測"),
        ("com.k.reader.filen", "缺少 authority 統一目標"),
        ("gradle-8.7-bin.zip", "缺少 wrapper 8.7 對齊"),
        ('rootProject.name = "KReader"', "缺少根專案設定產出"),
        ('id("com.android.application") version "8.5.2"', "缺少根 AGP 版本釘選"),
        ('id("org.jetbrains.kotlin.android") version "1.9.24"', "缺少根 Kotlin 版本釘選"),
        ("STRINGS_STASH", "缺少字串暫存（防疊加覆蓋合併結果）"),
        ("only增不刪" if False else "只增不刪", "註解應說明只增不刪原則"),
        ("--exclude=init_k_reader.sh", "殘留檢查應排除我方文檔"),
        ("--exclude=README.md", "殘留檢查應排除我方 README"),
        ("--exclude=build.gradle.kts", "殘留檢查應排除我方建置腳本"),
        ("--exclude=build.yml", "殘留檢查應排除我方工作流"),
        ("--exclude-dir=.git", "改寫應排除 .git"),
        ("不可對重疊層倉庫本身執行", "缺少重疊層自體防呆"),
        ("冪等", "說明應承諾冪等可重跑"),
        ("ET.indent", "字串合併應排版（Python 3.9+ 容錯）"),
    ]:
        check(needle in raw, f"init_k_reader.sh：{desc}")

    # 六階段關鍵檔清單（腳本內 KEY_FILES 應涵蓋 20 個 Kotlin＋字串＋工作流）
    kt_refs = re.findall(r'"(app/src/main/java/com/k/reader/[\w/]+\.kt)"', raw)
    check(len(kt_refs) == 20,
          f"init_k_reader.sh KEY_FILES 應針對 20 個 Kotlin 模組，實為 {len(kt_refs)}")
    for rel in kt_refs:
        check((ROOT / rel).is_file(), f"init_k_reader.sh KEY_FILES 指向不存在的檔案：{rel}")

# ═══════════ 3. build.yml 結構檢查 ═══════════
wf_path = ROOT / ".github/workflows/build.yml"
if wf_path.is_file():
    wf_raw = wf_path.read_text(encoding="utf-8")
    check("\r" not in wf_raw, "build.yml 不應含 CRLF 換行")
    for bad in FORBIDDEN:
        check(bad not in wf_raw, f"build.yml 含禁用標記：{bad}")
    try:
        import yaml
        doc = yaml.safe_load(wf_raw)
    except ImportError:
        doc = None
        warnings.append("未安裝 pyyaml——僅做文字結構檢查")
    except Exception as e:  # YAML 解析失敗
        doc = None
        errors.append(f"build.yml YAML 解析失敗：{e}")

    if doc is not None:
        trig = doc.get("on") if "on" in doc else doc.get(True)  # pyyaml 將 on 解析為 True
        check(isinstance(trig, dict), "build.yml 缺少 on 觸發區塊")
        if isinstance(trig, dict):
            check("push" in trig and "v*" in trig["push"]["tags"],
                  "觸發應為推送 v* 標籤")
            check("workflow_dispatch" in trig, "應支援手動 workflow_dispatch")
        perms = doc.get("permissions") or {}
        check(perms.get("contents") == "write",
              "permissions.contents 應為 write（發佈 Release）")
        jobs = doc.get("jobs") or {}
        check("build" in jobs, "缺少 build 工作")
        job = jobs.get("build") or {}
        check(job.get("runs-on") == "ubuntu-latest", "應跑 ubuntu-latest")
        check(job.get("timeout-minutes") == 60, "timeout 應為 60 分鐘")
        steps = job.get("steps") or []
        uses = [s.get("uses", "") for s in steps]
        names = [s.get("name", "") for s in steps]
        check(any(u.startswith("actions/checkout@v4") for u in uses), "缺 checkout@v4")
        check(any(u.startswith("actions/setup-java@v4") for u in uses), "缺 setup-java@v4")
        check("distribution: temurin" in wf_raw and "'17'" in wf_raw,
              "應使用 temurin JDK 17")
        check(any(u.startswith("gradle/actions/setup-gradle") for u in uses),
              "缺 setup-gradle（建置快取）")
        check(any(u.startswith("actions/upload-artifact@v4") for u in uses),
              "缺 upload-artifact@v4")
        check(any(u.startswith("softprops/action-gh-release@v2") for u in uses),
              "缺 gh-release@v2（Tag 發佈）")

        # 防呆檢查（偵測未轉換樹 → 明確失敗，而非 CI 內轉換）
        guard = next((s for s in steps if "防呆" in s.get("name", "")), None)
        check(guard is not None, "缺少防呆檢查步驟（未轉換 Legado 樹偵測）")
        if guard:
            check("hashFiles('app/src/main/java/io/legado/app/**') != ''"
                  in guard.get("if", ""), "防呆應以 hashFiles 偵測 io/legado/app")
            check("exit 1" in guard.get("run", ""), "防呆應 exit 1 快速失敗")

        # 版本規則＋編譯注入
        check("MAJOR * 10000 + MINOR * 100 + PATCH" in wf_raw,
              "缺少 versionCode 公式（major*10000+minor*100+patch）")
        check('NAME="1.2"' in wf_raw and 'CODE="10200"' in wf_raw,
              "缺少非 Tag 回退預設（1.2／10200）")
        check("-PK_VERSION_NAME=" in wf_raw, "編譯未注入 -PK_VERSION_NAME")
        check("-PK_VERSION_CODE=" in wf_raw, "編譯未注入 -PK_VERSION_CODE")
        check(":app:assembleRelease" in wf_raw, "缺少 assembleRelease 編譯任務")

        # 產物命名＋校驗＋發佈
        check("app/build/outputs/apk/release/app-release.apk" in wf_raw,
              "應自標準產出路徑取 APK")
        check('K_Reader_v${VER}.apk' in wf_raw, "產物應命名為 K_Reader_v<版本>.apk")
        check("sha256sum" in wf_raw, "應產出 SHA-256 校驗檔")
        check("startsWith(github.ref, 'refs/tags/')" in wf_raw,
              "Release 發佈應僅限 Tag 觸發")

        # 簽章 Secrets（四件）＋keystore.properties 欄位
        for secret in ["KREADER_KEYSTORE_BASE64", "KREADER_KEYSTORE_PASSWORD",
                       "KREADER_KEY_ALIAS", "KREADER_KEY_PASSWORD"]:
            check(secret in wf_raw, f"缺少簽章 Secret：{secret}")
        for field in ["storeFile=release.jks", "storePassword=", "keyAlias=", "keyPassword="]:
            check(field in wf_raw, f"keystore.properties 缺少欄位輸出：{field}")

# ═══════════ 4. README.md 內容檢查 ═══════════
readme = ROOT / "README.md"
if readme.is_file():
    rm = readme.read_text(encoding="utf-8")
    for bad in FORBIDDEN:
        check(bad not in rm, f"README.md 含禁用標記：{bad}")
    for needle, desc in [
        ("K.閱讀 v1.2 旗艦純淨版", "標題應為 K.閱讀 v1.2 旗艦純淨版"),
        ("疊加層", "應說明本倉庫為疊加層＋轉換腳本架構"),
        ("init_k_reader.sh", "應說明套名重構腳本用法"),
        ("./gradlew :app:assembleRelease", "應提供本地編譯命令"),
        ("git tag v1.2", "應提供雲端編譯 Tag 推送示範"),
        ("K_Reader_v1.2.apk", "應提及雲端編譯產物名"),
        ("KREADER_KEYSTORE_BASE64", "應列簽章 Secret 表"),
        ("com.k.reader", "應說明隔離 applicationId"),
        ("com.k.reader.filen", "應說明 FileProvider 授權"),
        ("GPL-3.0", "應聲明 GPL-3.0 授權"),
        ("8.7+", "應列 Gradle 8.7+ 工具鏈"),
        ("8.5.2", "應列 AGP 8.5.2"),
        ("1.9.24", "應列 Kotlin 1.9.24"),
        ("| JDK | 17", "應列 JDK 17"),
        ("arm64-v8a", "應說明單 ABI 策略"),
        ("minSdk 23", "應說明 minSdk 23"),
        ("零統計 SDK", "應有隱私承諾（零統計 SDK）"),
        ("回收站", "功能表應含回收站"),
        ("金句卡", "功能表應含金句卡"),
        ("E-Ink", "功能表應含 E-Ink"),
        ("WebDAV", "功能表應含 WebDAV"),
        ("AI", "功能表應含 AI 助手"),
        ("Stage 6", "應有六階段對照表"),
        ("與 Stage 5", "應記載 Stage 6 authority 整合修正（與 Stage 5 對齊）"),
        ("常數對齊", "應記載 Stage 6 authority 整合修正（常數對齊）"),
    ]:
        check(needle in rm, f"README.md：{desc}")

# ═══════════ 5. build.gradle.kts 整合修正（authority 對齊） ═══════════
gradle = ROOT / "app/build.gradle.kts"
if gradle.is_file():
    g = gradle.read_text(encoding="utf-8")
    check('manifestPlaceholders["FILE_PROVIDER_AUTHORITY"] = "${applicationId}.filen"' in g,
          "app/build.gradle.kts authority 應為 ${applicationId}.filen（Stage 6 修正）")
    check("${applicationId}.fileprovider" not in g,
          "app/build.gradle.kts 不應殘留 .fileprovider 舊值")

# 跨階段：與 Stage 5 GoldenQuoteManager 常數一字不差對齊
gq = ROOT / "app/src/main/java/com/k/reader/help/quote/GoldenQuoteManager.kt"
if gq.is_file() and gradle.is_file():
    check('const val FILE_PROVIDER_AUTHORITY = "com.k.reader.filen"' in gq.read_text(encoding="utf-8"),
          "GoldenQuoteManager authority 常數應為 com.k.reader.filen")
    # 展開後一致：applicationId=com.k.reader（build.gradle 另行斷言）
    check('applicationId = "com.k.reader"' in g, "applicationId 應為 com.k.reader")

# 跨階段：工作流屬性名 ↔ build.gradle findProperty 名對齊
if wf_path.is_file() and gradle.is_file():
    w = wf_path.read_text(encoding="utf-8")
    check('findProperty("K_VERSION_NAME")' in g, "build.gradle 應讀取 K_VERSION_NAME 屬性")
    check('findProperty("K_VERSION_CODE")' in g, "build.gradle 應讀取 K_VERSION_CODE 屬性")
    check('"1.2"' in g and "10200" in g, "build.gradle 應有本地預設版本 1.2／10200")
    # 字串資源基線未被破壞
    sp = ROOT / "app/src/main/res/values-zh-rTW/strings.xml"
    n_keys = len(ET.parse(sp).getroot().findall("string"))
    check(n_keys == 241, f"字串資源應維持 241 鍵基線，實為 {n_keys}")

# ═══════════ 6. 交付包檢查（內容與樹逐位元組一致） ═══════════
check(ZIP_PATH.is_file(), f"缺少交付包：{ZIP_PATH.name}")
if ZIP_PATH.is_file():
    with zipfile.ZipFile(ZIP_PATH) as zf:
        names = zf.namelist()
        for rel in STAGE6_FILES:
            check(rel in names, f"交付包缺少成員：{rel}")
        check(len(names) == len(STAGE6_FILES),
              f"交付包應恰含 {len(STAGE6_FILES)} 檔，實為 {len(names)}")
        for rel in STAGE6_FILES:
            if rel in names:
                zbytes = zf.read(rel)
                tpath = ROOT / rel
                if tpath.is_file():
                    check(zbytes == tpath.read_bytes(),
                          f"交付包成員與原始樹不一致（未同步打包）：{rel}")
                check(len(zbytes) > 500, f"交付包成員過小：{rel}")

# ═══════════ 7. 功能實測：模擬 Legado 樹 → init_k_reader.sh 兩次 ═══════════
def run_func_test():
    if not script.is_file():
        return
    tmp = tempfile.mkdtemp(prefix="kreader-stage6-")
    mock = Path(tmp) / "legado"
    try:
        # ── 構造模擬樹 ──
        (mock / "app/src/main/java/io/legado/app/data/entities").mkdir(parents=True)
        (mock / "app/src/test/java/io/legado/app").mkdir(parents=True)
        (mock / "app/src/main/res/values").mkdir(parents=True)
        (mock / "app/src/main/res/values-zh-rTW").mkdir(parents=True)
        (mock / "app/src/main/res/mipmap-hdpi").mkdir(parents=True)
        (mock / "app/src/main/res/mipmap-anydpi-v26").mkdir(parents=True)
        (mock / ".github/workflows").mkdir(parents=True)
        (mock / "gradle/wrapper").mkdir(parents=True)
        (mock / ".git/objects").mkdir(parents=True)

        (mock / "app/src/main/java/io/legado/app/App.kt").write_text(
            "package io.legado.app\n\n"
            "import io.legado.app.data.entities.Book\n\n"
            "class App {\n    fun load(b: Book) = b.name\n}\n", encoding="utf-8")
        (mock / "app/src/main/java/io/legado/app/data/entities/Book.kt").write_text(
            "package io.legado.app.data.entities\n\n"
            "data class Book(var name: String)\n", encoding="utf-8")
        (mock / "app/src/test/java/io/legado/app/BookTest.kt").write_text(
            "package io.legado.app\n\n"
            "import io.legado.app.data.entities.Book\n\n"
            "class BookTest {\n    fun t() = Book(\"t\").name\n}\n", encoding="utf-8")
        (mock / "app/src/main/AndroidManifest.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:label="@string/app_name">\n'
            '        <provider\n'
            '            android:name="androidx.core.content.FileProvider"\n'
            '            android:authorities="io.legado.app.fileprovider"\n'
            '            android:exported="false"\n'
            '            android:grantUriPermissions="true" />\n'
            '        <provider\n'
            '            android:name="androidx.startup.InitializationProvider"\n'
            '            android:authorities="${applicationId}.androidx-startup"\n'
            '            android:exported="false" />\n'
            '    </application>\n'
            '</manifest>\n', encoding="utf-8")
        (mock / "app/src/main/res/values/strings.xml").write_text(
            '<resources>\n    <string name="app_name">閱讀</string>\n'
            '    <string name="legado_only">上游專屬鍵</string>\n'
            '    <string name="cancel">舊取消</string>\n</resources>\n', encoding="utf-8")
        (mock / "app/src/main/res/values-zh-rTW/strings.xml").write_text(
            '<resources>\n    <string name="legado_tw">上游繁中鍵</string>\n'
            '    <string name="cancel">舊取消</string>\n</resources>\n', encoding="utf-8")
        (mock / "app/src/main/res/mipmap-hdpi/ic_launcher.png").write_bytes(b"PNGFAKE")
        (mock / "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").write_text(
            "UPSTREAM_ICON", encoding="utf-8")
        (mock / ".github/workflows/upstream.yml").write_text("upstream", encoding="utf-8")
        (mock / "settings.gradle.kts").write_text(
            'rootProject.name = "legado"\ninclude(":app")\n', encoding="utf-8")
        (mock / "build.gradle.kts").write_text("// legado root\n", encoding="utf-8")
        (mock / "app/build.gradle.kts").write_text(
            "// legado app module（namespace io.legado.app）\n", encoding="utf-8")
        (mock / "README.md").write_text("# legado（上游）\n", encoding="utf-8")
        (mock / "gradle/wrapper/gradle-wrapper.properties").write_text(
            "distributionUrl=https\\://services.gradle.org/distributions/"
            "gradle-7.6-bin.zip\n", encoding="utf-8")
        (mock / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
        (mock / "gradlew").chmod(0o755)
        git_file = mock / ".git/objects/abc"
        git_file.write_text("io.legado.app object", encoding="utf-8")
        (mock / "app/PATHREF.txt").write_text(
            "source at io/legado/app here", encoding="utf-8")

        # ── 第一次執行 ──
        r1 = subprocess.run(["bash", str(script), str(mock)],
                             capture_output=True, text=True)
        check(r1.returncode == 0,
              f"功能實測第一次執行應成功：{r1.stderr.strip()[:300]}")
        check("2 個 source set" in r1.stdout or "2 個 source set" in r1.stderr,
              "套名應搬移 main＋test 兩個 source set")

        def A(cond, msg):
            check(cond, f"功能實測：{msg}")

        A((mock / "app/src/main/java/com/k/reader/App.kt").is_file(), "App.kt 未搬移")
        A("package com.k.reader" in (mock / "app/src/main/java/com/k/reader/App.kt").read_text(encoding="utf-8"),
          "package 未改寫")
        A("import com.k.reader.data.entities.Book" in (mock / "app/src/main/java/com/k/reader/App.kt").read_text(encoding="utf-8"),
          "import 未改寫")
        A((mock / "app/src/main/java/com/k/reader/data/entities/Book.kt").is_file(), "子包未搬移")
        A((mock / "app/src/test/java/com/k/reader/BookTest.kt").is_file(), "test source set 未搬移")
        A(not (mock / "app/src/main/java/io").exists(), "io/ 殘留未清")

        man = (mock / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        A("com.k.reader.filen" in man, "manifest authority 未統一")
        A("io.legado.app.fileprovider" not in man, "舊 authority 殘留")
        A("${applicationId}.androidx-startup" in man, "androidx-startup 被誤改")

        A('rootProject.name = "KReader"' in (mock / "settings.gradle.kts").read_text(encoding="utf-8"),
          "根專案設定未產出")
        rootb = (mock / "build.gradle.kts").read_text(encoding="utf-8")
        A("8.5.2" in rootb and "1.9.24" in rootb, "根 build.gradle 版本釘選缺失")

        A((mock / "app/src/main/java/com/k/reader/help/http/UserAgentPool.kt").is_file(),
          "疊加層 UserAgentPool 未落地")
        A((mock / "app/src/main/java/com/k/reader/help/ai/AiAssistantHelper.kt").is_file(),
          "疊加層 AiAssistantHelper 未落地")
        A((mock / ".github/workflows/build.yml").is_file(), "工作流未落地")
        A(not (mock / ".github/workflows/upstream.yml").exists(), "上游工作流未刪除")
        A("K.閱讀" in (mock / "README.md").read_text(encoding="utf-8"), "README 未替換")
        appb = (mock / "app/build.gradle.kts").read_text(encoding="utf-8")
        A('manifestPlaceholders["FILE_PROVIDER_AUTHORITY"] = "${applicationId}.filen"' in appb,
          "app/build.gradle.kts 未替換為 filen 版")
        A("android.enableR8.fullMode=true" in (mock / "gradle.properties").read_text(encoding="utf-8"),
          "gradle.properties 未替換")
        A(not (mock / "app/src/main/res/mipmap-hdpi/ic_launcher.png").exists(),
          "mipmap 點陣圖未刪除")
        A("K.閱讀" in (mock / "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").read_text(encoding="utf-8"),
          "啟動圖示未覆蓋為我方版本")
        wp = (mock / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        A("gradle-8.7-bin.zip" in wp, "wrapper 未對齊 8.7")
        A(git_file.read_text(encoding="utf-8") == "io.legado.app object",
          ".git 內容被改寫（重大違規）")
        pr = (mock / "app/PATHREF.txt").read_text(encoding="utf-8")
        A("io/legado/app" not in pr and "com/k/reader here" in pr,
          "路徑形式引用未改寫")

        # 字串合併：只增不刪＋同名覆寫＋基線 241
        mpath = mock / "app/src/main/res/values-zh-rTW/strings.xml"
        m = {el.get("name"): (el.text or "")
             for el in ET.parse(mpath).getroot().findall("string")}
        A("legado_tw" in m, "上游繁中鍵被刪除（違反只增不刪）")
        A(m.get("cancel") == "取消", "同名鍵應以 K.閱讀版本覆寫")
        A(len(m) == 242, f"合併後應 242 鍵（241＋legado_tw），實為 {len(m)}")

        # ── 第二次執行（冪等） ──
        r2 = subprocess.run(["bash", str(script), str(mock)],
                            capture_output=True, text=True)
        check(r2.returncode == 0, "功能實測第二次執行（冪等）應成功")
        check("已套名" in r2.stdout, "第二次執行應走「已套名」快速路徑")
        m2 = {el.get("name") for el in ET.parse(mpath).getroot().findall("string")}
        check(len(m2) == 242,
              f"冪等執行後鍵數應穩定 242，實為 {len(m2)}（疊加重複）")
        A(not (mock / "app/src/main/java/io").exists(), "冪等執行後 io/ 復活")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

run_func_test()

# ═══════════ 結果 ═══════════
print("  Stage 6 檔案：README／工作流／init 腳本／build.gradle 整合修正")
print("  功能實測：模擬 Legado 樹雙跑（冪等）全鏈路斷言")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 6 全部檔案驗證通過")
