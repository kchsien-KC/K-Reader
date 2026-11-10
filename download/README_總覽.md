# K.閱讀 v1.2 旗艦純淨版 — 完整交付包總覽

> 本包收錄「K.閱讀」（com.k.reader）十三階段開發的全部交付文件。
> 基於開源「閱讀（Legado）」架構純淨重建——GPL-3.0、零廣告、零統計 SDK。

## 包內容

```
完整交付包/
├── README_總覽.md                    ← 本文件
├── 01_階段交付包/                    ← Stage 1~13 各階段交付 zip（源碼＋資源＋驗證基準）
├── 02_完整歷史/                      ← git 完整開發歷程 bundle（20 個提交）＋自推說明
├── 03_驗證器與腳本/                  ← 十三階段驗證器＋圖示腳本＋GitHub 推送腳本
└── 04_圖示預覽/                      ← 純向量啟動圖示預覽圖
```

## 十三階段開發歷程

| 階段 | 範圍 |
| --- | --- |
| Stage 1 | 專案識別與編譯配置（隔離 applicationId／純向量圖示／繁中字串） |
| Stage 2 | 防封鎖三件套＋簡轉繁＋隨機試讀 |
| Stage 3 | 書源／訂閱源完整鏈路檢核與智慧分組 |
| Stage 4 | WebView 過盾＋剪貼簿偵測＋HTTP 聽書＋智慧換源 |
| Stage 5 | 淨化規則包＋回收站＋金句卡＋閱讀統計＋E-Ink＋WebDAV＋AI 助手 |
| Stage 6 | README＋GitHub Actions 雲端編譯＋init_k_reader.sh 套名重構 |
| Stage 7 | 隱私鎖＋台灣排版規範＋自動追更＋護眼色溫＋字詞快查 |
| Stage 8 | 書內全文搜搜尋＋劃線筆記本＋閱讀目標＋定時日夜切換＋書架整理 |
| Stage 9 | 章節書籤＋離線快取＋聽書睡眠定時＋自訂字體＋翻頁手勢 |
| Stage 10 | 本地書匯入＋備份加密＋閱讀週報／月報＋休息提醒＋書源倉庫 |
| Stage 11 | 成就徽章＋生詞本＋書籍分享卡＋閱讀時間軸＋閱讀挑戰 |
| Stage 12 | 每書閱讀方案＋聽書微調＋溫故詞頻＋待讀清單＋讀速洞察 |
| Stage 13 | 閱讀年報＋資料匯出中心＋搬家包＋書籍標籤＋資料健檢＋完整樹接縫 |

**規模**：55 個 Kotlin 模組＋15 個布局＋680 鍵自有繁中字串（合併上游後 1838 鍵）。

## 完整歷史（02_完整歷史/）

`K_Reader_git_history.bundle` 含全部 20 個提交（Initial → Stage 1 → … → Stage 13 ＋完整樹接縫）。
還原為 git 倉庫：

```bash
git clone K_Reader_git_history.bundle k-reader
cd k-reader && git log --oneline     # 檢視完整開發歷程
git push https://github.com/<你的帳號>/<repo>.git main:main   # 可直接推送
```

## 完整可編譯樹（雲端編譯）

疊加層倉庫（main 分支）＋`init_k_reader.sh` 對一份 Legado 基準樹執行套名重構，
即得完整可編譯樹（已推送到倉庫 `full-tree` 分支；打上 `v1.2` 標籤觸發
GitHub Actions 雲端編譯，自動產出 `K_Reader_v1.2.apk`）。

## 驗證器（03_驗證器與腳本/）

```bash
python3 validate_stage1.py    # 各階段獨立驗證（檔案／布局／字串／邏輯等價測試）
...
python3 validate_stage13.py
```

每個驗證器為「階段快照」——對其對應階段時點的樹驗證；以最新階段驗證器為準。

## 線上資源

- GitHub 倉庫：https://github.com/kchsien-KC/K-Reader
  - `main` 分支＝十三階段疊加層（完整開發過程）
  - `full-tree` 分支＝完整可編譯樹（雲端編譯用）
- 授權：GPL-3.0（上游「閱讀（Legado）」衍生作品）
