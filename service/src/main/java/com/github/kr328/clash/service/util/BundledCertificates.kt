package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private val bundledTls by lazy {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val systemTrust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    systemTrust.acceptedIssuers.forEachIndexed { index, certificate ->
        store.setCertificateEntry("system-$index", certificate)
    }
    Global.application.resources.openRawResource(R.raw.browser_ca).use { input ->
        CertificateFactory.getInstance("X.509").generateCertificates(input)
            .forEachIndexed { index, certificate ->
                store.setCertificateEntry("browser-$index", certificate)
            }
    }
    factory.init(store)
    val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }
    context.socketFactory to trustManager
}

internal fun OkHttpClient.Builder.withBundledCertificates(): OkHttpClient.Builder {
    val (socketFactory, trustManager) = bundledTls
    return sslSocketFactory(socketFactory, trustManager)
}
