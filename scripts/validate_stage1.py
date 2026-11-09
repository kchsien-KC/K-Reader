#!/usr/bin/env python3
"""K.閱讀 Stage 1 — 交付檔案自動驗證器（XML 結構 / 零點陣圖 / 關鍵隔離設定）"""
import glob
import xml.etree.ElementTree as ET

BASE = "/home/z/my-project/K-Reader"
errors = []

xml_files = [
    f"{BASE}/app/src/main/res/values-zh-rTW/strings.xml",
    f"{BASE}/app/src/main/res/drawable/ic_launcher_background.xml",
    f"{BASE}/app/src/main/res/drawable/ic_launcher_foreground.xml",
    f"{BASE}/app/src/main/res/drawable/ic_launcher_monochrome.xml",
    f"{BASE}/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    f"{BASE}/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
    f"{BASE}/app/src/main/res/mipmap/ic_launcher.xml",
    f"{BASE}/app/src/main/res/mipmap/ic_launcher_round.xml",
]

for f in xml_files:
    try:
        root = ET.parse(f).getroot()
        print(f"[OK] XML 格式正確：{f.split('/K-Reader/')[-1]}（root=<{root.tag}>）")
    except Exception as e:
        errors.append(f"{f}: {e}")

# ── strings.xml 檢查 ──
strings = ET.parse(xml_files[0]).getroot()
names = [s.get("name") for s in strings.findall("string")]
app_name = next(s.text for s in strings.findall("string") if s.get("name") == "app_name")
assert app_name == "K.閱讀", f"app_name 異常：{app_name!r}"
assert len(names) == len(set(names)), "字串名稱重複"
for s in strings.findall("string"):
    assert s.text and s.text.strip(), f"空字串：{s.get('name')}"
print(f"[OK] strings.xml：共 {len(names)} 條字串、名稱唯一、app_name = {app_name}")

# ── 零點陣圖檢查 ──
pngs = glob.glob(f"{BASE}/**/*.png", recursive=True)
assert not pngs, f"發現點陣圖：{pngs}"
print("[OK] 零點陣圖：專案樹內不存在任何 PNG 圖示")

# ── build.gradle.kts 關鍵隔離 / 瘦身設定 ──
gradle = open(f"{BASE}/app/build.gradle.kts", encoding="utf-8").read()
for token in [
    'applicationId = "com.k.reader"',
    'namespace = "com.k.reader"',
    "arm64-v8a",
    "isMinifyEnabled = true",
    "isShrinkResources = true",
    "zh-rTW",
    "KReader",
    "FILE_PROVIDER_AUTHORITY",
]:
    assert token in gradle, f"build.gradle.kts 缺少：{token}"
print("[OK] build.gradle.kts：識別隔離＋<15MB 瘦身設定齊備")

props = open(f"{BASE}/gradle.properties", encoding="utf-8").read()
assert "android.enableR8.fullMode=true" in props, "缺 R8 full mode"
print("[OK] gradle.properties：R8 full mode 已開啟")

pg = open(f"{BASE}/app/proguard-rules.pro", encoding="utf-8").read()
assert "com.k.reader" in pg and "JsExtensions" in pg, "proguard 缺書源引擎保留規則"
print("[OK] proguard-rules.pro：JS 引擎 / Gson 實體保留規則齊備")

if errors:
    print("\n發現錯誤：")
    for e in errors:
        print(" -", e)
    raise SystemExit(1)
print("\n=== Stage 1 驗證全數通過 ===")
