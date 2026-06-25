package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.util.hwid
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SubscriptionHeaders(
    val hasUserInfo: Boolean = false,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val subInfoColor: String? = null,
    val subInfoText: String? = null,
    val subInfoButtonText: String? = null,
    val subInfoButtonLink: String? = null,
    val subExpire: Boolean = false,
    val subExpireButtonLink: String? = null,
    val profileUpdateIntervalHours: Long? = null,
    val appUpdateUrl: String? = null,
    val appUpdateCertSha256: String? = null,
)

suspend fun Context.fetchSubscriptionHeaders(source: String): SubscriptionHeaders? {
    if (!source.startsWith("https://", true))
        return null

    val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val versionName = packageManager.getPackageInfo(packageName, 0).versionName
    val request = Request.Builder()
        .url(source)
        .header("User-Agent", "ClashMetaForAndroid/$versionName")
        .header("x-hwid", hwid)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful)
            return null

        return parseSubscriptionHeaders(response.headers)
    }
}

private fun parseSubscriptionHeaders(headers: Headers): SubscriptionHeaders {
    var upload: Long = 0
    var download: Long = 0
    var total: Long = 0
    var expire: Long = 0

    val userInfo = headers["subscription-userinfo"]

    userInfo?.split(";")?.forEach { flag ->
        val info = flag.split("=", limit = 2)
        if (info.size != 2 || info[1].isEmpty())
            return@forEach

        val key = info[0].trim()
        val value = info[1].trim()

        when {
            key.contains("upload") -> upload = value.toLongFromHeader()
            key.contains("download") -> download = value.toLongFromHeader()
            key.contains("total") -> total = value.toLongFromHeader()
            key.contains("expire") -> expire = (value.toDouble() * 1000).toLong()
        }
    }

    val subInfoText = headers["sub-info-text"]
        ?.trim()
        ?.decodeHeaderText()
        ?.take(200)
        ?.takeIf { it.isNotEmpty() && it != "0" }

    val subInfoColor = headers["sub-info-color"]
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it == "red" || it == "blue" || it == "green" }
        ?: "blue"

    return SubscriptionHeaders(
        hasUserInfo = userInfo != null,
        upload = upload,
        download = download,
        total = total,
        expire = expire,
        subInfoColor = subInfoText?.let { subInfoColor },
        subInfoText = subInfoText,
        subInfoButtonText = headers["sub-info-button-text"]?.trim()?.decodeHeaderText()?.take(25)?.takeIf { it.isNotEmpty() },
        subInfoButtonLink = headers["sub-info-button-link"]?.trim()?.takeIf { it.isNotEmpty() },
        subExpire = headers["sub-expire"]?.trim()?.let { it == "1" || it.equals("true", true) } == true,
        subExpireButtonLink = headers["sub-expire-button-link"]?.trim()?.takeIf { it.isNotEmpty() },
        profileUpdateIntervalHours = headers["profile-update-interval"]?.trim()?.toLongOrNull(),
        appUpdateUrl = headers["app-update-url"]?.trim()?.takeIf { it.isNotEmpty() },
        appUpdateCertSha256 = headers["app-update-cert-sha256"]
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(":", "")
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
    )
}

private fun String.toLongFromHeader(): Long {
    return BigDecimal(split('.').first()).longValueExact()
}

private fun String.decodeHeaderText(): String {
    return if (contains('%')) {
        runCatching {
            URLDecoder.decode(this, StandardCharsets.UTF_8.name())
        }.getOrDefault(this)
    } else {
        this
    }
}
