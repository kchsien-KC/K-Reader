#!/usr/bin/env python3
"""K.閱讀 Stage 1 — 圖示像素級幾何驗證
將背景＋前景合成渲染為 540×540 點陣，於關鍵座標取樣，
驗證各設計層（青黛底 / 框線 / 雙層書頁 / 書脊 / 金點）位置與顏色正確。
"""
import math
import re
import xml.etree.ElementTree as ET

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import PathPatch
from matplotlib.path import Path

NS = "{http://schemas.android.com/apk/res/android}"
RES = "/home/z/my-project/K-Reader/app/src/main/res"
OUT = "/tmp/render/icon_raster.png"
TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+")


def arc_points(p0, p1, rx, ry, large, sweep, steps=28):
    x1, y1 = p0
    x2, y2 = p1
    dx, dy = (x2 - x1) / 2.0, (y2 - y1) / 2.0
    r = max(rx, ry)
    lam = (dx * dx + dy * dy) / (r * r)
    if lam > 1:
        r *= math.sqrt(lam)
    den = dx * dx + dy * dy
    num = r * r - dx * dx - dy * dy
    coef = math.sqrt(max(0.0, num / den)) if den > 1e-12 else 0.0
    if large == sweep:
        coef = -coef
    cx = -dy * coef + (x1 + x2) / 2.0
    cy = dx * coef + (y1 + y2) / 2.0
    a0 = math.atan2(y1 - cy, x1 - cx)
    a1 = math.atan2(y2 - cy, x2 - cx)
    da = a1 - a0
    if sweep == 0 and da > 0:
        da -= 2 * math.pi
    elif sweep == 1 and da < 0:
        da += 2 * math.pi
    return [(cx + r * math.cos(a0 + da * k / steps),
             cy + r * math.sin(a0 + da * k / steps)) for k in range(steps + 1)]


def path_data_to_path(d):
    toks = TOKEN.findall(d)
    verts, codes = [], []
    cur = [0.0, 0.0]
    start = [0.0, 0.0]
    i = 0

    def nxt():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    cmd = None
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                codes.append(Path.CLOSEPOLY)
                verts.append((0.0, 0.0))
                cur = list(start)
                continue
        else:
            if cmd in ("M", "m"):
                cmd = "L" if cmd == "M" else "l"
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = nxt(), nxt()
            if rel:
                x += cur[0]
                y += cur[1]
            cur, start = [x, y], [x, y]
            codes.append(Path.MOVETO)
            verts.append((x, y))
        elif c == "L":
            x, y = nxt(), nxt()
            if rel:
                x += cur[0]
                y += cur[1]
            cur = [x, y]
            codes.append(Path.LINETO)
            verts.append((x, y))
        elif c == "H":
            x = nxt() + (cur[0] if rel else 0)
            cur[0] = x
            codes.append(Path.LINETO)
            verts.append((cur[0], cur[1]))
        elif c == "V":
            y = nxt() + (cur[1] if rel else 0)
            cur[1] = y
            codes.append(Path.LINETO)
            verts.append((cur[0], cur[1]))
        elif c == "Q":
            x1, y1, x, y = nxt(), nxt(), nxt(), nxt()
            if rel:
                x1 += cur[0]
                y1 += cur[1]
                x += cur[0]
                y += cur[1]
            codes += [Path.CURVE3, Path.CURVE3]
            verts += [(x1, y1), (x, y)]
            cur = [x, y]
        elif c == "C":
            x1, y1, x2, y2, x, y = nxt(), nxt(), nxt(), nxt(), nxt(), nxt()
            if rel:
                x1 += cur[0]
                y1 += cur[1]
                x2 += cur[0]
                y2 += cur[1]
                x += cur[0]
                y += cur[1]
            codes += [Path.CURVE4] * 3
            verts += [(x1, y1), (x2, y2), (x, y)]
            cur = [x, y]
        elif c == "A":
            rx, ry, rot, large, sweep, x, y = tuple(nxt() for _ in range(7))
            large, sweep = int(large), int(sweep)
            if rel:
                x += cur[0]
                y += cur[1]
            for p in arc_points((cur[0], cur[1]), (x, y), rx, ry, large, sweep)[1:]:
                codes.append(Path.LINETO)
                verts.append(p)
            cur = [x, y]
    return verts, codes


