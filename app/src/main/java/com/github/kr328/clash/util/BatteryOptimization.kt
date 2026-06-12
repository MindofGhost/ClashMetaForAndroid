package com.github.kr328.clash.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

fun Activity.isIgnoringBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
        return true

    return getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) == true
}

fun Activity.requestIgnoreBatteryOptimizations(openSettingsIfAllowed: Boolean = false) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
        return

    val ignoringOptimizations = isIgnoringBatteryOptimizations()

    if (ignoringOptimizations && !openSettingsIfAllowed)
        return

    val intent = if (ignoringOptimizations) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
    }

    runCatching {
        startActivity(intent)
    }.onFailure {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
