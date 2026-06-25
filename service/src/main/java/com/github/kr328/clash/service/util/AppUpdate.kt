@file:Suppress("DEPRECATION")

package com.github.kr328.clash.service.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.service.ProfileWorker
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.sync.Mutex
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val APP_UPDATE_CHANNEL = "app_update_channel"
private val appUpdateDownloadLock = Mutex()

suspend fun Context.handleAppUpdateHeaders(source: String, headers: SubscriptionHeaders?) {
    if (!ServiceStore(this).appUpdateNotifications) {
        cancelAppUpdateNotifications()
        return
    }

    val template = headers?.appUpdateUrl
    val expectedCert = headers?.appUpdateCertSha256

    if (template.isNullOrBlank() || expectedCert.isNullOrBlank()) {
        cancelAvailableAppUpdateNotification()
        return
    }

    if (!currentSigningCertificateSha256().contains(expectedCert)) {
        cancelAppUpdateNotifications()
        Log.w("App update ignored: signing certificate mismatch")
        return
    }

    if (restoreDownloadedAppUpdateNotification()) {
        cancelAvailableAppUpdateNotification()
        return
    }

    val url = resolveAvailableAppUpdateUrl(source, template)
    if (url == null) {
        cancelAvailableAppUpdateNotification()
        Log.w("App update ignored: no APK URL is available")
        return
    }

    showAppUpdateNotification(url, expectedCert)
}

suspend fun Context.downloadAndInstallAppUpdate(url: String, expectedCert: String) {
    cancelAvailableAppUpdateNotification()

    if (!appUpdateDownloadLock.tryLock()) {
        Log.i("App update download skipped: already running")
        return
    }

    try {
        if (!currentSigningCertificateSha256().contains(expectedCert)) {
            Log.w("App update download skipped: signing certificate mismatch")
            return
        }

        val updateDir = appUpdateCacheDir.apply {
            deleteRecursively()
            mkdirs()
        }
        val apk = appUpdateApk
        val client = appUpdateClient()
        val request = Request.Builder()
            .url(url)
            .get()
            .withUrlBasicAuth()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    throw IllegalStateException("Download failed: HTTP ${response.code}")

                val body = response.body ?: throw IllegalStateException("Empty response body")
                apk.outputStream().use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }

            if (!downloadedApkMatchesCurrentApp(apk)) {
                apk.delete()
                Log.w("App update download rejected: APK signature or package name mismatch")
                return
            }

            showAppUpdateReadyNotification()
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    } finally {
        appUpdateDownloadLock.unlock()
    }
}

fun Context.restoreDownloadedAppUpdateNotification(): Boolean {
    val apk = appUpdateApk
    if (!apk.exists())
        return false

    if (downloadedApkMatchesCurrentApp(apk)) {
        showAppUpdateReadyNotification()
        return true
    } else {
        clearAppUpdateCache()
    }

    return false
}

fun Context.installDownloadedAppUpdate() {
    val apk = appUpdateApk
    if (!apk.exists()) {
        cancelReadyAppUpdateNotification()
        Log.w("App update install skipped: downloaded APK is missing")
        return
    }

    if (!downloadedApkMatchesCurrentApp(apk)) {
        clearAppUpdateCache()
        Log.w("App update install skipped: APK signature or package name mismatch")
        return
    }

    installAppUpdate(apk)
}

fun Context.clearAppUpdateCache() {
    cancelAppUpdateNotifications()
    appUpdateCacheDir.deleteRecursively()
}

private fun Context.resolveAvailableAppUpdateUrl(source: String, template: String): String? {
    val abis = Build.SUPPORTED_ABIS.takeIf { it.isNotEmpty() } ?: return null

    return abis.asSequence()
        .mapNotNull { abi -> resolveAppUpdateUrl(source, template.replace("{{abi}}", abi)) }
        .firstOrNull { isAppUpdateUrlAvailable(it) }
}

private fun Context.resolveAppUpdateUrl(source: String, value: String): String? {
    if (value.startsWith("https://", true))
        return value

    val base = source.toHttpUrlOrNull() ?: return null
    val resolved = base.resolve(value) ?: return null
    if (!resolved.isHttps)
        return null

    if (resolved.query != null || base.query == null)
        return resolved.toString()

    return resolved.newBuilder()
        .encodedQuery(base.encodedQuery)
        .build()
        .toString()
}

