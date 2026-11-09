package com.k.reader.help.source

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.BookSource
import com.k.reader.data.entities.RssSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 來源智慧分組管理器（十大旗艦功能：智慧分組）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（三層分組策略，標籤零硬編碼）：
 *
 *   1. 智慧自動分組（group_auto）：以 BookSource.bookSourceType
 *      （Legado 慣例：0 文本／1 有聲／2 圖片／3 文件／4 網頁）
 *      為主軸，再以來源名稱關鍵字細分——
 *        文本 → 小說（書源名帶「影視／新聞／有聲」則歸影音／資訊／有聲）
 *        圖片 → 漫畫／圖庫／影音（依名稱關鍵字）
 *        網頁 → 資訊；文件 → 其他
 *      訂閱源（無型態欄位）純以名稱關鍵字分：
 *        影音／圖庫／有聲／資訊／其他
 *
 *   2. 依網域分組（group_by_domain）：來源網址的 host 去除
 *      www.／m. 前綴作為分組標籤——同一站點的多個鏡像源一鍵聚攏。
 *
 *   3. 失效分組（group_failed）：由 BookSourceHealthManager／
 *      RssSourceVerifyManager 檢核後自動標記；「一鍵清空失效
 *      分組」（group_clear_failed）將該分組內所有來源連根刪除。
 *
 *  置換語意（可預期、不膨脹）：
 *   「智慧自動分組」與「依網域分組」同屬自動標籤、互為置換——
 *   重複執行任一策略結果恆等（冪等）；使用者手動自訂的分組與
 *   「失效」標記則恆保留、永不誤刪。
 *
 *  分組欄位慣例：與 Legado 一致——bookSourceGroup／sourceGroup
 *  為逗號分隔的多重分組字串（讀入時兼容全形逗號／分號）。
 *
 *  標籤供給：全部經 getString(R.string.group_*) 取自 strings.xml
 *  前瞻鍵（小說／有聲／漫畫／資訊／圖庫／影音／其他／失效），
 *  程式碼內零硬編碼中文標籤。
 *
 *  整合點：
 *   · 書源管理頁選單「智慧自動分組／依網域分組」→ autoGroupBookSources()／
 *     groupBookSourcesByDomain()
 *   · 訂閱源管理頁同款選單 → autoGroupRssSources()／groupRssSourcesByDomain()
 *   · 檢核流程自動標記 → syncBookSourceFailureGroup()／syncRssSourceFailureGroup()
 *   · 「一鍵清空失效分組」確認框後 → clearFailedSources()
 */
object SourceGroupManager {

    private const val TAG = "SourceGroupManager"

    /** 分組欄位分隔符（與 Legado bookSourceGroup 慣例一致） */
    private const val GROUP_SEPARATOR = ","

    // ── BookSource.bookSourceType 對照（Legado 3.0 慣例） ──
    //  0 文本：與 null／未知一併落入 classifyBookSource 的 else 細分
    private const val SOURCE_TYPE_AUDIO = 1   // 有聲
    private const val SOURCE_TYPE_IMAGE = 2   // 圖片
    private const val SOURCE_TYPE_FILE = 3    // 文件
    private const val SOURCE_TYPE_WEB = 4     // 網頁

    /** 分組結果（UI 得以即時顯示「來源數量：N」與各組計數） */
    data class GroupResult(
        val total: Int,
        val changed: Int,
        val groupCounts: Map<String, Int>
    )

    // ═════════════════ 標籤供給（零硬編碼） ═════════════════

    private fun novelLabel() = AppContext.ctx.getString(R.string.group_novel)
    private fun audioLabel() = AppContext.ctx.getString(R.string.group_audio)
    private fun comicLabel() = AppContext.ctx.getString(R.string.group_comic)
    private fun galleryLabel() = AppContext.ctx.getString(R.string.group_gallery)
    private fun videoLabel() = AppContext.ctx.getString(R.string.group_video)
    private fun infoLabel() = AppContext.ctx.getString(R.string.group_info)
    private fun otherLabel() = AppContext.ctx.getString(R.string.group_other)
    private fun failedLabel() = AppContext.ctx.getString(R.string.group_failed)

    /** 書源側管轄的類型標籤全集（置換式分組的辨識基準） */
    private fun bookManagedLabels(): Set<String> = setOf(
        novelLabel(), audioLabel(), comicLabel(), galleryLabel(),
        videoLabel(), infoLabel(), otherLabel()
    )

