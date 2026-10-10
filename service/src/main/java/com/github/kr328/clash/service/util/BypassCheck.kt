package com.github.kr328.clash.service.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.BypassCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal suspend fun Context.checkBypassConditions(
    checks: List<BypassCheck>,
    onResult: (domain: String, result: Boolean, expected: Boolean) -> Unit,
): Boolean {
    if (checks.isEmpty()) return true
    val network = bypassCheckNetwork() ?: return false
    val client = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                network.getAllByName(hostname).toList()
        })
        .proxy(Proxy.NO_PROXY)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    try {
        for (check in checks) {
            val url = check.url.toHttpUrlOrNull() ?: return false
            val request = Request.Builder().url(url).get().build()
            val result = client.newCall(request).checkSuccess()
            onResult(url.host, result, check.alive)
            if (result != check.alive) return false
            // A network disappearing must not satisfy an expected failed request.
            if (bypassCheckNetwork() != network) return false
        }
        return true
    } finally {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

private fun Context.bypassCheckNetwork(): Network? {
    val connectivity = getSystemService<ConnectivityManager>() ?: return null
    return connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
        val priority = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            else -> 3
        }
        network to priority
    }.minByOrNull { it.second }?.first
}

private suspend fun Call.checkSuccess(): Boolean = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resume(false)
        }

        override fun onResponse(call: Call, response: Response) {
            val success = response.use { it.isSuccessful }
            continuation.resume(success)
        }
    })
}
