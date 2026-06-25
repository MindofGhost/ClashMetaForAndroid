package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.clearAppUpdateCacheIfPackageChanged
import com.github.kr328.clash.service.util.downloadAndInstallAppUpdate
import com.github.kr328.clash.service.util.installDownloadedAppUpdate
import com.github.kr328.clash.service.util.restoreDownloadedAppUpdateNotification
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.TimeUnit

class ProfileWorker : BaseService() {
    private val service: ProfileWorker
        get() = this

    private val jobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()

        createChannels()

        foreground()
        clearAppUpdateCacheIfPackageChanged()
        restoreDownloadedAppUpdateNotification()

        launch {
            delay(TimeUnit.SECONDS.toMillis(10))

            while (true) {
                pollJob()?.join() ?: break
            }

            stopSelf()
        }
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                intent.uuid?.also {
                    val forceReload = intent.getBooleanExtra(Intents.EXTRA_FORCE_RELOAD, false)
                    val job = launch {
                        runDeduplicated(it, "request", forceReload)
                    }

                    enqueueJob(job)
                }
            }
            Intents.ACTION_PROFILE_SCHEDULE_UPDATES -> {
                val job = launch {
                    ProfileReceiver.rescheduleAll(service)

                    delay(TimeUnit.SECONDS.toMillis(30))
                }

                enqueueJob(job)
            }
            Intents.ACTION_PROFILE_UPDATE_ON_START -> {
                val job = launch {
                    updateAutoProfilesOnStart()
                }

                enqueueJob(job)
            }
            Intents.ACTION_PROFILE_UPDATE_STALE -> {
                val job = launch {
                    updateStaleProfiles()
                }

                enqueueJob(job)
            }
            Intents.ACTION_APP_UPDATE_INSTALL -> {
                val url = intent.getStringExtra(Intents.EXTRA_URL)
                val cert = intent.getStringExtra(Intents.EXTRA_CERT_SHA256)
                if (!url.isNullOrBlank() && !cert.isNullOrBlank()) {
                    val job = launch {
                        try {
                            downloadAndInstallAppUpdate(url, cert)
                        } catch (e: Exception) {
                            Log.w("Install app update failed: ${e.message}", e)
                        }
                    }

                    enqueueJob(job)
                }
            }
            Intents.ACTION_APP_UPDATE_OPEN_DOWNLOADED -> {
                val job = launch {
                    try {
                        installDownloadedAppUpdate()
                    } catch (e: Exception) {
                        Log.w("Open downloaded app update failed: ${e.message}", e)
                    }
                }

                enqueueJob(job)
            }
        }

        return START_NOT_STICKY
    }

    private fun enqueueJob(job: Job) {
        synchronized(jobs) {
            jobs.add(job)
        }
    }

    private fun pollJob(): Job? {
        return synchronized(jobs) {
            jobs.removeFirstOrNull()
        }
    }

    private suspend fun updateAutoProfilesOnStart() {
        ImportedDao().queryAllUUIDs()
            .mapNotNull { ImportedDao().queryByUUID(it) }
            .filter { it.type != Profile.Type.File }
            .filter { it.interval >= TimeUnit.MINUTES.toMillis(15) }
            .forEach { runDeduplicated(it.uuid, "startup") }
    }

    private suspend fun updateStaleProfiles() {
        val current = System.currentTimeMillis()

        ImportedDao().queryAllUUIDs()
            .mapNotNull { ImportedDao().queryByUUID(it) }
            .filter { it.type != Profile.Type.File }
            .filter { it.interval >= TimeUnit.MINUTES.toMillis(15) }
            .filter {
                if (failedUpdateProfiles.contains(it.uuid))
                    return@filter true

                val last = importedDir
                    .resolve(it.uuid.toString())
                    .resolve("config.yaml")
                    .lastModified()

                last > 0 && current - last >= it.interval
            }
            .forEach {
                Log.i("Update stale profile: ${it.name}")
                runDeduplicated(it.uuid, "stale")
            }
    }

    private suspend fun runDeduplicated(uuid: UUID, reason: String, forceReload: Boolean = false) {
        if (!pendingProfileUpdates.add(uuid)) {
            Log.i("Skip duplicate profile update: $uuid reason=$reason")
            return
        }

        try {
            run(uuid, forceReload)
        } finally {
            pendingProfileUpdates.remove(uuid)
        }
    }

    private suspend fun run(uuid: UUID, forceReload: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        try {
            processing(imported.name) {
                updateWithRetry(imported.uuid, forceReload)
            }

            completed(imported.uuid)
            failedUpdateProfiles.remove(imported.uuid)

            ImportedDao().queryByUUID(imported.uuid)?.let {
                ProfileReceiver.scheduleNext(this, it)
            }
        } catch (e: Exception) {
            failedUpdateProfiles.add(imported.uuid)

            ImportedDao().queryByUUID(imported.uuid)?.let {
                ProfileReceiver.scheduleRetry(this, it)
            }

            failed(imported.uuid, imported.name, e.message ?: "Unknown")
        }
    }

    private suspend fun updateWithRetry(uuid: UUID, forceReload: Boolean) {
        var last: Exception? = null

        repeat(MAX_UPDATE_ATTEMPTS) { attempt ->
            try {
                withTimeout(UPDATE_ATTEMPT_TIMEOUT) {
                    ProfileProcessor.update(this@ProfileWorker, uuid, null, forceReload)
                }
                return
            } catch (e: TimeoutCancellationException) {
                last = IllegalStateException(
                    "Profile update timed out after ${TimeUnit.MILLISECONDS.toMinutes(UPDATE_ATTEMPT_TIMEOUT)} minutes",
                    e
                )

                if (attempt != MAX_UPDATE_ATTEMPTS - 1) {
                    delay(RETRY_DELAYS[attempt])
                }
            } catch (e: Exception) {
                last = e

                if (attempt != MAX_UPDATE_ATTEMPTS - 1) {
                    delay(RETRY_DELAYS[attempt])
                }
            }
        }

        throw last ?: IllegalStateException("Profile update failed")
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_service_status)).build(),
                NotificationChannelCompat.Builder(
                    STATUS_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_process_status)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.profile_process_result)).build()
            )
        )
    }

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.profile_updater))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_profile_worker, notification)
    }

    private suspend inline fun processing(name: String, block: () -> Unit) {
        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setContentTitle(getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(id, notification)
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                NotificationManagerCompat.from(applicationContext)
                    .cancel(id)
            }
        }
    }

    private fun resultBuilder(id: Int, uuid: UUID): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            this,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
    }

    private fun completed(uuid: UUID) {
        sendProfileUpdateCompleted(uuid)
    }

    private fun failed(uuid: UUID, name: String, reason: String) {
        val id = UndefinedIds.next()

        val content = getString(R.string.format_update_failure, name, reason)

        val notification = resultBuilder(id, uuid)
            .setContentTitle(getString(R.string.update_failure))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()

        NotificationManagerCompat.from(this)
            .notify(id, notification)

        sendProfileUpdateFailed(uuid, reason)
    }

    companion object {
        private const val SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
        private const val RESULT_CHANNEL = "profile_result_channel"
        private const val MAX_UPDATE_ATTEMPTS = 3
        private val UPDATE_ATTEMPT_TIMEOUT = TimeUnit.MINUTES.toMillis(4)
        private val STALE_UPDATE_REQUEST_INTERVAL = TimeUnit.MINUTES.toMillis(1)
        @Volatile
        private var lastStaleUpdateRequest = 0L
        private val pendingProfileUpdates = Collections.synchronizedSet(mutableSetOf<UUID>())
        private val failedUpdateProfiles = Collections.synchronizedSet(mutableSetOf<UUID>())
        private val RETRY_DELAYS = longArrayOf(
            TimeUnit.SECONDS.toMillis(10),
            TimeUnit.SECONDS.toMillis(30),
        )

        fun requestUpdateStale(context: android.content.Context) {
            val current = System.currentTimeMillis()
            if (current - lastStaleUpdateRequest < STALE_UPDATE_REQUEST_INTERVAL)
                return

            lastStaleUpdateRequest = current

            val service = Intent(Intents.ACTION_PROFILE_UPDATE_STALE)
                .setComponent(ProfileWorker::class.componentName)

            runCatching {
                context.startForegroundServiceCompat(service)
            }.onFailure {
                Log.w("Request stale profile update failed: ${it.message}", it)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
