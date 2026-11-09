#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 2 檔案驗證器"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE2_FILES = [
    "app/src/main/java/com/k/reader/help/http/UserAgentPool.kt",
    "app/src/main/java/com/k/reader/help/http/SourceRateLimiter.kt",
    "app/src/main/java/com/k/reader/help/http/SslTrustHelper.kt",
    "app/src/main/java/com/k/reader/help/TraditionalChineseHelper.kt",
    "app/src/main/java/com/k/reader/help/RandomTrialManager.kt",
    "app/src/main/res/layout/view_trial_bar.xml",
]
FORBIDDEN = ["TODO", "FIXME", "XXX", "待補充", "【"]

errors, warnings = [], []


def strip_kotlin(src: str) -> str:
    """單趟狀態機：正確剝除 Kotlin 註解／字串／字元字面值，僅留程式碼骨架。
    （逐層 regex 會被字串內的 /* 或 // 誤導，故必須狀態機。）"""
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
    return {el.get("name") for el in tree.getroot().findall("string")}


string_keys = load_strings()

# ── 1. 檔案存在與大小 ──
for rel in STAGE2_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 500, f"檔案過小（可疑不完整）：{rel}")

# ── 2. view_trial_bar.xml 良構性 + id + @string ──
xml_path = ROOT / "app/src/main/res/layout/view_trial_bar.xml"
if xml_path.is_file():
    try:
        tree = ET.parse(xml_path)
        ids = {el.get("name") for el in tree.iter() if el.tag == "item" and el.get("type") == "id"}
        # ElementTree 以 namespace 解析；改用字串掃描較穩
        raw = xml_path.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["trial_bar", "tv_change_book", "tv_add_shelf", "tv_trial_hint"]:
            check(need in ids, f"view_trial_bar.xml 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys, f"view_trial_bar.xml 引用了不存在的 string：{ref}")
    except ET.ParseError as e:
        errors.append(f"view_trial_bar.xml XML 解析失敗：{e}")

# ── 3. Kotlin 檔案檢查 ──
kt_expected_pkg = {
    "UserAgentPool.kt": "com.k.reader.help.http",
    "SourceRateLimiter.kt": "com.k.reader.help.http",
    "SslTrustHelper.kt": "com.k.reader.help.http",
    "TraditionalChineseHelper.kt": "com.k.reader.help",
    "RandomTrialManager.kt": "com.k.reader.help",
}
fallback_pairs = {}
for rel in STAGE2_FILES:
    if not rel.endswith(".kt"):
        continue
    p = ROOT / rel
    src = p.read_text(encoding="utf-8")
    name = p.name

    # 括號平衡與骨架（狀態機剝除字串／註解）
    stripped = strip_kotlin(src)

    # 禁用標記（「省略」排除術語「省略號」）
    for bad in FORBIDDEN:
        check(bad not in src, f"{name} 含禁用標記：{bad}")
    check(re.search(r"省略(?!號)", stripped) is None,
          f"{name} 含程式碼省略標記：省略")

    # package 宣告
    m = re.search(r'^package\s+([\w.]+)\s*$', src, re.M)
    check(m is not None, f"{name} 缺少 package 宣告")
    if m:
        check(m.group(1) == kt_expected_pkg[name],
              f"{name} package 應為 {kt_expected_pkg[name]}，實為 {m.group(1)}")

    for a, b in [("{", "}"), ("(", ")"), ("[", "]")]:
        check(stripped.count(a) == stripped.count(b),
              f"{name} 括號不平衡：{a}{stripped.count(a)} vs {b}{stripped.count(b)}")

    # R.string 引用存在性
    for ref in set(re.findall(r'R\.string\.(\w+)', src)):
        check(ref in string_keys, f"{name} 引用了不存在的 R.string.{ref}")

    # 保底字符表解析（僅 TraditionalChineseHelper）
    if name == "TraditionalChineseHelper.kt":
        block = re.search(r'FALLBACK_S2T[^=]*=\s*mapOf\(([\s\S]*?)\n\s*\)', src)
        check(block is not None, "找不到 FALLBACK_S2T mapOf 區塊")
        if block:
            entries = re.findall(r'"(\S)"\s*to\s*"(\S)"', block.group(1))
            check(len(entries) >= 700,
                  f"保底字符表數量異常：{len(entries)}（預期 ≥ 700）")
            for k, v in entries:
                if k in fallback_pairs and fallback_pairs[k] != v:
                    errors.append(f"保底表重複鍵且值不一致：{k}")
                if k == v:
                    warnings.append(f"保底表鍵值相同（無轉換意義）：{k}→{v}")
                fallback_pairs[k] = v
            print(f"  保底簡繁字符表：{len(entries)} 對")

# ── 4. RandomTrialManager 引用的 string（含格式參數）──
for ref, argn in [("random_trial_retrying", 1), ("random_trial_failed", 0),
                  ("random_trial_loading", 0), ("random_trial_added", 0)]:
    check(ref in string_keys, f"缺少字串：{ref}")

# ── 5. 跨檔案一致性：試讀條 id 在 Manager 中引用 ──
mgr = (ROOT / "app/src/main/java/com/k/reader/help/RandomTrialManager.kt").read_text(encoding="utf-8")
for rid in ["trial_bar", "tv_change_book", "tv_add_shelf"]:
    check(f"R.id.{rid}" in mgr, f"RandomTrialManager 未綁定 id：{rid}")

# ── 結果 ──
print(f"\n  字串資源：{len(string_keys)} 鍵")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 2 全部檔案驗證通過")
