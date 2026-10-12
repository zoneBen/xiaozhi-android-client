package me.xiaozhi.androidclient.email

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 「信任任何证书」的 `SSLSocketFactory`，给用自签证书的 SMTP 服务器用。
 *
 * **它必须是一个能 `new` 出来的类**，而不是一个返回工厂的工具函数：
 * JavaMail 是通过 `mail.smtp.ssl.socketFactory.class` 里的**类名**去
 * `Class.forName(...).newInstance()` 造它的，所以这个类要
 * ①是 `SSLSocketFactory` 的子类、②有公开无参构造。写成 `object` 或者只返回实例，
 * JavaMail 那边都会在发信那一刻失败。
 *
 * 因此也**必须配一条 R8 keep 规则**（见 `app/proguard-rules.pro`）：
 * 类名被混淆之后，`Class.forName` 就找不到了，而那要开了混淆的 release 包才会暴露。
 *
 * 名字里带 Trusting 是为了它在代码搜索里一眼可见——这类东西最怕散落某处、后人不知道
 * 它存在。风险是明确的：接受任何证书等于放弃了对中间人的防护，只该用在用户自己的
 * 服务器上，所以设置页那行必须写明「会降低安全性」。
 */
class TrustingSslSocketFactory : SSLSocketFactory() {

    // 用 lazy 而不是在构造里建：构造是给反射用的，越简单越好，
    // 而且 SSLContext.getInstance 会抛受检异常，塞进构造会把异常路径搞得很难看。
    private val delegate: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
            .socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, host, port, autoClose)

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = delegate.createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = delegate.createSocket(address, port, localAddress, localPort)
}
