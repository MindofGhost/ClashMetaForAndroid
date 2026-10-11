package com.github.kr328.clash.service.util

import android.content.Context
import android.net.Network
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import kotlin.coroutines.resumeWithException

internal suspend fun <T : Any> Context.withAppMetadataRouteRetry(
    name: String,
    client: OkHttpClient,
    request: suspend (OkHttpClient) -> T?,
): T? {
    try {
        currentCoroutineContext().ensureActive()
        Log.i("App metadata $name: route=default")
        try {
            request(client)?.let { return it }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Log.w("App metadata $name failed on default route (${e.javaClass.simpleName})")
        }
        currentCoroutineContext().ensureActive()
        val network = appUpdateDirectNetwork()
        if (network == null) {
            Log.w("App metadata $name direct retry skipped: no physical network")
            return null
        }
        // A retry must never reuse a connection created on the default route.
        val direct = client.newBuilder()
            .connectionPool(ConnectionPool())
            .dispatcher(Dispatcher())
            .useDirectNetwork(network)
            .build()
        try {
            Log.i("App metadata $name: retry route=direct")
            return request(direct)
        } finally {
            direct.connectionPool.evictAll()
            direct.dispatcher.executorService.shutdown()
        }
    } finally {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal fun OkHttpClient.Builder.useDirectNetwork(network: Network): OkHttpClient.Builder =
    socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        })
        .proxy(Proxy.NO_PROXY)

internal suspend fun Call.awaitMetadataResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, cancelledResponse, _ -> cancelledResponse.close() }
        }
    })
}
