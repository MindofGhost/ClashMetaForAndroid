package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.fetchSubscriptionHeaders
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending =
                        PendingDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val force = snapshot.type != Profile.Type.File
                var cb = callback

                withTimeout(FETCH_AND_VALIDATE_TIMEOUT) {
                    Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                        try {
                            cb?.updateStatus(it)
                        } catch (e: Exception) {
                            cb = null

                            Log.w("Report fetch status: $e", e)
                        }
                    }.await()
                }

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        var upload: Long = old?.upload ?: 0
                        var download: Long = old?.download ?: 0
                        var total: Long = old?.total ?: 0
                        var expire: Long = old?.expire ?: 0
                        var subInfoColor: String? = old?.subInfoColor
                        var subInfoText: String? = old?.subInfoText
                        var subInfoButtonText: String? = old?.subInfoButtonText
                        var subInfoButtonLink: String? = old?.subInfoButtonLink
                        var subExpire = old?.subExpire ?: false
                        var subExpireButtonLink: String? = old?.subExpireButtonLink
                        var updateInterval: Long = snapshot.interval
                        if (snapshot?.type == Profile.Type.Url) {
                            if (snapshot.source.startsWith("https://", true)) {
                                try {
                                    val headers = withTimeout(SUBSCRIPTION_HEADERS_TIMEOUT) {
                                        context.fetchSubscriptionHeaders(snapshot.source)
                                    }
                                    if (headers != null) {
                                        upload = if (headers.hasUserInfo) headers.upload else old?.upload ?: 0
                                        download = if (headers.hasUserInfo) headers.download else old?.download ?: 0
                                        total = if (headers.hasUserInfo) headers.total else old?.total ?: 0
                                        expire = if (headers.hasUserInfo) headers.expire else old?.expire ?: 0
                                        subInfoColor = headers.subInfoColor
                                        subInfoText = headers.subInfoText
                                        subInfoButtonText = headers.subInfoButtonText
                                        subInfoButtonLink = headers.subInfoButtonLink
                                        subExpire = headers.subExpire
                                        subExpireButtonLink = headers.subExpireButtonLink
                                        if (old == null && snapshot.interval == 0L) {
                                            headers.profileUpdateIntervalHours?.let { intervalHours ->
                                                updateInterval = if (intervalHours > 0) {
                                                    TimeUnit.HOURS.toMillis(intervalHours)
                                                        .coerceAtLeast(TimeUnit.MINUTES.toMillis(15))
                                                } else {
                                                    0L
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("Report fetch subscription-userinfo status: $e", e)
                                }
                            }
                        }
                        val new = Imported(
                            snapshot.uuid,
                            snapshot.name,
                            snapshot.type,
                            snapshot.source,
                            updateInterval,
                            upload,
                            download,
                            total,
                            expire,
                            old?.createdAt ?: System.currentTimeMillis(),
                            ageSecretKey = snapshot.ageSecretKey,
                            subInfoColor = subInfoColor,
                            subInfoText = subInfoText,
                            subInfoButtonText = subInfoButtonText,
                            subInfoButtonLink = subInfoButtonLink,
                            subExpire = subExpire,
                            subExpireButtonLink = subExpireButtonLink,
                        )
                        if (old != null) {
                            ImportedDao().update(new)
                        } else {
                            ImportedDao().insert(new)
                        }

                        PendingDao().remove(snapshot.uuid)

                        context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun update(
        context: Context,
        uuid: UUID,
        callback: IFetchObserver?,
        forceReload: Boolean = false
    ) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                var cb = callback

                withTimeout(FETCH_AND_VALIDATE_TIMEOUT) {
                    Clash.fetchAndValid(context.processingDir, snapshot.source, true) {
                        try {
                            cb?.updateStatus(it)
                        } catch (e: Exception) {
                            cb = null

                            Log.w("Report fetch status: $e", e)
                        }
                    }.await()
                }

                profileLock.withLock {
                    if (ImportedDao().exists(snapshot.uuid)) {
                        val headers = try {
                            withTimeout(SUBSCRIPTION_HEADERS_TIMEOUT) {
                                context.fetchSubscriptionHeaders(snapshot.source)
                            }
                        } catch (e: Exception) {
                            Log.w("Report fetch subscription-userinfo status: $e", e)
                            null
                        }
                        val updated = if (headers != null) {
                            snapshot.copy(
                                upload = if (headers.hasUserInfo) headers.upload else snapshot.upload,
                                download = if (headers.hasUserInfo) headers.download else snapshot.download,
                                total = if (headers.hasUserInfo) headers.total else snapshot.total,
                                expire = if (headers.hasUserInfo) headers.expire else snapshot.expire,
                                subInfoColor = headers.subInfoColor,
                                subInfoText = headers.subInfoText,
                                subInfoButtonText = headers.subInfoButtonText,
                                subInfoButtonLink = headers.subInfoButtonLink,
                                subExpire = headers.subExpire,
                                subExpireButtonLink = headers.subExpireButtonLink,
                            )
                        } else {
                            snapshot
                        }

                        val importedDir = context.importedDir.resolve(snapshot.uuid.toString())
                        if (!forceReload && hasSameProfileContent(importedDir, context.processingDir)) {
                            ImportedDao().update(updated)
                            markProfileChecked(importedDir)
                            Log.i("Profile ${snapshot.name} content unchanged, skip reload")
                        } else {
                            importedDir.deleteRecursively()
                            context.processingDir.copyRecursively(importedDir)
                            ImportedDao().update(updated)
                            context.sendProfileChanged(snapshot.uuid)
                        }
                    }
                }
            }
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" -> throw IllegalArgumentException(
                "Unsupported url $source"
            )

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

    private fun hasSameProfileContent(current: File, updated: File): Boolean {
        val currentDigest = current.contentDigest() ?: return false
        val updatedDigest = updated.contentDigest() ?: return false

        return currentDigest.contentEquals(updatedDigest)
    }

    private fun markProfileChecked(profileDir: File) {
        val config = profileDir.resolve("config.yaml")

        if (config.exists()) {
            config.setLastModified(System.currentTimeMillis())
        }
    }

    private fun File.contentDigest(): ByteArray? {
        if (!exists())
            return null

        val digest = MessageDigest.getInstance("SHA-256")
        val root = if (isDirectory) this else parentFile ?: return null
        val files = if (isDirectory) {
            walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        } else {
            listOf(this)
        }
        val buffer = ByteArray(8192)

        files.forEach { file ->
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())

            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0)
                        break

                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }

        return digest.digest()
    }

    private val FETCH_AND_VALIDATE_TIMEOUT = TimeUnit.MINUTES.toMillis(3)
    private val SUBSCRIPTION_HEADERS_TIMEOUT = TimeUnit.SECONDS.toMillis(30)

}
