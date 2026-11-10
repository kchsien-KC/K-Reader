package com.k.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.session.MediaSessionCompat
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book
import com.k.reader.help.book.BookHelp
import com.k.reader.help.tts.HttpTtsEngine
import com.k.reader.model.webBook.WebBook
import com.k.reader.model.ReadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — HTTP 線上聽書前台服務（十大旗艦功能：HTTP 聽書）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（小說是拿來「聽」的）：
 *   以 HttpTtsEngine（Stage 4 引擎層）為語音來源，本服務負責
 *   「後台不中斷」的播放調度：
 *
 *    · 播放迴圈：當前章節 → splitSegments 分段 → 逐段合成
 *      （快取命中零流量）→ MediaPlayer 播放（語速／音調經
 *      PlaybackParams 即時套用）→ 段落推進；
 *    · 朗讀完自動下一章（tts_auto_next，預設開啟）：章節播畢
 *      自動載入下一章正文，閱讀頁進度同步跟隨；
 *    · 前台服務＋MediaSession：通知列暫停／續播／停止、耳機
 *      藍牙媒體鍵、AudioFocus 焦點禮讓（來電／其他播放器自動
 *      暫停，焦點回復自動續播）；
 *    · 容錯：單段合成失敗跳段續播，連續 3 段失敗（逾時／斷網）
 *      停止並回報網路異常。
 *
 *  生命週期：start() → onStartCommand(ACTION_START) →
 *  startForeground(通知) → 播放迴圈（IO）→ 播完／stopAll() →
 *  stopForeground + stopSelf。
 *
 *  ─── Legado 引擎接縫區 ────────────────────────────────────
 *  loadChapterText / advanceReaderProgress 兩個私有函數是本服務與
 *  Legado 閱讀核心的唯一接觸點（對照 ContentViewModel 與
 *  ReadBook.moveToNextChapter）。若套用版本簽名略有差異，僅需
 *  修正該區，播放／通知／焦點邏輯完全不受影響。
 *
 *  整合點：
 *   · 閱讀頁選單「朗讀」（read_aloud）→ HttpReadAloudService.start(activity)
 *   · 通知／媒體鍵：ACTION_PAUSE／ACTION_RESUME／ACTION_STOP
 *   · AndroidManifest（Stage 6 init 腳本統一處理）：
 *     FOREGROUND_SERVICE ＋ FOREGROUND_SERVICE_MEDIA_PLAYBACK 權限、
 *     <service android:name=".service.HttpReadAloudService"
 *         android:foregroundServiceType="mediaPlayback"/>
 */
class HttpReadAloudService : Service() {

