package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.SubscriptionHeaders
import com.github.kr328.clash.service.util.fetchSubscriptionHeaders
import com.github.kr328.clash.service.util.handleAppUpdateHeaders
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending = PendingDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")
                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()
                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)
                    pending
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })
                val subscriptionInfo = withTimeout(FETCH_AND_VALIDATE_TIMEOUT) {
                    fetchProfile(context, snapshot.source, snapshot.type != Profile.Type.File, callback)
                }
                val headers = fetchExtendedHeaders(context, snapshot.source)

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) != snapshot)
                        return@withLock

                    val importedDir = context.importedDir.resolve(snapshot.uuid.toString())
                    importedDir.deleteRecursively()
                    context.processingDir.copyRecursively(importedDir)

                    val old = ImportedDao().queryByUUID(snapshot.uuid)
                    val updateInterval = if (old == null && snapshot.interval == 0L) {
                        headers?.profileUpdateIntervalHours?.toIntervalMillis()
                            ?: subscriptionInfo?.subUpdateInterval
                            ?: snapshot.interval
                    } else {
                        snapshot.interval
                    }
                    val new = Imported(
                        uuid = snapshot.uuid,
                        name = snapshot.name,
                        type = snapshot.type,
                        source = snapshot.source,
                        interval = updateInterval,
                        upload = headers?.takeIf { it.hasUserInfo }?.upload
                            ?: subscriptionInfo?.subUpload ?: old?.upload ?: 0,
                        download = headers?.takeIf { it.hasUserInfo }?.download
                            ?: subscriptionInfo?.subDownload ?: old?.download ?: 0,
                        total = headers?.takeIf { it.hasUserInfo }?.total
                            ?: subscriptionInfo?.subTotal ?: old?.total ?: 0,
                        expire = headers?.takeIf { it.hasUserInfo }?.expire
                            ?: subscriptionInfo?.subExpire ?: old?.expire ?: 0,
                        createdAt = old?.createdAt ?: System.currentTimeMillis(),
                        ageSecretKey = snapshot.ageSecretKey,
                        subInfoColor = if (headers != null) headers.subInfoColor else old?.subInfoColor,
                        subInfoText = if (headers != null) headers.subInfoText else old?.subInfoText,
                        subInfoButtonText = if (headers != null) headers.subInfoButtonText else old?.subInfoButtonText,
                        subInfoButtonLink = if (headers != null) headers.subInfoButtonLink else old?.subInfoButtonLink,
                        subExpire = headers?.subExpire ?: old?.subExpire ?: false,
                        subExpireButtonLink = if (headers != null) headers.subExpireButtonLink else old?.subExpireButtonLink,
                    )
                    if (old == null) ImportedDao().insert(new) else ImportedDao().update(new)

                    PendingDao().remove(snapshot.uuid)
                    context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                    context.sendProfileChanged(snapshot.uuid)
                }
            }
        }
    }

    suspend fun update(
        context: Context,
        uuid: UUID,
        callback: IFetchObserver?,
        forceReload: Boolean = false,
    ) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()
                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)
                    imported
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })
                val subscriptionInfo = withTimeout(FETCH_AND_VALIDATE_TIMEOUT) {
                    fetchProfile(context, snapshot.source, true, callback)
                }
                val headers = fetchExtendedHeaders(context, snapshot.source)

                profileLock.withLock {
                    if (!ImportedDao().exists(snapshot.uuid))
                        return@withLock

                    val updated = snapshot.copy(
                        upload = headers?.takeIf { it.hasUserInfo }?.upload
                            ?: subscriptionInfo?.subUpload ?: snapshot.upload,
                        download = headers?.takeIf { it.hasUserInfo }?.download
                            ?: subscriptionInfo?.subDownload ?: snapshot.download,
                        total = headers?.takeIf { it.hasUserInfo }?.total
                            ?: subscriptionInfo?.subTotal ?: snapshot.total,
                        expire = headers?.takeIf { it.hasUserInfo }?.expire
                            ?: subscriptionInfo?.subExpire ?: snapshot.expire,
                        subInfoColor = if (headers != null) headers.subInfoColor else snapshot.subInfoColor,
                        subInfoText = if (headers != null) headers.subInfoText else snapshot.subInfoText,
                        subInfoButtonText = if (headers != null) headers.subInfoButtonText else snapshot.subInfoButtonText,
                        subInfoButtonLink = if (headers != null) headers.subInfoButtonLink else snapshot.subInfoButtonLink,
                        subExpire = headers?.subExpire ?: snapshot.subExpire,
                        subExpireButtonLink = if (headers != null) headers.subExpireButtonLink else snapshot.subExpireButtonLink,
                    )

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

    private suspend fun fetchProfile(
        context: Context,
        source: String,
        force: Boolean,
        callback: IFetchObserver?,
    ): FetchStatus? {
        var subscriptionInfo: FetchStatus? = null
        var observer = callback

        Clash.fetchAndValid(context.processingDir, source, force) { status ->
            if (status.action == FetchStatus.Action.SubscriptionInfo) {
                subscriptionInfo = status
                return@fetchAndValid
            }
            try {
                observer?.updateStatus(status)
            } catch (e: Exception) {
                observer = null
                Log.w("Report fetch status: $e", e)
            }
        }.await()

        return subscriptionInfo
    }

    private suspend fun fetchExtendedHeaders(context: Context, source: String): SubscriptionHeaders? {
        if (!source.startsWith("https://", ignoreCase = true))
            return null

        return try {
            withTimeout(SUBSCRIPTION_HEADERS_TIMEOUT) {
                context.fetchSubscriptionHeaders(source)
            }?.also { context.handleAppUpdateHeaders(source, it) }
        } catch (e: Exception) {
            Log.w("Report fetch subscription headers: $e", e)
            null
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)
                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
                context.importedDir.resolve(uuid.toString()).deleteRecursively()
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
                    ServiceStore(context).activeProfile = uuid
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
            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" ->
                throw IllegalArgumentException("Unsupported url $source")
            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 ->
                throw IllegalArgumentException("Invalid interval")
        }
    }

    private fun Long.toIntervalMillis(): Long {
        return if (this > 0) {
            TimeUnit.HOURS.toMillis(this).coerceAtLeast(TimeUnit.MINUTES.toMillis(15))
        } else {
            0L
        }
    }

    private fun hasSameProfileContent(current: File, updated: File): Boolean {
        val currentDigest = current.contentDigest() ?: return false
        val updatedDigest = updated.contentDigest() ?: return false
        return currentDigest.contentEquals(updatedDigest)
    }

    private fun markProfileChecked(profileDir: File) {
        profileDir.resolve("config.yaml").takeIf(File::exists)
            ?.setLastModified(System.currentTimeMillis())
    }

    private fun File.contentDigest(): ByteArray? {
        if (!exists()) return null

        val digest = MessageDigest.getInstance("SHA-256")
        val root = if (isDirectory) this else parentFile ?: return null
        val files = if (isDirectory) {
            walkTopDown().filter(File::isFile)
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
                    if (read < 0) break
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
