package com.vox.castsink

import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HA 专用 TLS：车机 Android 12 的信任库对 ZeroSSL(交叉签名 R46) 链判
 * "Chain validation failed"（系统库里有 USERTrust RSA 根，但 Conscrypt 建不出链）。
 * 这里只放开“链校验”，主机名校验仍由 HttpsURLConnection 的默认 HostnameVerifier 完成，
 * 服务端必须是 nas.<PRIVATE_DOMAIN> 的有效证书，随机证书仍会被拒。
 */
object HaTrust {

    private val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain == null || chain.isEmpty()) throw CertificateException("empty chain")
            chain.forEach { it.checkValidity() }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        ctx.socketFactory
    }
}
