#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 3 檔案驗證器"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE3_FILES = [
    "app/src/main/java/com/k/reader/help/source/BookSourceHealthManager.kt",
    "app/src/main/java/com/k/reader/help/source/RssSourceVerifyManager.kt",
    "app/src/main/java/com/k/reader/help/source/SourceGroupManager.kt",
    "app/src/main/res/layout/dialog_source_verify.xml",
]
FORBIDDEN = ["TODO", "FIXME", "XXX", "待補充", "【"]

errors, warnings = [], []


def strip_kotlin(src: str) -> str:
    """單趟狀態機：正確剝除 Kotlin 註解／字串／字元字面值，僅留程式碼骨架。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            i = n if j == -1 else j            # 保留換行
        elif c == "/" and nxt == "*":
            j = src.find("*/", i + 2)
            i = n if j == -1 else j + 2
            out.append(" ")
        elif c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j == -1 else j + 3
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"':
                        break
                    j += 1
                i = (j + 1) if j < n else n
            out.append("S")
        elif c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = (j + 1) if j < n else n
            out.append("C")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check(cond, msg):
    if not cond:
        errors.append(msg)


def load_strings():
    tree = ET.parse(ROOT / "app/src/main/res/values-zh-rTW/strings.xml")
    return {el.get("name"): (el.text or "") for el in tree.getroot().findall("string")}


string_map = load_strings()
string_keys = set(string_map)

# ── 1. 檔案存在與大小 ──
for rel in STAGE3_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 500, f"檔案過小（可疑不完整）：{rel}")

# ── 2. dialog_source_verify.xml 良構性 + id + @string ──
xml_path = ROOT / "app/src/main/res/layout/dialog_source_verify.xml"
if xml_path.is_file():
    try:
        ET.parse(xml_path)
        raw = xml_path.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["progress_verify", "tv_verify_progress", "tv_verify_detail"]:
            check(need in ids, f"dialog_source_verify.xml 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys, f"dialog_source_verify.xml 引用了不存在的 string：{ref}")
        expect_refs = {"verify_running"}
        check(expect_refs <= set(re.findall(r'@string/(\w+)', raw)),
              "dialog_source_verify.xml 應引用 verify_running 作為初始狀態文案")
    except ET.ParseError as e:
        errors.append(f"dialog_source_verify.xml XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE3_FILES:
    if not rel.endswith(".kt"):
        continue
    p = ROOT / rel
    src = p.read_text(encoding="utf-8")
    name = p.name
    kt_sources[name] = src
    stripped = strip_kotlin(src)

    for bad in FORBIDDEN:
        check(bad not in src, f"{name} 含禁用標記：{bad}")
    check(re.search(r"省略(?!號)", stripped) is None,
          f"{name} 含程式碼省略標記：省略")

    m = re.search(r'^package\s+([\w.]+)\s*$', src, re.M)
    check(m is not None, f"{name} 缺少 package 宣告")
    if m:
        check(m.group(1) == "com.k.reader.help.source",
              f"{name} package 應為 com.k.reader.help.source，實為 {m.group(1)}")

    for a, b in [("{", "}"), ("(", ")"), ("[", "]")]:
        check(stripped.count(a) == stripped.count(b),
              f"{name} 括號不平衡：{a}{stripped.count(a)} vs {b}{stripped.count(b)}")

    # R.string / R.id 引用存在性（以剝殼後的程式骨架掃描——註解中
    # 出現的「R.string.group_*」示例不應計入引用）
    layout_raw = xml_path.read_text(encoding="utf-8") if xml_path.is_file() else ""
    layout_ids = set(re.findall(r'@\+id/(\w+)', layout_raw))
    for ref in set(re.findall(r'R\.string\.(\w+)', stripped)):
        check(ref in string_keys, f"{name} 引用了不存在的 R.string.{ref}")
    for ref in set(re.findall(r'R\.id\.(\w+)', stripped)):
        check(ref in layout_ids,
              f"{name} 引用了 layout 不存在的 R.id.{ref}")

# ── 4. 跨檔一致性：直接消費 Stage 2 三件套 ──
health = kt_sources.get("BookSourceHealthManager.kt", "")
rss = kt_sources.get("RssSourceVerifyManager.kt", "")
group = kt_sources.get("SourceGroupManager.kt", "")

# 4a. 書源檢核 → 三件套 + 分組協作 + 對話框綁定
for needle, desc in [
    ("SourceRateLimiter.awaitPermit", "書源檢核未經單源限流"),
    ("UserAgentPool.clearSticky", "書源檢核未接入 UA 身分輪替"),
    ("SslTrustHelper.isSslError", "書源檢核未接入 SSL 錯誤分類"),
    ("SourceGroupManager.syncBookSourceFailureGroup", "書源檢核未同步失效分組"),
    ("R.layout.dialog_source_verify", "書源檢核未綁定進度對話框布局"),
    ("R.id.progress_verify", "對話框未綁定進度條 id"),
    ("R.id.tv_verify_progress", "對話框未綁定狀態列 id"),
    ("R.id.tv_verify_detail", "對話框未綁定明細列 id"),
    ("kotlinx.coroutines.sync.Semaphore", "書源檢核未使用限額並發"),
]:
    check(needle in health, f"BookSourceHealthManager.kt：{desc}")

# 4b. 訂閱源檢核 → 瀏覽器標頭 + 寬容通道 + 限流 + 分組協作
for needle, desc in [
    ("UserAgentPool.browserHeaders", "訂閱源檢核未使用瀏覽器級標頭"),
    ("SslTrustHelper.tolerantClient", "訂閱源檢核未接入 SSL 寬容通道"),
    ("SourceRateLimiter.awaitPermit", "訂閱源檢核未經單源限流"),
    ("UserAgentPool.clearSticky", "訂閱源檢核未接入 UA 身分輪替"),
    ("SourceGroupManager.syncRssSourceFailureGroup", "訂閱源檢核未同步失效分組"),
    ("kotlinx.coroutines.sync.Semaphore", "訂閱源檢核未使用限額並發"),
]:
    check(needle in rss, f"RssSourceVerifyManager.kt：{desc}")

# 4c. 智慧分組 → 八個前瞻鍵 + 欄位寫回 + 失效清理
for key in ["group_novel", "group_audio", "group_comic", "group_gallery",
            "group_video", "group_info", "group_other", "group_failed"]:
    check(f"R.string.{key}" in group, f"SourceGroupManager.kt 未引用 R.string.{key}")
for needle, desc in [
    ("bookSourceGroup", "智慧分組未寫回書源分組欄位"),
    ("sourceGroup", "智慧分組未寫回訂閱源分組欄位"),
    ("fun clearFailedSources", "缺少一鍵清空失效分組 API"),
    ("fun classifyBookSource", "缺少書源類型推斷"),
    ("fun classifyRssSource", "缺少訂閱源類型推斷"),
    ("fun domainLabelOf", "缺少網域標籤推導"),
]:
    check(needle in group, f"SourceGroupManager.kt：{desc}")

# 4d. 檢核步驟文案消費（步驟標籤 + 結果統計；verify_running 由布局初始文案消費）
for key in ["verify_all", "verify_step_search",
            "verify_step_toc", "verify_step_content", "verify_result",
            "verify_no_valid_source", "verify_search_fail", "verify_toc_fail",
            "verify_content_ok", "verify_content_short"]:
    check(f"R.string.{key}" in health, f"BookSourceHealthManager.kt 未引用 R.string.{key}")
for key in ["rss_verify_ok", "rss_verify_fail"]:
    check(f"R.string.{key}" in rss, f"RssSourceVerifyManager.kt 未引用 R.string.{key}")

# ── 5. 規格一致性：150 字門檻（字串 ↔ 常數） ──
short_val = string_map.get("verify_content_short", "")
check("150" in short_val, "字串 verify_content_short 未含 150 門檻規格值")
check(re.search(r'MIN_CONTENT_CHARS\s*=\s*150', health) is not None,
      "BookSourceHealthManager.kt 的 MIN_CONTENT_CHARS 應為 150（對應字串規格）")

# ── 6. 格式參數個數抽查 ──
check(re.search(r'R\.string\.verify_result\s*,\s*\w+\s*,\s*\w+', health) is not None,
      "verify_result（%1$d、%2$d）呼叫應帶兩個參數")
check(re.search(r'R\.string\.verify_content_short\s*,\s*contentChars', health) is not None,
      "verify_content_short（%1$d）呼叫應帶 contentChars 參數")
check(re.search(r'R\.string\.verify_content_ok\s*,\s*contentChars', health) is not None,
      "verify_content_ok（%1$d）呼叫應帶 contentChars 參數")

# ── 結果 ──
print(f"  字串資源：{len(string_keys)} 鍵")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 3 全部檔案驗證通過")
