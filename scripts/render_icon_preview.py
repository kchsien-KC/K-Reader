#!/usr/bin/env python3
"""K.閱讀 Stage 1 — 提案 A 啟動圖示（純向量）預覽產生器
解析 VectorDrawable pathData（M/L/H/V/Q/C/A/Z，含 W3C 圓弧端點轉圓心法），
以遮罩模擬啟動器呈現：完整畫布 / 圓形遮罩 / 圓角方遮罩 / 單色主題 / 小尺寸。
"""
import math
import re
import xml.etree.ElementTree as ET

import matplotlib
matplotlib.use("Agg")
import matplotlib.font_manager as fm
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, FancyBboxPatch, PathPatch
from matplotlib.path import Path

fm.fontManager.addfont("/usr/share/fonts/truetype/chinese/NotoSansSC-Regular.ttf")
plt.rcParams["font.sans-serif"] = ["Noto Sans SC", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

NS = "{http://schemas.android.com/apk/res/android}"
RES = "/home/z/my-project/K-Reader/app/src/main/res"
OUT = "/home/z/my-project/download/K_reader_Stage1_icon_preview.png"

TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+")


def arc_points(p0, p1, rx, ry, large, sweep, steps=28):
    """W3C 端點→圓心轉換（本專案僅使用正圓弧 rx==ry）。"""
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
    pts = []
    for k in range(steps + 1):
        th = a0 + da * k / steps
        pts.append((cx + r * math.cos(th), cy + r * math.sin(th)))
    return pts


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
            if cmd in ("M", "m"):  # M 之後隱含 L
                cmd = "L" if cmd == "M" else "l"
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = nxt(), nxt()
            if rel:
                x += cur[0]
                y += cur[1]
            cur = [x, y]
            start = list(cur)
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
            x = nxt()
            if rel:
                x += cur[0]
            cur[0] = x
            codes.append(Path.LINETO)
            verts.append((cur[0], cur[1]))
        elif c == "V":
            y = nxt()
            if rel:
                y += cur[1]
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
            codes += [Path.CURVE4, Path.CURVE4, Path.CURVE4]
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
        else:
            raise ValueError(f"未支援的指令: {cmd}")
    return verts, codes


def draw_file(ax, xml_path, clip=None):
    for p in ET.parse(xml_path).getroot().findall("path"):
        d = p.get(NS + "pathData")
        fill = p.get(NS + "fillColor")
        stroke = p.get(NS + "strokeColor")
        sw = p.get(NS + "strokeWidth")
        verts, codes = path_data_to_path(d)
        patch = PathPatch(
            Path(verts, codes),
            facecolor=fill if fill else "none",
            edgecolor=stroke if stroke else "none",
            linewidth=(float(sw) * 1.5) if (stroke and sw) else 0.0,
        )
        if clip is not None:
            patch.set_clip_path(clip)
        ax.add_patch(patch)


def style_ax(ax, title):
    ax.set_xlim(0, 108)
    ax.set_ylim(108, 0)  # SVG y 軸向下
    ax.set_aspect("equal")
    ax.axis("off")
    ax.set_title(title, fontsize=13, pad=10)


BG = f"{RES}/drawable/ic_launcher_background.xml"
FG = f"{RES}/drawable/ic_launcher_foreground.xml"
MONO = f"{RES}/drawable/ic_launcher_monochrome.xml"

fig, axes = plt.subplots(
    1, 5, figsize=(22, 4.8), constrained_layout=True,
    gridspec_kw={"width_ratios": [1, 1, 1, 1, 0.62]},
)

# 1. 完整畫布
style_ax(axes[0], "完整畫布 108dp（方形）")
draw_file(axes[0], BG)
draw_file(axes[0], FG)

# 2. 圓形遮罩（最嚴格）
style_ax(axes[1], "圓形遮罩（最嚴格 72dp）")
c2 = Circle((54, 54), 36, transform=axes[1].transData, facecolor="none", edgecolor="none")
axes[1].add_patch(c2)
draw_file(axes[1], BG, clip=c2)
draw_file(axes[1], FG, clip=c2)

# 3. 圓角方遮罩（主流桌面）
style_ax(axes[2], "圓角方遮罩（主流桌面）")
c3 = FancyBboxPatch((16, 16), 76, 76, boxstyle="round,pad=0,rounding_size=22",
                    transform=axes[2].transData, facecolor="none", edgecolor="none")
axes[2].add_patch(c3)
draw_file(axes[2], BG, clip=c3)
draw_file(axes[2], FG, clip=c3)

# 4. 單色主題圖示
style_ax(axes[3], "單色層（Android 13+ 主題）")
axes[3].add_patch(Circle((54, 54), 36, facecolor="#245950"))
c4 = Circle((54, 54), 36, transform=axes[3].transData, facecolor="none", edgecolor="none")
axes[3].add_patch(c4)
draw_file(axes[3], MONO, clip=c4)

# 5. 48dp 小尺寸可讀性
style_ax(axes[4], "48dp 小尺寸")
c5 = Circle((54, 54), 36, transform=axes[4].transData, facecolor="none", edgecolor="none")
axes[4].add_patch(c5)
draw_file(axes[4], BG, clip=c5)
draw_file(axes[4], FG, clip=c5)

fig.suptitle("K.閱讀 v1.2 — 提案 A「青黛宣紙」羅馬大 K 啟動圖示（純向量、零點陣圖）", fontsize=16)
fig.savefig(OUT, dpi=110)
print("saved:", OUT)
