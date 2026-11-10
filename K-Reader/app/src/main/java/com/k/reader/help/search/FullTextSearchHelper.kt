package com.k.reader.help.search

import android.util.Log
import com.k.reader.help.TraditionalChineseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 書內全文搜尋器（Stage 8 旗艦增益：全文搜尋）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（在全書裡，替你找回那句話）：
 *   讀到第兩百章，忽然想起第三章那句伏筆——翻頁是找不到的。
 *   本搜尋器把「整本書」當成一個可檢索的文本庫：
 *
 *    1. 章節注入：搜尋不綁資料庫——ContentLoader 函式介面由
 *       呼叫端注入（閱讀頁的內容快取／ContentProcessor），
 *       沒快取的章節回 null 自動跳過，不炸不卡；
 *    2. 簡繁並搜：繁體關鍵字在簡體書源原文上命中難——雙針
 *       策略：原關鍵字＋繁化關鍵字（TraditionalChineseHelper
 *       s2t）兩支針同時在「原文」上定位，位置零漂移；
 *    3. 雙模式：純文字（indexOf 走查）／正則（Matcher 走查
 *       ，PatternSyntaxException 即時回報不崩潰）；
 *    4. 前後文摘錄：命中位置前後 CONTEXT_CHARS 字——結果列
 *       不只報章節，還報「那句話長什麼樣」；
 *    5. 進度回報：onProgress(processed, total) 每章一次，
 *       fulltext_searching 狀態列即時餵百分比；
 *    6. 上限護欄：MAX_HITS 筆封頂（規格 300）——「的」字全書
 *       三萬筆的災難不會發生，滿額即停。
 *
 *  整合點：
 *   · 閱讀頁選單 fulltext_search_title → searchInBook()
 *   · dialog_fulltext_search 進度列 ← onProgress
 *   · 結果點選 → chapterIndex＋position 餵閱讀頁跳章定位
 */
object FullTextSearchHelper {

    private const val TAG = "FullTextSearch"

    /** 單次搜尋結果上限（規格：300 筆——滿額即停不掃完） */
    const val MAX_HITS = 300

    /** 摘錄前後文字數（規格：前後各 20 字） */
    const val CONTEXT_CHARS = 20

    /** 關鍵字長度上限（防誤觸全空白／超長貼上） */
    const val MAX_KEYWORD_CHARS = 64

    /** 單章內容長度護欄（超過即截斷搜尋——異常源防呆） */
    const val MAX_CHAPTER_CHARS = 200_000

    /** 搜尋模式：純文字 */
    const val MODE_PLAIN = "PLAIN"

    /** 搜尋模式：正則表達式 */
    const val MODE_REGEX = "REGEX"

    /** 單筆命中：章 index＋章名＋字元位置＋前後文摘錄（UI 直接渲染） */
    data class SearchHit(
        val chapterIndex: Int,
        val chapterTitle: String?,
        val position: Int,
        val excerpt: String
    )

    /** 搜尋結果：命中清單＋是否滿額＋掃過章數 */
    data class SearchResult(
        val hits: List<SearchHit>,
        val truncated: Boolean,
        val scannedChapters: Int,
        val totalChapters: Int
    )

    /** 章節內容注入介面：呼叫端提供（index → 章內容；null = 跳過） */
    fun interface ContentLoader {
        fun load(chapterIndex: Int): String?
    }

    /** 進度回報：每掃完一章一次（IO 執行緒——UI 端自行 post） */
    fun interface ProgressReporter {
        fun onProgress(processed: Int, total: Int)
    }

    /** 正則快取（同 needle 重搜零編譯——ConcurrentHashMap 命中） */
    private val patternCache = ConcurrentHashMap<String, Pattern>()

    // ═════════════════ 對外主入口 ═════════════════