private fun isAppUpdateUrlAvailable(url: String): Boolean {
    val client = appUpdateClient()
    val head = Request.Builder()
        .url(url)
        .head()
        .withUrlBasicAuth()
        .build()

    client.newCall(head).execute().use { response ->
        if (response.code == 200)
            return true

        if (response.code != 405)
            return false
    }

    val ranged = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-0")
        .get()
        .withUrlBasicAuth()
        .build()

    client.newCall(ranged).execute().use { response ->
        return response.code == 200 || response.code == 206
    }
}

private fun Context.showAppUpdateNotification(url: String, expectedCert: String) {
    val notificationManager = NotificationManagerCompat.from(this)
    notificationManager.createNotificationChannelsCompat(
        listOf(
            NotificationChannelCompat.Builder(
                APP_UPDATE_CHANNEL,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName(getString(R.string.app_update_channel)).build()
        )
    )

    val intent = Intent(Intents.ACTION_APP_UPDATE_INSTALL)
        .setComponent(ProfileWorker::class.componentName)
        .putExtra(Intents.EXTRA_URL, url)
        .putExtra(Intents.EXTRA_CERT_SHA256, expectedCert)

    val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PendingIntent.getForegroundService(
            this,
            R.id.nf_app_update,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    } else {
        PendingIntent.getService(
            this,
            R.id.nf_app_update,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    val notification = NotificationCompat.Builder(this, APP_UPDATE_CHANNEL)
        .setContentTitle(getString(R.string.app_update_available))
        .setContentText(getString(R.string.app_update_tap_to_install))
        .setColor(getColorCompat(R.color.color_clash))
        .setSmallIcon(R.drawable.ic_logo_service)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build()

    notificationManager.notify(R.id.nf_app_update, notification)
}

private fun Context.showAppUpdateReadyNotification() {
    val notificationManager = NotificationManagerCompat.from(this)
    notificationManager.createNotificationChannelsCompat(
        listOf(
            NotificationChannelCompat.Builder(
                APP_UPDATE_CHANNEL,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName(getString(R.string.app_update_channel)).build()
        )
    )

    val intent = Intent(Intents.ACTION_APP_UPDATE_OPEN_DOWNLOADED)
        .setComponent(ProfileWorker::class.componentName)

    val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PendingIntent.getForegroundService(
            this,
            R.id.nf_app_update_ready,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    } else {
        PendingIntent.getService(
            this,
            R.id.nf_app_update_ready,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    val notification = NotificationCompat.Builder(this, APP_UPDATE_CHANNEL)
        .setContentTitle(getString(R.string.app_update_downloaded))
        .setContentText(getString(R.string.app_update_ready_to_install))
        .setColor(getColorCompat(R.color.color_clash))
        .setSmallIcon(R.drawable.ic_logo_service)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(false)
        .build()

    notificationManager.notify(R.id.nf_app_update_ready, notification)
}

private fun Context.cancelAppUpdateNotifications() {
    cancelAvailableAppUpdateNotification()
    cancelReadyAppUpdateNotification()
}

private fun Context.cancelAvailableAppUpdateNotification() {
    NotificationManagerCompat.from(this).cancel(R.id.nf_app_update)
}

private fun Context.cancelReadyAppUpdateNotification() {
    NotificationManagerCompat.from(this).cancel(R.id.nf_app_update_ready)
}

private fun Context.installAppUpdate(apk: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.app-update-files", apk)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    startActivity(intent)
}

private fun Context.downloadedApkMatchesCurrentApp(apk: File): Boolean {
    val archiveInfo = packageManager.getPackageArchiveInfo(
        apk.absolutePath,
        signingPackageInfoFlags()
    ) ?: return false

    if (archiveInfo.packageName != packageName)
        return false

    return signingCertificateSha256(archiveInfo).intersect(currentSigningCertificateSha256()).isNotEmpty()
}

private fun Context.currentSigningCertificateSha256(): Set<String> {
    val info = packageManager.getPackageInfo(packageName, signingPackageInfoFlags())
    return signingCertificateSha256(info)
}

private fun signingPackageInfoFlags(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
}

private fun signingCertificateSha256(info: PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners ?: emptyArray()
    } else {
        info.signatures ?: emptyArray()
    }

    return signatures.map { signature ->
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }.toSet()
}

private fun appUpdateClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private val Context.appUpdateCacheDir: File
    get() = cacheDir.resolve("updates")

private val Context.appUpdateApk: File
    get() = appUpdateCacheDir.resolve("update.apk")

private fun Request.Builder.withUrlBasicAuth(): Request.Builder {
    val url = build().url
    return withUrlBasicAuth(url)
}

private fun Request.Builder.withUrlBasicAuth(url: HttpUrl): Request.Builder {
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        header("Authorization", Credentials.basic(url.username, url.password))
    }

    return this
}
