package com.github.kr328.clash

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.ProfileWorker
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.util.clearAppUpdateCacheIfPackageChanged
import com.github.kr328.clash.service.util.restoreDownloadedAppUpdateNotification
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.util.startClashService
import java.io.File
import java.io.FileOutputStream

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()

        Log.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
            clearAppUpdateCacheIfPackageChanged()
            restoreDownloadedAppUpdateNotification()
            registerScreenRestartReceiver()
            updateProfilesOnStart()
        } else {
            sendServiceRecreated()
        }
    }

    private fun updateProfilesOnStart() {
        val service = Intent(Intents.ACTION_PROFILE_UPDATE_ON_START)
            .setComponent(ProfileWorker::class.componentName)

        startForegroundServiceCompat(service)
    }

    private fun registerScreenRestartReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_ON)
                    return

                ProfileWorker.requestUpdateStale(context)

                if (!StatusProvider.shouldStartClashOnBoot)
                    return

                runCatching {
                    context.startClashService()
                }.onFailure {
                    Log.w("Restart clash from screen on failed: ${it.message}", it)
                }
            }
        }

        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val geoipFile = File(clashDir, "geoip.metadb")
        if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
            geoipFile.delete()
        }
        if (!geoipFile.exists()) {
            FileOutputStream(geoipFile).use {
                assets.open("geoip.metadb").copyTo(it)
            }
        }

        val geositeFile = File(clashDir, "geosite.dat")
        if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
            geositeFile.delete()
        }
        if (!geositeFile.exists()) {
            FileOutputStream(geositeFile).use {
                assets.open("geosite.dat").copyTo(it)
            }
        }

        val asnFile = File(clashDir, "ASN.mmdb")
        if (asnFile.exists() && asnFile.lastModified() < updateDate) {
            asnFile.delete()
        }
        if (!asnFile.exists()) {
            FileOutputStream(asnFile).use {
                assets.open("ASN.mmdb").copyTo(it)
            }
        }

        val bundleMRSFile = File(clashDir, "BundleMRS.7z")
        if (bundleMRSFile.exists() && bundleMRSFile.lastModified() < updateDate) {
            bundleMRSFile.delete()
        }
        if (!bundleMRSFile.exists()) {
            FileOutputStream(bundleMRSFile).use {
                assets.open("BundleMRS.7z").copyTo(it)
            }
        }
    }

    fun finalize() {
        Global.destroy()
    }
}
