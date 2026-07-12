package com.github.kr328.clash.common.compat

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.getSystemService

fun Context.hasUsageAccess(): Boolean {
    val appOps = getSystemService<AppOpsManager>() ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
    }

    return mode == AppOpsManager.MODE_ALLOWED
}

fun Context.usageAccessSettingsIntent(): Intent {
    val packageUri = Uri.parse("package:$packageName")
    val appIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri)

    return if (appIntent.resolveActivity(packageManager) != null)
        appIntent
    else
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