    companion object {

        private const val TAG = "HttpReadAloudService"
        private const val CHANNEL_ID = "k_reader_read_aloud"
        private const val SESSION_TAG = "KReaderReadAloud"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.k.reader.service.readaloud.START"
        const val ACTION_PAUSE = "com.k.reader.service.readaloud.PAUSE"
        const val ACTION_RESUME = "com.k.reader.service.readaloud.RESUME"
        const val ACTION_STOP = "com.k.reader.service.readaloud.STOP"

        /** 連續合成失敗上限（逾此停止並回報網路異常） */
        const val MAX_SYNTH_FAILURES = 3

        @Volatile
        private var serviceRef: HttpReadAloudService? = null

        /** 閱讀頁「朗讀」選單入口 */
        fun start(context: Context) {
            val intent = Intent(context, HttpReadAloudService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause() {
            serviceRef?.pauseInternal()
        }

        fun resume() {
            serviceRef?.resumeInternal()
        }

        fun stopAll() {
            serviceRef?.stopInternal()
        }

        fun isRunning(): Boolean = serviceRef != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var mediaSession: MediaSessionCompat? = null
    private var playJob: Job? = null

    /** 暫停閘門（true 時播放迴圈停在段與段之間） */
    private val paused = MutableStateFlow(false)

    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** 通知列當前章節標題（播放迴圈於 IO 更新） */
    @Volatile
    private var chapterTitle: String? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS ->
                stopInternal()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                pauseInternal()
            AudioManager.AUDIOFOCUS_GAIN ->
                resumeInternal()
        }
    }

    // ═════════════════ 生命週期 ═════════════════

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseInternal()
            ACTION_RESUME -> resumeInternal()
            ACTION_STOP -> stopInternal()
            else -> startPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ═════════════════ 啟動／停止 ═════════════════

    private fun startPlayback() {
        if (started.get()) return
        started.set(true)
        stopped.set(false)
        serviceRef = this
        val book = ReadBook.book
        if (book == null) {
            stopInternal()
            return
        }
        createChannel()
        createMediaSession()
        requestAudioFocus()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        updateMediaSessionState(false)
        playbackLoop(book)
    }

    private fun stopInternal() {
        if (stopped.getAndSet(true)) return
        paused.value = false
        playJob?.cancel()
        mainHandler.post {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { audioManager?.abandonAudioFocusRequest(audioFocusListener) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        serviceRef = null
        started.set(false)
    }

    private fun pauseInternal() {
        paused.value = true
        pausePlayer()
        updateMediaSessionState(true)
        updateNotification(true)
    }

    private fun resumeInternal() {
        if (stopped.get()) return
        paused.value = false
        resumePlayer()
        updateMediaSessionState(false)
        updateNotification(false)
    }

    // ═════════════════ 播放迴圈 ═════════════════

    private fun playbackLoop(book: Book) {
        playJob = serviceScope.launch(Dispatchers.IO) {
            val engine = resolveEngine()
            if (engine == null) {
                stopInternal()
                return@launch
            }
            var chapterIndex = book.durChapterIndex
            var consecutiveFailures = 0
            try {
                while (isActive && !stopped.get()) {
                    val text = loadChapterText(book, chapterIndex)
                    if (text.isNullOrBlank()) break
                    val segments = HttpTtsEngine.splitSegments(text)
                    for (segment in segments) {
                        if (stopped.get()) return@launch
                        paused.first { !it }          // 暫停閘門
                        val audio = HttpTtsEngine.synthesize(engine, segment)
                        if (audio == null) {
                            consecutiveFailures++
                            if (consecutiveFailures >= MAX_SYNTH_FAILURES) {
                                toastOnMain(getString(R.string.network_error))
                                return@launch
                            }
                            continue                   // 單段失敗：跳段續播
                        }
                        consecutiveFailures = 0
                        updateMediaMetadata(book, chapterIndex)
                        awaitPlay(audio, engine.rate, engine.pitch)
                    }
                    if (stopped.get()) return@launch
                    // 章節播畢 → 朗讀完自動下一章（tts_auto_next）
                    if (!HttpTtsEngine.autoNextEnabled()) break
                    val nextIndex = chapterIndex + 1
                    if (nextIndex >= chapterCountOf(book)) break
                    chapterIndex = nextIndex
                    advanceReaderProgress(book, nextIndex)
                }
            } finally {
                stopInternal()
            }
        }
    }

    /** 選定引擎（未設定 → Edge 預設；使用者停用全部引擎 → 提示後停止） */
    private fun resolveEngine(): HttpTtsEngine.HttpTts? {
        val engine = HttpTtsEngine.loadSelectedEngine() ?: HttpTtsEngine.defaultEngine()
        if (!engine.enabled) {
            toastOnMain(getString(R.string.tts_no_engine))
            return null
        }
        return engine
    }

    // ═════════════════ MediaPlayer（單段一 player，語速／音調即時套用） ═════════════════

    private suspend fun awaitPlay(audio: File, rate: Float, pitch: Float) {
        suspendCancellableCoroutine<Unit> { cont ->
            val player = MediaPlayer()
            mediaPlayer = player
            val prepared = runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setDataSource(audio.absolutePath)
                player.setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "播放錯誤 $what/$extra")
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                player.prepare()
                if (rate != 1.0f || pitch != 1.0f) {
                    player.playbackParams = PlaybackParams().setSpeed(rate).setPitch(pitch)
                }
                player.start()
                true
            }
            if (!prepared.getOrDefault(false)) {
                runCatching { player.release() }
                if (mediaPlayer === player) mediaPlayer = null
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                mainHandler.post {
                    runCatching { player.stop() }
                    runCatching { player.release() }
                }
            }
        }
    }

    private fun pausePlayer() {
        mainHandler.post { runCatching { mediaPlayer?.pause() } }
    }

    private fun resumePlayer() {
        mainHandler.post { runCatching { mediaPlayer?.start() } }
    }

    // ═════════════════ Legado 引擎接縫區 ═════════════════

    /**
     * 載入章節正文（鏡射 ContentViewModel：WebBook 觸發抓取寫入
     * 快取後 BookHelp 讀回）。本地已有快取時零網路。
     */
    private suspend fun loadChapterText(book: Book, index: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                /* 接縫：BookSourceDao.getBookSource（版本差異請對照） */
                val source = AppContext.db.bookSourceDao().getBookSource(book.bookSourceUrl)
                    ?: return@runCatching null
                /* 接縫：BookChapterDao.getChapterList（版本差異請對照） */
                val chapters = AppContext.db.bookChapterDao().getChapterList(book.bookUrl)
                val chapter = chapters.getOrNull(index) ?: return@runCatching null
                /* 接縫：新版 getContentAwait（觸發抓取寫入快取）後 BookHelp 讀回 */
                WebBook.getContentAwait(source, book, chapter)
                BookHelp.getContent(book, chapter)
            }.getOrNull()
        }

