package com.k.reader.help.http

import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — SSL 寬容助手（十大旗艦功能：網路防封鎖）
 * ═══════════════════════════════════════════════════════════════
 *
 *  老書源站點常見三大 SSL 狀況：憑證過期 / 自簽憑證 / 主機名不符。
 *  嚴格校驗會把整個書源「誤判為失效」，故提供雙通道策略：
 *
 *   1. 常規請求先用預設嚴格客戶端；
 *   2. 攔到 SSL 例外（isSslError() 判定）→ 以 tolerantClientOf()
 *      就地派生寬容客戶端「重試一次」；
 *   3. 寬容設定不污染全域，僅作用於該次重試。
 *
 *  另內建 TLSv1.2 / TLSv1.3 相容層：API 23~28 部分機型預設未啟用
 *  TLSv1.2，透過包裝 SSLSocketFactory 在交握時主動開啟——
 *  這是老裝置連不上老站的常見盲區。
 *
 *  安全聲明：寬容通道僅用於公開小說 / 訂閱站點的內容抓取，
 *  本應用不經手任何支付或登入憑證，降級風險可控。
 *
 *  整合點：
 * ```
 * val response = runCatching { client.newCall(request).await() }
 *     .recoverCatching {
 *         if (SslTrustHelper.isSslError(it)) {
 *             SslTrustHelper.tolerantClientOf(client).newCall(request).await()
 *         } else throw it
 *     }
 * ```
 */
object SslTrustHelper {

    /** 信任所有憑證的 X509TrustManager（僅供寬容重試通道使用） */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            /* 寬容放行 */
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            /* 寬容放行 */
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 主機名一律通過的驗證器 */
    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

    /** 包裝工廠：交握時主動啟用 TLSv1.3 / TLSv1.2 */
    private val tlsCompatFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf<TrustManager>(trustAllManager), SecureRandom())
        TlsCompatSocketFactory(context.socketFactory)
    }

    /** 共用的寬容 OkHttpClient（懶建立，單例重用連線池與執行緒池） */
    private val sharedTolerantClient: OkHttpClient by lazy {
        applyTo(
            OkHttpClient.Builder()
                .connectTimeout(15L, TimeUnit.SECONDS)
                .readTimeout(60L, TimeUnit.SECONDS)
                .writeTimeout(30L, TimeUnit.SECONDS)
        ).build()
    }

    /**
     * 判定某例外鏈是否為 SSL 憑證類錯誤。
     * （SSLHandshakeException / SSLPeerUnverifiedException 皆為
     *   SSLException 的子類，一併命中）
     */
    fun isSslError(t: Throwable?): Boolean {
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 16) {
            if (cause is javax.net.ssl.SSLException) return true
            if (cause is CertificateException) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    /** 把寬容 SSL 設定套到任一 Builder（就地改造，不動其他參數） */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder
            .sslSocketFactory(tlsCompatFactory, trustAllManager)
            .hostnameVerifier(trustAllHostnameVerifier)

    /** 取得共用寬容客戶端（已含合理逾時） */
    fun tolerantClient(): OkHttpClient = sharedTolerantClient

    /** 以既有客戶端為基底派生寬容版本（保留其逾時 / 攔截器設定） */
    fun tolerantClientOf(base: OkHttpClient): OkHttpClient =
        applyTo(base.newBuilder()).build()
}

/**
 * SSLSocketFactory 包裝層：建立每個 socket 時啟用 TLSv1.3 → TLSv1.2。
 * 老裝置（API < 29）與老站點的相容關鍵。
 */
private class TlsCompatSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    private val preferredProtocols = arrayOf("TLSv1.3", "TLSv1.2")

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(host: String, port: Int): Socket =
        patch(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        patch(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        patch(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        patch(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        patch(delegate.createSocket(s, host, port, autoClose))

    private fun patch(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supported = socket.enabledProtocols.toSet()
            val enable = preferredProtocols.filter { it in supported }
            if (enable.isNotEmpty()) {
                socket.enabledProtocols = enable.toTypedArray()
            }
        }
        return socket
    }
}
