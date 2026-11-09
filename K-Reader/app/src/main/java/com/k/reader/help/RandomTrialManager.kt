package com.k.reader.help

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book
import com.k.reader.data.entities.BookChapter
import com.k.reader.data.entities.BookSource
import com.k.reader.help.BookHelp
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.model.webBook.BookList
import com.k.reader.model.webBook.WebBook
import com.k.reader.ui.book.read.ReadBook
import com.k.reader.ui.book.read.ReadBookActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 隨機試讀管理器（K.閱讀專屬旗艦功能）
 * ═══════════════════════════════════════════════════════════════
 *
 *  功能規格：
 *   1. 隨機抽書：隨機書源 × 隨機關鍵字 × 隨機結果，跳過
 *      BookInfoActivity 直達 ReadBookActivity 正文；
 *   2. 品質門檻：首章正文 < 200 字（去空白計數）自動換書重試，
 *      最多 3 次（規格值），杜絕「試讀抽到廣告頁」；
 *   3. 閱讀介面試讀條（view_trial_bar.xml）：
 *      「換一本試讀」＋「一鍵加入書架」；
 *   4. 離開自動清除：閱讀頁 onDestroy 時，若該書仍未加入書架
 *      （仍帶試讀標記）→ 連書籍、章節、內容快取一併刪除；
 *      進程被系統殺死的殘留標記，於 App 冷啟動時補掃清除。
 *
 *  防誤刪設計：每個 ReadBookActivity「實例」在載入書籍時記下
 *  自己的 bookUrl，onDestroy 只清理自己那本——換書／堆疊銷毀
 *  的執行順序競態不會波及新書（絕不讀取全域單例來判斷）。
 *
 *  併發紀律：所有對書源的請求都經過 SourceRateLimiter 限流，
 *  「隨機試讀」本身不成為封鎖觸發源。
 *
 *  ─── Legado 引擎接縫區 ────────────────────────────────────
 *  searchBooks / loadBookInfo / loadChapterList / loadFirstChapterText
 *  四個私有函數是本管理器與 Legado 書源引擎的唯一接觸點，分別
 *  鏡射 SearchModel / BookInfoViewModel / TocViewModel /
 *  ContentViewModel 內部的公開呼叫。若你套用的基底版本簽名略有
 *  差異，僅需對照上述四個 ViewModel 修正該區（各一行呼叫），
 *  其餘所有邏輯（重試、標記、清理、UI 綁定）完全不受影響。
 */
object RandomTrialManager {

    private const val TAG = "RandomTrialManager"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_TRIAL_SET = "trial_book_urls"

    /** 首章正文下限（規格：200 字） */
    private const val MIN_FIRST_CHAPTER_CHARS = 200

    /** 正文不足自動重試上限（規格：最多 3 次 → 初選 + 3 次換書） */
    private const val MAX_CONTENT_RETRY = 3

    /** 單輪最多嘗試的書源數（控制整體耗時上界） */
    private const val MAX_SOURCE_ATTEMPTS = 12

    private const val SEARCH_TIMEOUT_MS = 15_000L
    private const val INFO_TIMEOUT_MS = 15_000L
    private const val TOC_TIMEOUT_MS = 20_000L
    private const val CONTENT_TIMEOUT_MS = 20_000L

    /** 管理器專用作用域（存活於 Activity 之外——離場清理必須完成） */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var picking = false

    /**
     * 搜尋關鍵字池（刻意使用簡體——絕大多數書源站點為簡中站，
     * 以簡體關鍵字命中率最高；正文顯示時由簡轉繁引擎處理）。
     */
    private val SEARCH_KEYWORDS = listOf(
        "剑", "仙", "都市", "重生", "江湖", "少年", "星辰", "传说", "西游", "红楼",
        "盗墓", "灵异", "神话", "战争", "帝国", "王朝", "武侠", "修真", "法师", "骑士",
        "深海", "长夜", "人间", "烟火", "孤岛", "迷雾", "时间", "远行", "故乡", "山河"
    )

    // ═════════════════ 試讀標記（跨進程重啟仍可清理） ═════════════════

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val trialUrls: MutableSet<String> by lazy {
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also { set ->
            prefs.getStringSet(KEY_TRIAL_SET, emptySet())?.let { set.addAll(it) }
        }
    }

    fun isTrial(bookUrl: String): Boolean = trialUrls.contains(bookUrl)

    fun markTrial(bookUrl: String) {
        if (trialUrls.add(bookUrl)) persistTrialSet()
    }

    /** 解除暫存標記（＝一鍵加入書架：書籍永久保留） */
    fun unmarkTrial(bookUrl: String) {
        if (trialUrls.remove(bookUrl)) persistTrialSet()
    }

    private fun persistTrialSet() {
        prefs.edit().putStringSet(KEY_TRIAL_SET, trialUrls.toSet()).apply()
    }

    // ═════════════════ 主流程 ═════════════════