    /** 訂閱源側管轄的類型標籤（訂閱源不出現小說／漫畫類） */
    private fun rssManagedLabels(): Set<String> = setOf(
        audioLabel(), galleryLabel(), videoLabel(), infoLabel(), otherLabel()
    )

    /** UI 群組頁籤建議清單：七類型標籤＋失效（依序） */
    fun managedGroupLabels(): List<String> =
        (bookManagedLabels() + failedLabel()).toList()

    // ═════════════════ 類型推斷 ═════════════════

    /** 名稱關鍵字池（簡繁並列——書源名兩種語系皆有） */
    private val KEYWORDS_COMIC = listOf("漫画", "漫畫", "comic", "本子", "同人")
    private val KEYWORDS_GALLERY = listOf(
        "图库", "圖庫", "壁纸", "壁紙", "gallery", "photo",
        "插画", "插畫", "cosplay", "表情"
    )
    private val KEYWORDS_VIDEO = listOf(
        "视频", "視頻", "影音", "影视", "影視", "直播",
        "video", "bilibili", "哔哩", "b站"
    )
    private val KEYWORDS_INFO = listOf(
        "新闻", "新聞", "资讯", "資訊", "news", "头条",
        "頭條", "日报", "日報", "周刊", "週刊", "时报", "時報"
    )
    private val KEYWORDS_AUDIO = listOf(
        "有声", "有聲", "听书", "聽書", "播客", "podcast",
        "电台", "電台", "评书", "評書", "相声", "相聲"
    )

    private fun String.containsAny(words: List<String>): Boolean =
        words.any { contains(it, ignoreCase = true) }

    /**
     * 單一書源的分類標籤（智慧自動分組核心）：
     * bookSourceType 定大類，來源名稱關鍵字做細分與導正。
     */
    fun classifyBookSource(source: BookSource): String {
        val name = source.bookSourceName
        return when (source.bookSourceType) {
            SOURCE_TYPE_AUDIO -> audioLabel()
            SOURCE_TYPE_IMAGE -> when {
                name.containsAny(KEYWORDS_COMIC) -> comicLabel()
                name.containsAny(KEYWORDS_GALLERY) -> galleryLabel()
                name.containsAny(KEYWORDS_VIDEO) -> videoLabel()
                else -> comicLabel()   // 圖片型無關鍵字者預設為漫畫
            }
            SOURCE_TYPE_WEB -> infoLabel()
            SOURCE_TYPE_FILE -> otherLabel()
            else -> when {             // 文本型（含 null）再細分
                name.containsAny(KEYWORDS_VIDEO) -> videoLabel()
                name.containsAny(KEYWORDS_INFO) -> infoLabel()
                name.containsAny(KEYWORDS_AUDIO) -> audioLabel()
                else -> novelLabel()
            }
        }
    }

    /** 單一訂閱源的分類標籤（無型態欄位，純名稱關鍵字） */
    fun classifyRssSource(source: RssSource): String {
        val name = source.sourceName
        return when {
            name.containsAny(KEYWORDS_VIDEO) -> videoLabel()
            name.containsAny(KEYWORDS_GALLERY) -> galleryLabel()
            name.containsAny(KEYWORDS_AUDIO) -> audioLabel()
            name.containsAny(KEYWORDS_INFO) -> infoLabel()
            else -> otherLabel()
        }
    }

    // ═════════════════ 書源分組 API ═════════════════

    /** 智慧自動分組（書源）：型態＋名稱關鍵字分類，冪等置換 */
    suspend fun autoGroupBookSources(): GroupResult = withContext(Dispatchers.IO) {
        regroupBookSources { classifyBookSource(it) }.also {
            Log.i(TAG, "智慧自動分組（書源）：${it.total} 個來源，異動 ${it.changed}")
        }
    }

    /** 依網域分組（書源）：同站鏡像源聚攏，冪等置換 */
    suspend fun groupBookSourcesByDomain(): GroupResult = withContext(Dispatchers.IO) {
        regroupBookSources { domainLabelOf(it.bookSourceUrl) }.also {
            Log.i(TAG, "依網域分組（書源）：${it.total} 個來源，異動 ${it.changed}")
        }
    }