    /**
     * 全書搜尋（IO 執行緒執行）。
     *
     * @param totalChapters   章節總數（目錄長度）
     * @param chapterTitles   章名映射（index → title，可 null）
     * @param loader          章內容注入（null = 無快取跳過）
     * @param keyword         關鍵字（空白回空結果）
     * @param mode            MODE_PLAIN／MODE_REGEX
     * @param caseInsensitive 忽略大小大小寫（預設 true——英文書名人名友善）
     * @param traditionalAlso 簡繁並搜（預設 true——雙針策略）
     * @param onProgress      進度回報（null = 不回報）
     * @return SearchResult（正則格式錯誤回 null，呼叫端報 fulltext_regex_invalid）
     */
    suspend fun searchInBook(
        totalChapters: Int,
        chapterTitles: Map<Int, String>?,
        loader: ContentLoader,
        keyword: String,
        mode: String = MODE_PLAIN,
        caseInsensitive: Boolean = true,
        traditionalAlso: Boolean = true,
        onProgress: ProgressReporter? = null
    ): SearchResult? = withContext(Dispatchers.IO) {
        val needle = keyword.trim().take(MAX_KEYWORD_CHARS)
        if (needle.isEmpty() || totalChapters <= 0) {
            return@withContext SearchResult(emptyList(), false, 0, totalChapters.coerceAtLeast(0))
        }

        // ── 正則模式：預編譯（失敗即退——格式錯誤） ──
        val pattern: Pattern? = if (mode == MODE_REGEX) {
            tryCompile(needle, caseInsensitive) ?: return@withContext null
        } else null

        // ── 雙針：原關鍵字＋繁化關鍵字（簡體針命中繁體文本） ──
        val plainNeedles: List<String> = if (mode == MODE_PLAIN) {
            buildPlainNeedles(needle, traditionalAlso)
        } else emptyList()

        val hits = mutableListOf<SearchHit>()
        var truncated = false
        var scanned = 0

        for (index in 0 until totalChapters) {
            val content = runCatching { loader.load(index) }.getOrNull()
            if (!content.isNullOrEmpty()) {
                scanned++
                val text = content.take(MAX_CHAPTER_CHARS)
                val title = chapterTitles?.get(index)
                if (pattern != null) {
                    collectRegexHits(pattern, text, index, title, hits)
                } else {
                    collectPlainHits(plainNeedles, text, index, title, hits)
                }
                if (hits.size >= MAX_HITS) {
                    truncated = true
                    if (hits.size > MAX_HITS) hits.subList(MAX_HITS, hits.size).clear()
                    onProgress?.onProgress(index + 1, totalChapters)
                    break
                }
            }
            onProgress?.onProgress(index + 1, totalChapters)
        }
        SearchResult(hits, truncated, scanned, totalChapters)
    }

    // ═════════════════ 搜尋內部 ═════════════════

    /** 雙針清單：原樣＋繁化（繁化結果與原樣相同時去重） */
    private fun buildPlainNeedles(needle: String, traditionalAlso: Boolean): List<String> {
        val needles = mutableListOf(needle)
        if (traditionalAlso) {
            runCatching { TraditionalChineseHelper.toTraditional(needle) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it != needle }
                ?.let { needles.add(it) }
        }
        return needles
    }

    /**
     * 純文字走查：雙針逐一 indexOf，取最小位置命中（忽略大小寫
     * 時雙方都轉 lowercase——CJK 無大小寫不受影響）。
     */
    private fun collectPlainHits(
        needles: List<String>,
        text: String,
        chapterIndex: Int,
        chapterTitle: String?,
        out: MutableList<SearchHit>
    ) {
        if (needles.isEmpty()) return
        val loweredHay = text.lowercase()
        val probes = needles.map { it.lowercase() }
        var from = 0
        while (out.size < MAX_HITS) {
            var bestPos = -1
            var bestLen = 0
            for (probe in probes) {
                val pos = loweredHay.indexOf(probe, from)
                if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                    bestPos = pos
                    bestLen = probe.length
                }
            }
            if (bestPos < 0) break
            out.add(buildHit(chapterIndex, chapterTitle, bestPos, text, bestLen))
            from = bestPos + bestLen.coerceAtLeast(1)
        }
    }

    /** 正則走查：Matcher.find 迴圈（每命中一筆摘錄） */
    private fun collectRegexHits(
        pattern: Pattern,
        text: String,
        chapterIndex: Int,
        chapterTitle: String?,
        out: MutableList<SearchHit>
    ) {
        val matcher: Matcher = pattern.matcher(text)
        while (matcher.find() && out.size < MAX_HITS) {
            val len = (matcher.end() - matcher.start()).coerceAtLeast(1)
            out.add(buildHit(chapterIndex, chapterTitle, matcher.start(), text, len))
        }
    }

    /** 命中條目組裝：前後文摘錄（CONTEXT_CHARS 規格值） */
    private fun buildHit(
        chapterIndex: Int,
        chapterTitle: String?,
        position: Int,
        text: String,
        hitLen: Int
    ): SearchHit {
        val start = (position - CONTEXT_CHARS).coerceAtLeast(0)
        val end = (position + hitLen + CONTEXT_CHARS).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val excerpt = "$prefix${text.substring(start, end).replace('\n', ' ')}$suffix"
        return SearchHit(chapterIndex, chapterTitle, position, excerpt.trim())
    }

    /** 正則預編譯＋快取（ConcurrentHashMap 命中零編譯） */
    private fun tryCompile(regex: String, caseInsensitive: Boolean): Pattern? {
        val cacheKey = "$caseInsensitive|$regex"
        patternCache[cacheKey]?.let { return it }
        return try {
            val flags = if (caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            val pattern = Pattern.compile(regex, flags)
            patternCache[cacheKey] = pattern
            pattern
        } catch (e: PatternSyntaxException) {
            Log.w(TAG, "正則關鍵字編譯失敗：$regex", e)
            null
        }
    }

    // ═════════════════ UI 文案 ═════════════════

    /** 章節標籤：fulltext_chapter_fmt（第 %1$d 章）＋章名（有則併） */
    fun chapterLabel(chapterIndex: Int, chapterTitle: String?): String {
        val base = com.k.reader.app.AppContext.ctx.getString(
            com.k.reader.R.string.fulltext_chapter_fmt, chapterIndex + 1
        )
        return if (chapterTitle.isNullOrBlank()) base else "$base · $chapterTitle"
    }
}
