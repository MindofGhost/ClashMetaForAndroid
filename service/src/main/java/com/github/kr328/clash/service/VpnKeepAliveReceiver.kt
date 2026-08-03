package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.service.store.ServiceStore
import java.util.concurrent.TimeUnit

class VpnKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS)
            return

        if (!ServiceStore(context).keepVpnAwake || !StatusProvider.shouldStartClashOnBoot) {
            cancel(context)
            return
        }

        if (!StatusProvider.serviceRunning) {
            if (VpnService.prepare(context) == null) {
                runCatching {
                    context.startForegroundServiceCompat(
                        Intent().setComponent(TunService::class.componentName)
                    )
                    Log.i("VPN keep-alive restarted TunService")
                }.onFailure {
                    Log.w("VPN keep-alive restart failed: ${it.message}", it)
                }
            } else {
                Log.w("VPN keep-alive cannot restart without VPN permission")
                StatusProvider.shouldStartClashOnBoot = false
                cancel(context)
                return
            }
        }

        schedule(context)
    }

    companion object {
        private val INTERVAL = TimeUnit.MINUTES.toMillis(15)
        private val SUPPORTED_ACTIONS = setOf(
            Intents.ACTION_VPN_KEEP_ALIVE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        fun schedule(context: Context) {
            val alarm = context.getSystemService<AlarmManager>() ?: return
            val triggerAt = System.currentTimeMillis() + INTERVAL
            val pendingIntent = pendingIntent(context)

            alarm.cancel(pendingIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }

        fun cancel(context: Context) {
            context.getSystemService<AlarmManager>()?.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(Intents.ACTION_VPN_KEEP_ALIVE)
                .setComponent(VpnKeepAliveReceiver::class.componentName)

            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
    }
}