    /** 書源重分組共用管線：置換自動標籤、保留自訂群組與失效標記 */
    private suspend fun regroupBookSources(labelOf: (BookSource) -> String): GroupResult {
        val sources = collectBookSources()
        if (sources.isEmpty()) return GroupResult(0, 0, emptyMap())
        val changedSources = mutableListOf<BookSource>()
        val counts = LinkedHashMap<String, Int>()

        for (source in sources) {
            val current = splitGroups(source.bookSourceGroup)
            val custom = current.filter { !isManagedBookLabel(it) }
            val newGroups = (custom + labelOf(source)).distinct()
            countGroups(newGroups, counts)
            if (newGroups != current) {
                source.bookSourceGroup = newGroups.joinToString(GROUP_SEPARATOR)
                changedSources.add(source)
            }
        }
        if (changedSources.isNotEmpty()) persistBookSources(changedSources)
        return GroupResult(sources.size, changedSources.size, counts)
    }

    // ═════════════════ 訂閱源分組 API ═════════════════

    /** 智慧自動分組（訂閱源）：名稱關鍵字分類，冪等置換 */
    suspend fun autoGroupRssSources(): GroupResult = withContext(Dispatchers.IO) {
        regroupRssSources { classifyRssSource(it) }.also {
            Log.i(TAG, "智慧自動分組（訂閱源）：${it.total} 個來源，異動 ${it.changed}")
        }
    }

    /** 依網域分組（訂閱源） */
    suspend fun groupRssSourcesByDomain(): GroupResult = withContext(Dispatchers.IO) {
        regroupRssSources { domainLabelOf(it.sourceUrl) }.also {
            Log.i(TAG, "依網域分組（訂閱源）：${it.total} 個來源，異動 ${it.changed}")
        }
    }

    /** 訂閱源重分組共用管線（與書源側同型） */
    private suspend fun regroupRssSources(labelOf: (RssSource) -> String): GroupResult {
        val sources = collectRssSources()
        if (sources.isEmpty()) return GroupResult(0, 0, emptyMap())
        val changedSources = mutableListOf<RssSource>()
        val counts = LinkedHashMap<String, Int>()

        for (source in sources) {
            val current = splitGroups(source.sourceGroup)
            val custom = current.filter { !isManagedRssLabel(it) }
            val newGroups = (custom + labelOf(source)).distinct()
            countGroups(newGroups, counts)
            if (newGroups != current) {
                source.sourceGroup = newGroups.joinToString(GROUP_SEPARATOR)
                changedSources.add(source)
            }
        }
        if (changedSources.isNotEmpty()) persistRssSources(changedSources)
        return GroupResult(sources.size, changedSources.size, counts)
    }

    // ═════════════════ 失效分組（檢核協作） ═════════════════

    /**
     * 同步書源「失效」分組：清單內補上失效標記、清單外（康復）
     * 移除失效標記。回傳異動數。
     * （BookSourceHealthManager.applyFailureGroups 檢核後自動呼叫）
     */
    suspend fun syncBookSourceFailureGroup(failedUrls: Collection<String>): Int =
        withContext(Dispatchers.IO) {
            val failedSet = failedUrls.toSet()
            val sources = collectBookSources()
            val changed = mutableListOf<BookSource>()
            for (source in sources) {
                val current = splitGroups(source.bookSourceGroup)
                val updated = when {
                    source.bookSourceUrl in failedSet && failedLabel() !in current ->
                        current + failedLabel()
                    source.bookSourceUrl !in failedSet && failedLabel() in current ->
                        current - failedLabel()
                    else -> null
                }
                if (updated != null) {
                    source.bookSourceGroup = updated.joinToString(GROUP_SEPARATOR)
                    changed.add(source)
                }
            }
            if (changed.isNotEmpty()) persistBookSources(changed)
            Log.i(TAG, "失效分組同步（書源）：標記 ${failedSet.size}、異動 ${changed.size}")
            changed.size
        }

    /**
     * 同步訂閱源「失效」分組（與書源側對稱）。
     * （RssSourceVerifyManager.applyFailureGroups 檢核後自動呼叫）
     */
    suspend fun syncRssSourceFailureGroup(failedUrls: Collection<String>): Int =
        withContext(Dispatchers.IO) {
            val failedSet = failedUrls.toSet()
            val sources = collectRssSources()
            val changed = mutableListOf<RssSource>()
            for (source in sources) {
                val current = splitGroups(source.sourceGroup)
                val updated = when {
                    source.sourceUrl in failedSet && failedLabel() !in current ->
                        current + failedLabel()
                    source.sourceUrl !in failedSet && failedLabel() in current ->
                        current - failedLabel()
                    else -> null
                }
                if (updated != null) {
                    source.sourceGroup = updated.joinToString(GROUP_SEPARATOR)
                    changed.add(source)
                }
            }
            if (changed.isNotEmpty()) persistRssSources(changed)
            Log.i(TAG, "失效分組同步（訂閱源）：標記 ${failedSet.size}、異動 ${changed.size}")
            changed.size
        }

