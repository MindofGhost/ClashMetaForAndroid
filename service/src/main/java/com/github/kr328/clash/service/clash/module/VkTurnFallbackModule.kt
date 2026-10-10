package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.BypassConfig
import com.github.kr328.clash.core.model.CoreHealthCheck
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.VkTurnEvent
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.AppLogWriter
import com.github.kr328.clash.service.util.checkBypassConditions
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class VkTurnFallbackModule(service: Service) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private val connectivity = service.getSystemService<ConnectivityManager>()
    private val notificationManager = NotificationManagerCompat.from(service)
    private val fallbackCheckMutex = Mutex()
    private var moduleScope: CoroutineScope? = null
    private var configs = emptyList<BypassConfig>()
    private val instances = linkedMapOf<String, TurnRuntime>()
    private var winner: String? = null
    private var startConfirmed = false
    private var failedChecks = 0
    private var recoveredChecks = 0
    private var lastAvailableEndpointCount: Int? = null
    private var lastPhysicalNetworkSignature: String? = null
    private var recoverySince: Long? = null
    private var recoveryStop: Job? = null
    private val completedChecks = linkedSetOf<String>()
    private val roundEndpoints = linkedMapOf<String, MutableSet<String>>()
    private val missingEndpoints = mutableSetOf<String>()
    private var openedCaptchaUrl: String? = null
    private var captchaEndpoint: String? = null

    private class TurnRuntime(val config: BypassConfig, val args: List<String>) {
        var pendingStart: Job? = null
        var waitingForHealthCheck = false
        var expected = false
        var token = ""
        var startedAt = 0L
        var endpointAlive = false
        var reconnectRound: String? = null
    }

    private data class HealthSnapshot(
        val proxies: Map<String, Proxy>,
        val ordinary: Set<String>,
        val availableOrdinary: Int,
        val selected: Set<String>,
    )

    override suspend fun run() = coroutineScope {
        if (!store.vkTurnFallback) {
            logInfo("VK TURN fallback disabled")
            return@coroutineScope
        }
        moduleScope = this
        createCaptchaNotificationChannel()
        val healthChecks = Clash.subscribeHealthCheckEvents()
        val events = Clash.subscribeVkTurnEvents()
        val profileLoaded = receiveBroadcast { addAction(Intents.ACTION_PROFILE_LOADED) }
        val screenOn = receiveBroadcast(false) { addAction(Intent.ACTION_SCREEN_ON) }
        val captchaSubmitted = receiveBroadcast(false) { addAction(CAPTCHA_SUBMITTED_ACTION) }

        try {
            fallbackCheckMutex.withLock { refreshConfiguration() }
            launch {
                for (ignored in profileLoaded) {
                    fallbackCheckMutex.withLock {
                        refreshConfiguration()
                        resetHealthState()
                    }
                }
            }
            launch {
                for (ignored in captchaSubmitted) {
                    fallbackCheckMutex.withLock { clearCaptcha() }
                }
            }
            launch {
                for (ignored in screenOn) {
                    withContext(Dispatchers.IO) { Clash.wakeVkTurn() }
                    fallbackCheckMutex.withLock { restartStoppedInstances() }
                }
            }
            launch {
                while (isActive) {
                    delay(RUNNING_WATCHDOG_INTERVAL)
                    fallbackCheckMutex.withLock { restartStoppedInstances() }
                }
            }
            launch {
                for (event in events) {
                    fallbackCheckMutex.withLock { handleTurnEvent(event) }
                }
            }
            for (check in healthChecks) {
                fallbackCheckMutex.withLock { handleHealthCheck(check) }
            }
        } finally {
            healthChecks.cancel()
            events.cancel()
            withContext(NonCancellable) {
                fallbackCheckMutex.withLock {
                    stopAll("service stopped")
                    withContext(Dispatchers.IO) { Clash.stopVkTurn() }
                    moduleScope = null
                }
            }
        }
    }

    private suspend fun refreshConfiguration() {
        val loaded = withContext(Dispatchers.IO) {
            val profile = store.activeProfile ?: return@withContext emptyList()
            val path = service.importedDir.resolve(profile.toString()).resolve("config.yaml")
            if (!path.isFile) return@withContext emptyList()
            runCatching { Clash.readBypassConfig(path.absolutePath) }.onFailure {
                logWarning("Cannot read bypass configuration", it)
            }.getOrNull().orEmpty()
        }
        if (loaded == configs) return
        stopAll("bypass configuration changed")
        configs = loaded
        instances.clear()
        missingEndpoints.clear()
        for (config in configs) {
            if (config.type != "turn") {
                logInfo("Bypass type '${config.type}' is not supported: ${config.endpoint}")
                continue
            }
            runCatching { parseCommandLine(config.config) }.onSuccess {
                if (it.isNotEmpty()) instances[config.endpoint] = TurnRuntime(config, it)
            }.onFailure {
                logWarning("Invalid TURN arguments for ${config.endpoint}", it)
            }
        }
        resetHealthState()
        logInfo("VK TURN fallback configured endpoints=${instances.keys.joinToString()}")
    }

    private suspend fun readHealthSnapshot(): HealthSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val groups = Clash.queryGroupNames(false).associateWith {
                Clash.queryGroup(it, ProxySort.Default)
            }
            if (groups.isEmpty()) return@withContext null
            val proxies = groups.values.flatMap { it.proxies }.associateBy { it.name }
            for (endpoint in instances.keys) {
                if (proxies[endpoint]?.let(::isEndpoint) != true && missingEndpoints.add(endpoint))
                    logWarning("Bypass endpoint '$endpoint' is not an existing leaf proxy; startup skipped")
            }
            val excluded = configs.map { it.endpoint }.toSet()
            val ordinary = proxies.values.filter(::isEndpoint).map { it.name }.toSet() - excluded
            val children = groups.values.flatMap { group ->
                group.proxies.filter { it.isGroup }.map { it.name }
            }.toSet()
            val roots = (groups.keys - children).ifEmpty { groups.keys }
            fun selectedLeaf(name: String, visited: MutableSet<String>): String? {
                if (!visited.add(name)) return null
                val group = groups[name] ?: return name
                return selectedLeaf(group.now, visited)
            }
            HealthSnapshot(
                proxies, ordinary,
                ordinary.count { name -> proxies[name]?.let(::isAvailableEndpoint) == true },
                roots.mapNotNull { selectedLeaf(it, mutableSetOf()) }.toSet(),
            )
        }.onFailure {
            logWarning("VK TURN cannot read core health results", it)
        }.getOrNull()
    }

    private suspend fun nativeStates(): Map<String, String> = withContext(Dispatchers.IO) {
        Clash.queryVkTurnStates()
    }

    private suspend fun handleHealthCheck(check: CoreHealthCheck) {
        val signature = physicalNetworkSignature()
        if (signature != lastPhysicalNetworkSignature) {
            resetHealthState()
            instances.values.forEach { it.endpointAlive = false }
            lastPhysicalNetworkSignature = signature
        }
        if (signature == null || check.round in completedChecks) return
        if (!check.finished) {
            val endpoints = roundEndpoints.getOrPut(check.round) { mutableSetOf() }
            if (!endpoints.add(check.endpoint)) return
            if (roundEndpoints.size > MAX_COMPLETED_CHECKS)
                roundEndpoints.remove(roundEndpoints.keys.first())
            val runtime = instances[check.endpoint] ?: return
            if (!runtime.expected || check.time < runtime.startedAt) return
            runtime.endpointAlive = check.alive
            if (!check.alive) {
                if (winner == check.endpoint) winner = null
                if (runtime.reconnectRound != check.round) {
                    // The core accepts reconnect only after its first-stream warm-up barrier ends.
                    val accepted = withContext(Dispatchers.IO) { Clash.reconnectVkTurn(check.endpoint) }
                    if (accepted) {
                        runtime.reconnectRound = check.round
                        logInfo("VK TURN endpoint ${check.endpoint} health check failed; reconnect requested")
                    }
                }
            }
            val snapshot = readHealthSnapshot() ?: return
            reconcileWinner(snapshot)
            if (!check.alive && lastAvailableEndpointCount == 0) scheduleStarts(signature)
            return
        }
        val checkedEndpoints = roundEndpoints.remove(check.round).orEmpty()
        completedChecks.add(check.round)
        if (completedChecks.size > MAX_COMPLETED_CHECKS) completedChecks.remove(completedChecks.first())
        val snapshot = readHealthSnapshot() ?: return
        reconcileWinner(snapshot)
        // A round checking only a helper cannot confirm failure/recovery of ordinary proxies.
        if (snapshot.ordinary.isNotEmpty() && checkedEndpoints.none { it in snapshot.ordinary }) return
        instances.values.forEach { it.waitingForHealthCheck = false }
        val available = snapshot.availableOrdinary
        lastAvailableEndpointCount = available
        logInfo("VK TURN core health check: availableOrdinaryEndpoints=$available")
        if (available == 0) {
            cancelRecoveryStop()
            recoveredChecks = 0
            failedChecks = (failedChecks + 1).coerceAtMost(REQUIRED_CHECKS)
            if (failedChecks >= REQUIRED_CHECKS && !startConfirmed) {
                startConfirmed = true
                logInfo("VK TURN fallback confirmed by the second failed ordinary check")
            }
            scheduleStarts(signature)
        } else {
            instances.values.forEach { cancelPendingStart(it) }
            failedChecks = 0
            if (!startConfirmed) {
                stopAll("ordinary endpoints recovered before startup was confirmed")
                return
            }
            recoveredChecks = (recoveredChecks + 1).coerceAtMost(REQUIRED_CHECKS)
            scheduleRecoveryStop(signature)
        }
    }

    private suspend fun reconcileWinner(snapshot: HealthSnapshot) {
        val states = nativeStates()
        val eligible = instances.values.filter {
            it.expected && it.endpointAlive && states[it.config.endpoint] in RUNNING_STATES &&
                it.config.endpoint in snapshot.selected &&
                snapshot.proxies[it.config.endpoint]?.let(::isAvailableEndpoint) == true
        }
        val selected = eligible.firstOrNull { it.config.endpoint == winner } ?: eligible.firstOrNull()
        winner = selected?.config?.endpoint
        if (selected == null) return
        instances.values.filter { it !== selected }.forEach { cancelPendingStart(it) }
        withContext(Dispatchers.IO) { Clash.cancelVkTurnExcept(selected.config.endpoint) }
        for (runtime in instances.values) {
            if (runtime !== selected) stopInstance(runtime, "Clash selected ${selected.config.endpoint}")
        }
    }

    private fun scheduleStarts(signature: String) {
        if (winner != null) return
        for (runtime in instances.values) {
            if (runtime.expected || runtime.waitingForHealthCheck || runtime.pendingStart?.isActive == true) continue
            val scope = moduleScope ?: return
            runtime.pendingStart = scope.launch {
                val job = coroutineContext[Job]
                try {
                    val conditionsMet = service.checkBypassConditions(runtime.config.check) { domain, result, expected ->
                        logInfo("VK TURN startup check endpoint=${runtime.config.endpoint} domain=$domain result=$result expected=$expected")
                    }
                    if (!conditionsMet) {
                        fallbackCheckMutex.withLock {
                            if (instances[runtime.config.endpoint] === runtime && runtime.pendingStart === job) {
                                runtime.waitingForHealthCheck = true
                                // Release this attempt before another health round can authorize a retry.
                                runtime.pendingStart = null
                                logInfo("VK TURN startup conditions not met: ${runtime.config.endpoint}; waiting for the next ordinary health check")
                            }
                        }
                        return@launch
                    }
                    fallbackCheckMutex.withLock {
                        if (instances[runtime.config.endpoint] !== runtime || winner != null ||
                            lastAvailableEndpointCount != 0 || physicalNetworkSignature() != signature)
                            return@withLock
                        val snapshot = readHealthSnapshot() ?: return@withLock
                        if (snapshot.availableOrdinary != 0 || physicalNetworkSignature() != signature ||
                            snapshot.proxies[runtime.config.endpoint]?.let(::isEndpoint) != true)
                            return@withLock
                        reconcileWinner(snapshot)
                        if (winner != null) return@withLock
                        runtime.token = UUID.randomUUID().toString()
                        runtime.startedAt = System.currentTimeMillis()
                        runtime.endpointAlive = false
                        runCatching {
                            withContext(Dispatchers.IO) {
                                Clash.startVkTurn(runtime.config.endpoint, runtime.args, runtime.token)
                            }
                        }.onSuccess {
                            runtime.expected = true
                            logInfo("VK TURN started endpoint=${runtime.config.endpoint}")
                        }.onFailure {
                            if (it is CancellationException) throw it
                            logWarning("VK TURN start failed endpoint=${runtime.config.endpoint}", it)
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        fallbackCheckMutex.withLock {
                            if (runtime.pendingStart === job) runtime.pendingStart = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun restartStoppedInstances() {
        if (lastAvailableEndpointCount != 0 || physicalNetworkSignature() != lastPhysicalNetworkSignature)
            return
        val signature = lastPhysicalNetworkSignature ?: return
        val states = nativeStates()
        for (runtime in instances.values) {
            if (runtime.expected && states[runtime.config.endpoint] !in RUNNING_STATES) {
                runtime.expected = false
                runtime.endpointAlive = false
                if (winner == runtime.config.endpoint) winner = null
                logWarning("VK TURN stopped unexpectedly: ${runtime.config.endpoint}")
            }
        }
        scheduleStarts(signature)
    }

    private fun cancelPendingStart(runtime: TurnRuntime) {
        runtime.pendingStart?.cancel()
        runtime.pendingStart = null
    }

    private suspend fun stopInstance(runtime: TurnRuntime, reason: String) {
        cancelPendingStart(runtime)
        if (!runtime.expected) return
        runCatching {
            withContext(Dispatchers.IO) { Clash.stopVkTurn(runtime.config.endpoint) }
        }.onSuccess {
            runtime.expected = false
            runtime.endpointAlive = false
            runtime.token = ""
            if (captchaEndpoint == runtime.config.endpoint) clearCaptcha()
            logInfo("VK TURN stopped endpoint=${runtime.config.endpoint}: $reason")
        }.onFailure {
            logWarning("VK TURN stop failed endpoint=${runtime.config.endpoint}", it)
        }
    }

    private suspend fun stopAll(reason: String) {
        instances.values.forEach { cancelPendingStart(it) }
        withContext(Dispatchers.IO) { Clash.cancelVkTurnExcept("") }
        for (runtime in instances.values) stopInstance(runtime, reason)
        winner = null
        startConfirmed = false
        failedChecks = 0
        recoveredChecks = 0
        lastAvailableEndpointCount = null
        cancelRecoveryStop()
        clearCaptcha()
    }

    private fun resetHealthState() {
        instances.values.forEach { cancelPendingStart(it) }
        cancelRecoveryStop()
        failedChecks = 0
        recoveredChecks = 0
        if (instances.values.none { it.expected }) startConfirmed = false
        lastAvailableEndpointCount = null
        completedChecks.clear()
        roundEndpoints.clear()
    }

    private fun cancelRecoveryStop() {
        recoveryStop?.cancel()
        recoveryStop = null
        recoverySince = null
    }

    private fun scheduleRecoveryStop(signature: String) {
        if (!startConfirmed || recoveryStop?.isActive == true) return
        val scope = moduleScope ?: return
        val since = recoverySince ?: SystemClock.elapsedRealtime().also { recoverySince = it }
        recoveryStop = scope.launch {
            delay((RECOVERY_STOP_DELAY - (SystemClock.elapsedRealtime() - since)).coerceAtLeast(0L))
            fallbackCheckMutex.withLock {
                recoveryStop = null
                if (!startConfirmed || recoveredChecks < REQUIRED_CHECKS ||
                    physicalNetworkSignature() != signature) return@withLock
                val snapshot = readHealthSnapshot() ?: return@withLock
                if (physicalNetworkSignature() != signature) return@withLock
                if (snapshot.availableOrdinary == 0) {
                    recoveredChecks = 0
                    cancelRecoveryStop()
                    return@withLock
                }
                stopAll("ordinary endpoints recovered for ${RECOVERY_STOP_DELAY / 1000}s")
                withContext(Dispatchers.IO) { Clash.closeAllConnections() }
            }
        }
    }

    private fun physicalNetworkSignature(): String? {
        val networks = connectivity?.allNetworks.orEmpty().mapNotNull { network ->
            val capabilities = connectivity?.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            network.toString()
        }.sorted()
        return networks.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun isEndpoint(proxy: Proxy): Boolean = !proxy.isGroup && proxy.type !in NON_ENDPOINT_TYPES

    private fun isAvailableEndpoint(proxy: Proxy): Boolean =
        isEndpoint(proxy) && proxy.delay in 1 until UNAVAILABLE_DELAY

    private fun clearCaptcha() {
        openedCaptchaUrl = null
        captchaEndpoint = null
        cancelCaptchaNotification()
    }

    private fun handleTurnEvent(event: VkTurnEvent) {
        val runtime = instances[event.endpoint] ?: return
        if (!runtime.expected || runtime.token != event.token) return
        val url = event.captcha
        if (url != null) {
            if (url.isBlank()) {
                if (captchaEndpoint == event.endpoint) clearCaptcha()
            } else {
                normalizeCaptchaUrl(url)?.let {
                    captchaEndpoint = event.endpoint
                    handleCaptchaUrl(it)
                }
            }
        } else if (event.message.startsWith("[State]")) {
            logInfo("VK TURN endpoint=${event.endpoint} ${event.message}")
        }
    }

    private fun parseCommandLine(commandLine: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        commandLine.forEach { char ->
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }
                char == '\\' && quote != '\'' -> escaping = true
                quote != null -> {
                    if (char == quote)
                        quote = null
                    else
                        current.append(char)
                }
                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }

        if (escaping)
            current.append('\\')

        if (quote != null)
            throw IllegalArgumentException("Unclosed quote")

        if (current.isNotEmpty())
            result.add(current.toString())

        return result
    }

    private fun normalizeCaptchaUrl(raw: String): CaptchaUrl? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null

        if (uri.scheme != "http")
            return null

        val host = uri.host ?: return null
        if (!host.equals("localhost", ignoreCase = true) && host != "127.0.0.1")
            return null

        val builder = uri.buildUpon()
            .encodedAuthority("127.0.0.1:${uri.port.takeIf { it > 0 } ?: CAPTCHA_PORT}")
            .clearQuery()

        uri.queryParameterNames
            .filterNot { it.equals("blank", ignoreCase = true) }
            .forEach { name ->
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }

        val normalized = builder.build().toString()

        return when (uri.path) {
            CAPTCHA_PATH -> CaptchaUrl(normalized)
            "", "/" -> CaptchaUrl(normalized)
            else -> null
        }
    }

    private fun handleCaptchaUrl(captcha: CaptchaUrl) {
        val url = captcha.url

        if (openedCaptchaUrl == url)
            return

        if (openedCaptchaUrl != null)
            logInfo("VK TURN fallback captcha URL updated: $url")

        openedCaptchaUrl = url

        logInfo("VK TURN fallback captcha URL detected: $url")
        showCaptchaNotification(url)
    }

    private fun createCaptchaNotificationChannel() {
        runCatching {
            notificationManager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CAPTCHA_CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(service.getText(R.string.vk_turn_captcha_channel)).build()
            )
        }.onFailure {
            logWarning("VK TURN fallback captcha notification channel failed: ${it.message}", it)
        }
    }

    private fun showCaptchaNotification(url: String) {
        val intent = Intent()
            .setClassName(service.packageName, CAPTCHA_ACTIVITY)
            .setData(Uri.parse(url))
            .putExtra(CAPTCHA_ACTIVITY_URL_EXTRA, url)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

        val pendingIntent = PendingIntent.getActivity(
            service,
            R.id.nf_vk_turn_captcha,
            intent,
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        val notification = NotificationCompat.Builder(service, CAPTCHA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(service.getColorCompat(R.color.color_clash))
            .setContentTitle(service.getText(R.string.vk_turn_captcha_title))
            .setContentText(service.getText(R.string.vk_turn_captcha_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            notificationManager.notify(R.id.nf_vk_turn_captcha, notification)
        }.onSuccess {
            logInfo("VK TURN fallback captcha notification shown")
        }.onFailure {
            logWarning("VK TURN fallback captcha notification failed: ${it.message}", it)
        }
    }

    private fun cancelCaptchaNotification() {
        runCatching {
            notificationManager.cancel(R.id.nf_vk_turn_captcha)
        }
    }

    private fun logInfo(message: String, throwable: Throwable? = null) {
        Log.i(message, throwable)
        AppLogWriter.append(service, LogMessage.Level.Info, message, throwable, LOG_SOURCE)
    }

    private fun logWarning(message: String, throwable: Throwable? = null) {
        Log.w(message, throwable)
        AppLogWriter.append(service, LogMessage.Level.Warning, message, throwable, LOG_SOURCE)
    }

    companion object {
        private const val LOG_SOURCE = "VK_TURN"
        private const val CAPTCHA_CHANNEL_ID = "vk_turn_captcha_channel"
        private const val CAPTCHA_ACTIVITY = "com.github.kr328.clash.VkTurnCaptchaActivity"
        private const val CAPTCHA_ACTIVITY_URL_EXTRA = "url"
        private const val CAPTCHA_SUBMITTED_ACTION =
            "com.github.kr328.clash.action.VK_TURN_CAPTCHA_SUBMITTED"
        private const val CAPTCHA_PORT = 8765
        private const val CAPTCHA_PATH = "/not_robot_captcha"
        private const val RUNNING_WATCHDOG_INTERVAL = 15_000L
        private const val RECOVERY_STOP_DELAY = 60_000L
        private const val REQUIRED_CHECKS = 2
        private val RUNNING_STATES = setOf("connecting", "connected", "captcha")
        private const val MAX_COMPLETED_CHECKS = 64
        private const val UNAVAILABLE_DELAY = 0xffff

        private val NON_ENDPOINT_TYPES = setOf(
            "Direct",
            "Reject",
            "RejectDrop",
            "Compatible",
            "Pass",
            "PassRule",
            "Dns",
            "Unknown",
        )

        private val CAPTCHA_URL_MARKERS = listOf(
            "CAPTCHA_URL:",
            "Open this URL in your browser:",
            "[Captcha Proxy] Redirecting ROOT to:",
            "manually open this URL:",
        )
        private val CAPTCHA_URL_REGEX = Regex("""http://(?:localhost|127\.0\.0\.1)(?::\d+)?(?:/[^\s]*)?""")
    }

    private data class CaptchaUrl(
        val url: String,
    )
}