    private suspend fun chapterCountOf(book: Book): Int = withContext(Dispatchers.IO) {
        runCatching {
            AppContext.db.bookChapterDao().getChapterList(book.bookUrl).size
        }.getOrDefault(0)
    }

    /** 朗讀推進章節：進度寫回＋閱讀頁同步跟隨 */
    private suspend fun advanceReaderProgress(book: Book, index: Int) =
        withContext(Dispatchers.IO) {
            runCatching {
                book.durChapterIndex = index
                book.durChapterPos = 0
                book.durChapterTime = System.currentTimeMillis()
                /* 接縫：Room 進度寫回 */
                AppContext.db.bookDao().insert(book)
                /* 接縫：閱讀頁同步跟隨（moveToNextChapter(upContent)） */
                ReadBook.moveToNextChapter(true)
            }
        }

    // ═════════════════ MediaSession／通知 ═════════════════

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumeInternal()
                }

                override fun onPause() {
                    pauseInternal()
                }

                override fun onStop() {
                    stopInternal()
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState(isPaused: Boolean) {
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (isPaused) PlaybackStateCompat.STATE_PAUSED
                    else PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1.0f
                )
                .build()
        )
    }

    private suspend fun updateMediaMetadata(book: Book, index: Int) =
        withContext(Dispatchers.IO) {
            runCatching {
                val chapter = AppContext.db.bookChapterDao()
                    .getChapterList(book.bookUrl).getOrNull(index)
                chapterTitle = chapter?.title ?: book.name
                mediaSession?.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, book.name)
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.author)
                        .build()
                )
                updateNotification(paused.value)
            }.getOrDefault(Unit)
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.read_aloud),
                NotificationManager.IMPORTANCE_LOW
            )
            runCatching { manager.createNotificationChannel(channel) }
        }
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val book = ReadBook.book
        val toggleTitle =
            if (isPaused) getString(R.string.read_aloud_resume)
            else getString(R.string.read_aloud_pause)
        val toggleIcon =
            if (isPaused) android.R.drawable.ic_media_play
            else android.R.drawable.ic_media_pause
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(book?.name ?: getString(R.string.read_aloud))
            .setContentText(chapterTitle ?: getString(R.string.read_aloud))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action.Builder(
                    toggleIcon, toggleTitle, servicePendingIntent(
                        if (isPaused) ACTION_RESUME else ACTION_PAUSE
                    )
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.read_aloud_stop),
                    servicePendingIntent(ACTION_STOP)
                ).build()
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun updateNotification(isPaused: Boolean) {
        mainHandler.post {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(isPaused))
        }
    }

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, HttpReadAloudService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ═════════════════ 音訊焦點 ═════════════════

    private fun requestAudioFocus() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audioManager?.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    // ═════════════════ 工具 ═════════════════

    private fun toastOnMain(text: String) {
        mainHandler.post {
            Toast.makeText(AppContext.ctx, text, Toast.LENGTH_SHORT).show()
        }
    }
}
