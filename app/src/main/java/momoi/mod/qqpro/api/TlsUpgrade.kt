package momoi.mod.qqpro.api

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * API 19（Android 4.4）默认 TLS 只到 1.0，而 web.qun.qq.com（stgw）只接受 TLS 1.2 +
 * ECDHE-RSA-AES128-GCM-SHA256 一类的套件，直接裸 HttpURLConnection 握手会被拒
 * （SSL handshake aborted）。这里给 HTTPS 连接换一个强制启用 TLS 1.2 的 socket factory。
 */
object TlsUpgrade {

    fun enableTls12(conn: HttpsURLConnection) {
        runCatching { conn.sslSocketFactory = Tls12SocketFactory() }
    }

    private class Tls12SocketFactory : SSLSocketFactory() {
        private val delegate: SSLSocketFactory? = runCatching {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, null, null)
            sc.socketFactory
        }.getOrNull()

        override fun getDefaultCipherSuites(): Array<String> =
            delegate?.defaultCipherSuites ?: arrayOf()

        override fun getSupportedCipherSuites(): Array<String> =
            delegate?.supportedCipherSuites ?: arrayOf()

        override fun createSocket(): Socket = enable(requireDelegate().createSocket())
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            enable(requireDelegate().createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket =
            enable(requireDelegate().createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            enable(requireDelegate().createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket =
            enable(requireDelegate().createSocket(host, port))
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            enable(requireDelegate().createSocket(host, port, localHost, localPort))

        private fun requireDelegate(): SSLSocketFactory =
            delegate ?: throw IOException("TLS context unavailable")

        private fun enable(s: Socket): Socket {
            if (s is SSLSocket) {
                runCatching {
                    s.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1")
                    val supported = s.supportedCipherSuites
                    val preferred = listOf(
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                        "TLS_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_RSA_WITH_AES_128_CBC_SHA",
                    )
                    val usable = preferred.filter { it in supported }.toTypedArray()
                    if (usable.isNotEmpty()) s.enabledCipherSuites = usable
                }
            }
            return s
        }
    }
}