    /**
     * 隨機試讀入口（書架入口按鈕 / 試讀條「換一本」共用）。
     * 流程：彈出載入對話框 → 隨機抽書並驗證 → 直接開啟閱讀頁。
     */
    fun startRandomTrial(activity: FragmentActivity) {
        if (picking) return
        picking = true
        val dialog = createLoadingDialog(activity)
        val job = managerScope.launch(Dispatchers.Main) {
            try {
                dialog.show()
                val book = withContext(Dispatchers.IO) { pickTrialBook() }
                if (book == null) {
                    toastOnMain(activity.getString(R.string.random_trial_failed))
                } else {
                    // 換書情境：發起者若是閱讀頁，先收掉它——
                    // 其 onDestroy 會自行清理「自己的」舊試讀書，順序無競態
                    if (activity is ReadBookActivity) activity.finish()
                    activity.startActivity(
                        Intent(activity, ReadBookActivity::class.java)
                            .putExtra("bookUrl", book.bookUrl)
                    )
                }
            } finally {
                picking = false
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { job.cancel() }
    }

    /**
     * 抽書管線（僅於 IO 執行緒呼叫）：
     * 隨機書源 → 搜尋 → 隨機取書 → 詳情 → 目錄 → 首章正文驗證。
     */
    private suspend fun pickTrialBook(): Book? {
        // 1) 收集可用書源（啟用且可搜尋），洗牌增加隨機性
        val sources = runCatching {
            /* 接縫：3.0 為 allBookSourceNotLive（若版本差異請對照 BookSourceDao） */
            AppContext.db.bookSourceDao().allBookSourceNotLive
        }.getOrNull().orEmpty()
            .filter { it.enabled == true && !it.searchUrl.isNullOrBlank() }
        if (sources.isEmpty()) return null

        val random = Random(System.nanoTime())
        val shuffledSources = sources.shuffled(random)
        var keywordCursor = random.nextInt(SEARCH_KEYWORDS.size)
        var shortContentCount = 0

        for ((index, source) in shuffledSources.withIndex()) {
            if (index >= MAX_SOURCE_ATTEMPTS) break

            // 限流：尊重單源 QPS（防封鎖紀律）
            SourceRateLimiter.awaitPermit(source.bookSourceUrl)

            val keyword = SEARCH_KEYWORDS[(keywordCursor++) % SEARCH_KEYWORDS.size]
            val candidates = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                searchBooks(keyword, source)
            }.orEmpty().filter { !it.name.isNullOrBlank() }
            if (candidates.isEmpty()) continue

            // 2) 隨機取一本，走完整鏈路驗證
            for (book in candidates.shuffled(random)) {
                if (shortContentCount > MAX_CONTENT_RETRY) return null

                val infoOk = withTimeoutOrNull(INFO_TIMEOUT_MS) {
                    loadBookInfo(book, source)
                } == true
                if (!infoOk) continue

                SourceRateLimiter.awaitPermit(source.bookSourceUrl)
                val chapters = withTimeoutOrNull(TOC_TIMEOUT_MS) {
                    loadChapterList(book, source)
                }
                if (chapters.isNullOrEmpty()) continue

                SourceRateLimiter.awaitPermit(source.bookSourceUrl)
                val first = chapters.first()
                val content = withTimeoutOrNull(CONTENT_TIMEOUT_MS) {
                    loadFirstChapterText(book, first, source)
                }
                val wordCount = content?.count { !it.isWhitespace() } ?: 0
                if (wordCount >= MIN_FIRST_CHAPTER_CHARS) {
                    // 命中：寫入資料庫並標記試讀，回交主流程開啟閱讀頁
                    insertTrialBook(book, chapters)
                    return book
                }

                // 首章正文 < 200 字 → 自動換書重試（規格：最多 3 次）
                shortContentCount++
                toastOnMain(
                    AppContext.ctx.getString(R.string.random_trial_retrying, shortContentCount)
                )
            }
        }
        return null
    }

    // ═════════════════ Legado 引擎接縫區 ═════════════════

    /** 搜尋（鏡射 SearchModel 的 BookList.search 呼叫） */
    private suspend fun searchBooks(key: String, source: BookSource): List<Book> = coroutineScope {
        val out = mutableListOf<Book>()
        runCatching {
            BookList.search(AppContext.ctx, this, key, source) { searchData ->
                searchData.bookList?.let { list -> out.addAll(list) }
            }
        }.onFailure { e ->
            Log.w(TAG, "搜尋失敗：${source.bookSourceName}", e)
        }
        out
    }

    /** 書籍詳情（鏡射 BookInfoViewModel；完成後 book 即帶 tocUrl／intro 等欄位） */
    private suspend fun loadBookInfo(book: Book, source: BookSource): Boolean = coroutineScope {
        runCatching {
            WebBook.getBookInfo(this, book, source) { }
            true
        }.getOrDefault(false)
    }

    /** 目錄（鏡射 TocViewModel；章節清單由回呼交付） */
    private suspend fun loadChapterList(book: Book, source: BookSource): List<BookChapter> = coroutineScope {
        val out = mutableListOf<BookChapter>()
        runCatching {
            WebBook.getChapterList(this, book, source) { tocData ->
                tocData.chapterList?.let { list -> out.addAll(list) }
            }
        }.onFailure { e ->
            Log.w(TAG, "目錄失敗：${book.name}", e)
        }
        out
    }

    /** 首章正文（鏡射 ContentViewModel：觸發抓取寫入快取後讀回） */
    private suspend fun loadFirstChapterText(
        book: Book,
        chapter: BookChapter,
        source: BookSource
    ): String? = coroutineScope {
        runCatching {
            WebBook.getContent(this, book, source, chapter, chapter.index + 1) { }
            BookHelp.getContent(book, chapter)
        }.getOrNull()
    }

    // ═════════════════ 書籍寫入／清除 ═════════════════

    private suspend fun insertTrialBook(book: Book, chapters: List<BookChapter>) =
        withContext(Dispatchers.IO) {
            book.durChapterIndex = 0
            book.durChapterPos = 0
            book.durChapterTime = System.currentTimeMillis()
            /* 接縫：Room 寫入（3.0 BaseDao 提供泛型 insertOrUpdate） */
            AppContext.db.bookDao().insertOrUpdate(book)
            AppContext.db.bookChapterDao().insertOrUpdate(*chapters.toTypedArray())
            markTrial(book.bookUrl)
            Log.i(TAG, "試讀就緒：${book.name}（${book.originName}）")
        }

    /**
     * 清除單本試讀暫存（書籍＋章節＋內容快取）。
     * 已加入書架（標記已解除）的書自動跳過——防誤刪核心。
     */
    suspend fun deleteTrialBook(bookUrl: String) = withContext(Dispatchers.IO) {
        if (!isTrial(bookUrl)) return@withContext
        unmarkTrial(bookUrl)
        runCatching {
            val book = AppContext.db.bookDao().getBook(bookUrl) ?: return@runCatching
            /* 接縫：Room / 快取清除（對照 BookshelfModel 的刪書流程） */
            AppContext.db.bookChapterDao().deleteByBookUrl(bookUrl)
            AppContext.db.bookDao().deleteBook(book)
            BookHelp.clearCache(book)
            Log.i(TAG, "已清除試讀暫存：${book.name}")
        }.onFailure { e ->
            Log.w(TAG, "清除試讀暫存失敗：$bookUrl", e)
        }
    }

    /**
     * 閱讀頁 onDestroy 鉤子：僅清理「該實例載入的那本」。
     * 呼叫方式（見整合說明）：
     * `krTrialUrl?.let { RandomTrialManager.onTrialActivityDestroyed(it) }`
     */
    fun onTrialActivityDestroyed(bookUrl: String) {
        if (!isTrial(bookUrl)) return
        managerScope.launch { deleteTrialBook(bookUrl) }
    }

    /** 冷啟動補掃：清掉進程被殺時殘留的試讀標記（Application onCreate 呼叫） */
    fun cleanupAllTrialBooks() {
        val snapshot = trialUrls.toList()
        if (snapshot.isEmpty()) return
        Log.i(TAG, "冷啟動清除試讀暫存：${snapshot.size} 本")
        managerScope.launch {
            snapshot.forEach { deleteTrialBook(it) }
        }
    }

    // ═════════════════ 試讀條 UI 綁定 ═════════════════

    /**
     * 綁定試讀條（閱讀頁書籍載入完成處呼叫；非試讀書自動隱藏）。
     * 內部經 findViewById 取得 view_trial_bar 的三個節點。
     */
    fun bindTrialBar(activity: ReadBookActivity) {
        val bar = activity.findViewById<View>(R.id.trial_bar) ?: return
        val bookUrl = ReadBook.book?.bookUrl
        if (bookUrl == null || !isTrial(bookUrl)) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        bar.findViewById<View>(R.id.tv_change_book)?.setOnClickListener {
            startRandomTrial(activity)
        }
        bar.findViewById<View>(R.id.tv_add_shelf)?.setOnClickListener {
            ReadBook.book?.let { book ->
                unmarkTrial(book.bookUrl)   // 解除標記＝書籍永久保留於書架
                toastOnMain(activity.getString(R.string.random_trial_added))
                bar.visibility = View.GONE
            }
        }
    }

    // ═════════════════ 小工具 ═════════════════

    private fun toastOnMain(text: String) {
        mainHandler.post {
            Toast.makeText(AppContext.ctx, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 載入對話框（純程式碼構建——零額外布局檔案） */
    private fun createLoadingDialog(activity: FragmentActivity): AlertDialog {
        val pad = dp(activity, 22)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        row.addView(
            ProgressBar(activity),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val text = TextView(activity).apply {
            setText(R.string.random_trial_loading)
            textSize = 15f
            val gap = dp(activity, 16)
            setPadding(gap, 0, 0, 0)
        }
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return AlertDialog.Builder(activity)
            .setView(row)
            .setCancelable(true)
            .create()
    }
}
