package com.github.kr328.clash.common.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

private val Hex16 = Regex("^[0-9a-f]{16}$")

val Context.hwid: String
    get() {
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )?.lowercase(Locale.ROOT)

        if (androidId != null && androidId != BrokenAndroidId && Hex16.matches(androidId)) {
            return androidId
        }

        return hashToHex16(
            listOfNotNull(
                androidId,
                Build.FINGERPRINT,
                Build.BRAND,
                Build.DEVICE,
                Build.MODEL,
                Build.MANUFACTURER,
            ).joinToString(separator = "\n")
        )
    }

private fun hashToHex16(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))

    return digest.take(8).joinToString(separator = "") {
        "%02x".format(it.toInt() and 0xff)
    }
}

private const val BrokenAndroidId = "9774d56d682e549c"