    /**
     * 一鍵清空「失效」分組：刪除該分組內所有書源與訂閱源
     * （回傳刪除總數）。呼叫端應先以 group_clear_failed_confirm
     * 彈出確認框。
     */
    suspend fun clearFailedSources(): Int = withContext(Dispatchers.IO) {
        val label = failedLabel()
        val failedBooks = collectBookSources()
            .filter { label in splitGroups(it.bookSourceGroup) }
        val failedRss = collectRssSources()
            .filter { label in splitGroups(it.sourceGroup) }

        failedBooks.forEach { source ->
            runCatching {
                /* 接縫：BaseDao 泛型 delete */
                AppContext.db.bookSourceDao().delete(source)
            }.onFailure { e ->
                Log.w(TAG, "刪除失效書源失敗：${source.bookSourceName}", e)
            }
        }
        failedRss.forEach { source ->
            runCatching {
                AppContext.db.rssSourceDao().delete(source)
            }.onFailure { e ->
                Log.w(TAG, "刪除失效訂閱源失敗：${source.sourceName}", e)
            }
        }
        Log.i(TAG, "清空失效分組：書源 ${failedBooks.size}、訂閱源 ${failedRss.size}")
        failedBooks.size + failedRss.size
    }

    // ═════════════════ 共用工具 ═════════════════

    /** 剖析分組欄位（兼容全形逗號／分號；一律 trim、去空） */
    internal fun splitGroups(raw: String?): List<String> =
        raw?.split(",", "，", ";", "；")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 書源側自動標籤判定（類型標籤或網域型標籤） */
    private fun isManagedBookLabel(label: String): Boolean =
        label in bookManagedLabels() || looksLikeDomainLabel(label)

    /** 訂閱源側自動標籤判定 */
    private fun isManagedRssLabel(label: String): Boolean =
        label in rssManagedLabels() || looksLikeDomainLabel(label)

    /** 網域型標籤特徵：含點、無空白、無中文字（www.example.com 型） */
    private fun looksLikeDomainLabel(label: String): Boolean =
        label.contains(".") && !label.contains(" ") && !CJK_REGEX.containsMatchIn(label)

    private val CJK_REGEX = Regex("[\\u4e00-\\u9fff]")

    /** 網域標籤：host 去協定、去埠、去 www.／m. 前綴；失敗回「其他」 */
    internal fun domainLabelOf(rawUrl: String): String {
        val url = rawUrl.trim()
        val host = runCatching { URI(url).host }.getOrNull()
            ?: Regex("//([^/?#]+)").find(url)?.groupValues?.get(1)
            ?: ""
        val cleaned = host.lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
            .substringBefore(":")
        return if (cleaned.isBlank()) otherLabel() else cleaned
    }

    private fun countGroups(groups: List<String>, counts: LinkedHashMap<String, Int>) {
        for (group in groups) {
            counts[group] = (counts[group] ?: 0) + 1
        }
    }

    // ═════════════════ 資料存取 ═════════════════

    private suspend fun collectBookSources(): List<BookSource> = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：3.0 為 allBookSourceNotLive（若版本差異請對照 BookSourceDao） */
            AppContext.db.bookSourceDao().allBookSourceNotLive
        }.getOrNull().orEmpty()
    }

    private suspend fun collectRssSources(): List<RssSource> = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：3.0 為 allRssSourceNotLive（若版本差異請對照 RssSourceDao） */
            AppContext.db.rssSourceDao().allRssSourceNotLive
        }.getOrNull().orEmpty()
    }

    private suspend fun persistBookSources(sources: List<BookSource>) {
        if (sources.isEmpty()) return
        runCatching {
            /* 接縫：BaseDao 泛型 insertOrUpdate（存在即更新分組欄位） */
            AppContext.db.bookSourceDao().insertOrUpdate(*sources.toTypedArray())
        }.onFailure { e ->
            Log.w(TAG, "書源分組寫回失敗", e)
        }
    }

    private suspend fun persistRssSources(sources: List<RssSource>) {
        if (sources.isEmpty()) return
        runCatching {
            AppContext.db.rssSourceDao().insertOrUpdate(*sources.toTypedArray())
        }.onFailure { e ->
            Log.w(TAG, "訂閱源分組寫回失敗", e)
        }
    }
}
