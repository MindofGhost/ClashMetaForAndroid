package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.util.startClashService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT -> restartClash(context, intent.action)
        }
    }

    private fun restartClash(context: Context, action: String?) {
        if (!StatusProvider.shouldStartClashOnBoot)
            return

        runCatching {
            context.startClashService()
        }.onFailure {
            Log.w("Restart clash from $action failed: ${it.message}", it)
        }
    }
}
