package com.github.kr328.clash.service.clash.module

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.hasUsageAccess
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

class TunPauseModule(
    service: Service,
    private val setPaused: (Boolean) -> Unit,
) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private val usageStats = service.getSystemService<UsageStatsManager>()
    private val activityManager = service.getSystemService<ActivityManager>()
    private val power = service.getSystemService<PowerManager>()
    private val keyguard = service.getSystemService<KeyguardManager>()
    private val wakeLock = power?.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "ClashMetaForAndroid:TunPause",
    )?.apply {
        setReferenceCounted(false)
    }
    private var foregroundPackage: String? = null
    private var eventCursor = System.currentTimeMillis() - INITIAL_EVENT_LOOKBACK

    override suspend fun run() {
        var paused = false
        val screenEvents = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        try {
            while (coroutineContext.isActive) {
                updateUsageEvents()

                val selected = store.tunPausePackages
                val foregroundProcesses = queryForegroundProcesses()
                val visibleSelectedPackage = if (foregroundProcesses.isNotEmpty()) {
                    foregroundProcesses.firstOrNull(selected::contains)
                } else {
                    foregroundPackage?.takeIf(selected::contains)
                        ?: if (foregroundPackage == null)
                            queryLatestVisiblePackage()?.takeIf(selected::contains)
                        else
                            null
                }
                val shouldPause = visibleSelectedPackage != null &&
                        service.hasUsageAccess() &&
                        power?.isInteractive != false &&
                        keyguard?.isKeyguardLocked != true

                if (shouldPause != paused) {
                    changePaused(shouldPause)
                    paused = shouldPause

                    Log.i(
                        if (paused)
                            "TUN paused for visible selected app: $visibleSelectedPackage"
                        else
                            "TUN restored after selected app left foreground: " +
                                    "processes=${foregroundProcesses.joinToString()}, event=$foregroundPackage"
                    )
                }

                withTimeoutOrNull(POLL_INTERVAL) {
                    screenEvents.receive()
                }
            }
        } finally {
            releaseWakeLock()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun changePaused(paused: Boolean) {
        if (paused && wakeLock?.isHeld == false)
            wakeLock.acquire()

        try {
            setPaused(paused)
        } finally {
            if (!paused)
                releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true)
            wakeLock.release()
    }

    private fun updateUsageEvents() {
        if (!service.hasUsageAccess()) {
            foregroundPackage = null
            eventCursor = System.currentTimeMillis() - EVENT_OVERLAP
            return
        }

        val now = System.currentTimeMillis()
        val events = runCatching {
            usageStats?.queryEvents(eventCursor, now)
        }.getOrNull()
        if (events == null) {
            foregroundPackage = null
            eventCursor = now - EVENT_OVERLAP
            return
        }
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> foregroundPackage = packageName

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (foregroundPackage == packageName)
                        foregroundPackage = null
                }
            }

        }

        // Some OEMs publish usage events late while retaining their original timestamp.
        eventCursor = now - EVENT_OVERLAP
    }

    private fun queryLatestVisiblePackage(): String? {
        if (!service.hasUsageAccess())
            return null

        val now = System.currentTimeMillis()
        val stats = runCatching {
            usageStats?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - LATEST_USAGE_LOOKBACK,
                now,
            )
        }.getOrNull() ?: return null

        return stats.maxByOrNull(::lastVisibleTime)?.packageName
    }

    private fun queryForegroundProcesses(): Set<String> {
        return runCatching {
            activityManager?.runningAppProcesses
                ?.asSequence()
                ?.filter {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
                ?.flatMap { it.pkgList.orEmpty().asSequence() }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun lastVisibleTime(stats: UsageStats): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            stats.lastTimeVisible
        else
            stats.lastTimeUsed
    }

    companion object {
        private const val POLL_INTERVAL = 500L
        private const val INITIAL_EVENT_LOOKBACK = 24 * 60 * 60 * 1000L
        private const val EVENT_OVERLAP = 30 * 1000L
        private const val LATEST_USAGE_LOOKBACK = 24 * 60 * 60 * 1000L
    }
}