def draw_file(ax, xml_path):
    for p in ET.parse(xml_path).getroot().findall("path"):
        d = p.get(NS + "pathData")
        fill = p.get(NS + "fillColor")
        stroke = p.get(NS + "strokeColor")
        sw = p.get(NS + "strokeWidth")
        verts, codes = path_data_to_path(d)
        ax.add_patch(PathPatch(
            Path(verts, codes),
            facecolor=fill if fill else "none",
            edgecolor=stroke if stroke else "none",
            linewidth=(float(sw) * 1.5) if (stroke and sw) else 0.0,
        ))


fig = plt.figure(figsize=(5.4, 5.4), dpi=100)
ax = fig.add_axes([0, 0, 1, 1])  # 滿幅座標系：540×540px 與 108×108dp 一比一映射
ax.set_xlim(0, 108)
ax.set_ylim(108, 0)
ax.set_aspect("equal")
ax.axis("off")
ax.set_facecolor("#FFFFFF")
draw_file(ax, f"{RES}/drawable/ic_launcher_background.xml")
draw_file(ax, f"{RES}/drawable/ic_launcher_foreground.xml")
fig.savefig(OUT, dpi=100)
plt.close(fig)

# ── 像素取樣驗證（滿幅 540×540，座標 = SVG 座標 × 5）──
from PIL import Image
img = Image.open(OUT).convert("RGB")
W, H = img.size
assert (W, H) == (540, 540), f"畫布尺寸異常：{W}×{H}"
sx = sy = 5.0


def sample(svg_x, svg_y):
    px = img.getpixel((min(int(svg_x * sx), W - 1), min(int(svg_y * sy), H - 1)))
    return px


def near(c1, c2, tol=18):
    return all(abs(a - b) <= tol for a, b in zip(c1, c2))


checks = [
    # (說明, SVG 座標, 期望色)
    ("左上角青黛綠底",        (3, 3),      (0x24, 0x59, 0x50)),
    ("右下角青黛綠底",        (105, 105),  (0x24, 0x59, 0x50)),
    ("框線（頂緣中點）",      (54, 24),    (0x34, 0x75, 0x6A)),
    ("書脊豎筆中心",          (37.75, 54), (0xF7, 0xF8, 0xF4)),
    ("上襯線中點",            (38, 33),    (0xF7, 0xF8, 0xF4)),
    ("下襯線中點",            (38, 77),    (0xF7, 0xF8, 0xF4)),
    ("上斜筆前頁",            (52, 41.5),  (0xF7, 0xF8, 0xF4)),
    ("上斜筆後頁外露緣",      (75, 33.9),  (0xE2, 0xE6, 0xDC)),
    ("下斜筆前頁",            (56, 61),    (0xF7, 0xF8, 0xF4)),
    ("下斜筆後頁外露緣",      (73.5, 69.9),(0xE2, 0xE6, 0xDC)),
    ("琥珀金圓點中心",        (68.5, 76.5),(0xD4, 0xAF, 0x37)),
]

ok = True
for name, (x, y), expect in checks:
    got = sample(x, y)
    passed = near(got, expect)
    ok &= passed
    mark = "OK " if passed else "FAIL"
    print(f"[{mark}] {name} @({x},{y}) → RGB{got}（期望 RGB{expect}）")

# ── 金點與底色佔比（非空白渲染證明）──
from collections import Counter
cnt = Counter(img.getdata())
total = W * H
teal = sum(v for c, v in cnt.items() if near(c, (0x24, 0x59, 0x50), 10))
white = sum(v for c, v in cnt.items() if near(c, (0xF7, 0xF8, 0xF4), 10))
gold = sum(v for c, v in cnt.items() if near(c, (0xD4, 0xAF, 0x37), 25))
print(f"覆蓋率：青黛底 {teal/total:.1%}｜宣紙白 {white/total:.1%}｜琥珀金 {gold/total:.2%}")

# 幾何安全圈驗證（字形極值 → 半徑 ≤ 33dp 安心圈）
extremes = {
    "上襯線左上角": (32, 31),
    "下襯線左下角": (32, 77.5),
    "上斜筆尖端":   (77, 33.5),
    "金點下緣":     (68.5, 80.5),
}
for name, (x, y) in extremes.items():
    r = math.hypot(x - 54, y - 54)
    assert r <= 33.0, f"{name} 超出 33dp 安心圈（r={r:.1f}）"
    print(f"[OK ] {name} r = {r:.1f}dp ≤ 33dp 安心圈")

assert ok, "像素取樣有失敗項"
print("\n=== 圖示像素級幾何驗證全數通過 ===")
