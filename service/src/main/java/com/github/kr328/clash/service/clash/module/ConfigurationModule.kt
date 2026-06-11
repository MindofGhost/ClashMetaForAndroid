package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        try {
            while (true) {
                val changed: UUID? = select {
                    broadcasts.onReceive {
                        if (it.action == Intents.ACTION_PROFILE_CHANGED)
                            UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                        else
                            null
                    }
                    reload.onReceive {
                        null
                    }
                }

                try {
                    val current = store.activeProfile
                        ?: throw NullPointerException("No profile selected")

                    if (current == loaded && changed != null && changed != loaded)
                        continue

                    val active = ImportedDao().queryByUUID(current)
                        ?: throw NullPointerException("No profile selected")

                    Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                    Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                    loaded = current

                    val remove = SelectionDao().querySelections(active.uuid)
                        .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                        .map { it.proxy }

                    SelectionDao().removeSelections(active.uuid, remove)

                    StatusProvider.currentProfile = active.name

                    service.sendProfileLoaded(current)

                    Log.d("Profile ${active.name} loaded")
                } catch (e: Exception) {
                    return enqueueEvent(LoadException(e.message ?: "Unknown"))
                }
            }
        } finally {
            persistCurrentSelections(loaded)
        }
    }

    private fun persistCurrentSelections(uuid: UUID?) {
        if (uuid == null)
            return

        runCatching {
            val dao = SelectionDao()
            val selectableTypes = setOf(
                Proxy.Type.Selector,
                Proxy.Type.Fallback,
                Proxy.Type.URLTest,
                Proxy.Type.LoadBalance,
            )

            Clash.queryGroupNames(false)
                .mapNotNull { name ->
                    val group = Clash.queryGroup(name, ProxySort.Default)

                    if (group.type in selectableTypes && group.now.isNotBlank()) {
                        Selection(uuid, name, group.now)
                    } else {
                        null
                    }
                }
                .forEach(dao::setSelected)
        }.onFailure {
            Log.w("Persist current proxy selections: ${it.message}", it)
        }
    }
}
