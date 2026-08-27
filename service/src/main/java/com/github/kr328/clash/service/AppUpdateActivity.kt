package com.github.kr328.clash.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.service.util.installDownloadedAppUpdate

class AppUpdateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("App update notification action received: ${intent?.action}")

        when (intent?.action) {
            Intents.ACTION_APP_UPDATE_INSTALL -> startDownload(intent)
            Intents.ACTION_APP_UPDATE_OPEN_DOWNLOADED -> runCatching {
                installDownloadedAppUpdate()
            }.onFailure {
                Log.w("Open downloaded app update failed: ${it.message}", it)
            }
        }

        finishAndRemoveTask()
    }

    private fun startDownload(source: Intent) {
        val url = source.getStringExtra(Intents.EXTRA_URL)
        val cert = source.getStringExtra(Intents.EXTRA_CERT_SHA256)
        if (url.isNullOrBlank() || cert.isNullOrBlank()) {
            Log.w("Start app update download failed: notification data is missing")
            return
        }

        val service = Intent(source)
            .setComponent(ProfileWorker::class.componentName)

        runCatching {
            startForegroundServiceCompat(service)
            Log.i("App update download service requested")
        }.onFailure {
            Log.w("Start app update download service failed: ${it.message}", it)
        }
    }
}
