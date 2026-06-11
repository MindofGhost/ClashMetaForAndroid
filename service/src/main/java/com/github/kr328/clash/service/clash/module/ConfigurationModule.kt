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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)
    private sealed class ReloadEvent {
        data object Reload : ReloadEvent()
        data object HealthCheckFinished : ReloadEvent()
        data class ProfileChanged(val uuid: UUID) : ReloadEvent()
    }

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null
        val logcat = Clash.subscribeLogcat()
        val healthCheckFinished = Channel<Unit>(Channel.CONFLATED)

        reload.trySend(Unit)

        try {
            coroutineScope scope@{
                val logcatJob = launch {
                    while (isActive) {
                        val message = logcat.receive()
                        if (message.message.startsWith("Finish A Health Checking")) {
                            healthCheckFinished.trySend(Unit)
                        }
                    }
                }

                while (true) {
                    val event = select<ReloadEvent> {
                        broadcasts.onReceive {
                            if (it.action == Intents.ACTION_PROFILE_CHANGED)
                                ReloadEvent.ProfileChanged(UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID)))
                            else
                                ReloadEvent.Reload
                        }
                        reload.onReceive {
                            ReloadEvent.Reload
                        }
                        healthCheckFinished.onReceive {
                            ReloadEvent.HealthCheckFinished
                        }
                    }

                    try {
                        if (event is ReloadEvent.HealthCheckFinished) {
                            persistCurrentSelections(loaded)
                            continue
                        }

                        val current = store.activeProfile
                            ?: throw NullPointerException("No profile selected")

                        if (current == loaded && event is ReloadEvent.ProfileChanged && event.uuid != loaded)
                            continue

                        val active = ImportedDao().queryByUUID(current)
                            ?: throw NullPointerException("No profile selected")

                        Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                        Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                        loaded = current

                        restoreSelections(active.uuid)

                        StatusProvider.currentProfile = active.name

                        service.sendProfileLoaded(current)

                        Log.d("Profile ${active.name} loaded")
                    } catch (e: Exception) {
                        logcatJob.cancel()
                        enqueueEvent(LoadException(e.message ?: "Unknown"))
                        return@scope
                    }
                }
            }
        } finally {
            persistCurrentSelections(loaded)
            logcat.cancel()
            healthCheckFinished.cancel()
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

    private suspend fun restoreSelections(uuid: UUID) {
        val dao = SelectionDao()
        val selections = dao.querySelections(uuid)
        val remove = mutableListOf<String>()

        selections.forEach { selection ->
            if (!Clash.patchSelector(selection.proxy, selection.selected)) {
                remove += selection.proxy
            }
        }

        if (remove.isNotEmpty()) {
            dao.removeSelections(uuid, remove.distinct())
        }
    }

}
