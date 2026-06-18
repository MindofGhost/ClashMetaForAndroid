package com.github.kr328.clash.util

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

fun Activity.openAutostartSettings() {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val intents = backgroundSettingsIntents(manufacturer) + commonBackgroundSettingsIntents() +
            appDetailsIntent()

    intents.forEach { intent ->
        if (runCatching { startActivity(intent) }.isSuccess)
            return
    }

    startActivity(Intent(Settings.ACTION_SETTINGS))
}

private fun Activity.appDetailsIntent(): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$packageName"))
}

private fun backgroundSettingsIntents(manufacturer: String): List<Intent> {
    return when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> listOf(
            componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            ),
        )

        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
            componentIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            componentIntent(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            ),
            componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
        )

        manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") -> listOf(
            componentIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            componentIntent(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            componentIntent(
                "com.realme.securitycenter",
                "com.realme.securitycenter.permission.startup.StartupAppListActivity"
            ),
        )

        manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
        )

        else -> emptyList()
    }
}

private fun commonBackgroundSettingsIntents(): List<Intent> {
    return listOf(
        componentIntent(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        componentIntent(
            "com.samsung.android.sm",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        componentIntent(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity"
        ),
        componentIntent(
            "com.samsung.android.sm",
            "com.samsung.android.sm.battery.ui.BatteryActivity"
        ),
    )
}

private fun componentIntent(packageName: String, className: String): Intent {
    return Intent().setComponent(ComponentName(packageName, className))
}
