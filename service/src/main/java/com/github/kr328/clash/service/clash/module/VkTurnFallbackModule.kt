package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.AppLogWriter
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class VkTurnFallbackModule(service: Service) : Module<Unit>(service) {
    private val store = ServiceStore(service)
    private val connectivity = service.getSystemService<ConnectivityManager>()

    private var logcat: Job? = null
    private var watchdog: Job? = null
    private var resumeWatchdog: Job? = null
    private var runningArgs: List<String>? = null
    private var openedCaptchaUrl: String? = null
    private var waitingForCaptcha = false
    private var lastAvailableEndpointCount: Int? = null
    private var moduleScope: CoroutineScope? = null
    private val notificationManager = NotificationManagerCompat.from(service)

    override suspend fun run() = coroutineScope {
        moduleScope = this

        logInfo("VK TURN fallback module initialized, enabled=${store.vkTurnFallback}")

        if (!store.vkTurnFallback) {
            logInfo("VK TURN fallback disabled")
            return@coroutineScope
        }

        logInfo("VK TURN fallback enabled")

        createCaptchaNotificationChannel()

        val captchaSubmitted = receiveBroadcast(false) {
            addAction(CAPTCHA_SUBMITTED_ACTION)
        }
        val screenToggle = receiveBroadcast(false) {
            addAction(Intent.ACTION_SCREEN_ON)
        }

        launch {
            for (ignored in captchaSubmitted) {
                openedCaptchaUrl = null
                waitingForCaptcha = false
                logInfo("VK TURN fallback captcha submitted")
                cancelCaptchaNotification()
                scheduleHealthWatchdog("captcha submitted")
            }
        }

        launch {
            for (ignored in screenToggle) {
                restartIfExpectedButStopped("screen on")
                scheduleHealthWatchdog("screen on")
            }
        }

        launch {
            while (isActive) {
                delay(RUNNING_WATCHDOG_INTERVAL)
                restartIfExpectedButStopped("running watchdog")
            }
        }

        logcat = launch {
            coroutineScope {
                launch {
                    val events = Clash.subscribeVkTurnEvents()

                    try {
                        for (line in events) {
                            handleProcessLine(line)
                        }
                    } finally {
                        events.cancel()
                    }
                }

                launch {
                    val logs = Clash.subscribeLogcat()

                    try {
                        for (message in logs) {
                            if (message.message.contains(VK_TURN_LOG_PREFIX)) {
                                handleProcessLine(message.message)
                            }
                        }
                    } finally {
                        logs.cancel()
                    }
                }
            }
        }

        delay(INITIAL_DELAY)

        try {
            while (isActive) {
                val args = readFallbackArguments()

                if (args == null) {
                    logInfo("VK TURN fallback arguments are absent")
                    stopProcess("fallback configuration is absent")
                } else {
                    var healthCheckSucceeded = true
                    val availableEndpoints = runCatching {
                        availableEndpointCount()
                    }.getOrElse {
                        logWarning("VK TURN fallback health check failed", it)
                        healthCheckSucceeded = false

                        STOP_THRESHOLD
                    }

                    if (healthCheckSucceeded)
                        noteEndpointAvailability(availableEndpoints, "periodic health check")

                    logInfo(
                        "VK TURN fallback check: availableEndpoints=$availableEndpoints " +
                                "args=${args.joinToString(" ")}"
                    )

                    when {
                        availableEndpoints == 0 -> startProcess(args)
                        availableEndpoints >= STOP_THRESHOLD -> stopProcess(
                            "$availableEndpoints endpoints are available"
                        )
                    }
                }

                delay(CHECK_INTERVAL)
            }
        } finally {
            stopProcess("service stopped")
            runCatching {
                logcat?.cancelAndJoin()
            }.onFailure {
                logWarning("VK TURN fallback logcat cancel failed: ${it.message}", it)
            }
            logcat = null
            watchdog?.cancel()
            watchdog = null
            resumeWatchdog?.cancel()
            resumeWatchdog = null
            moduleScope = null
        }
    }

    private suspend fun restartIfExpectedButStopped(reason: String) {
        val args = runningArgs ?: return

        val coreRunning = runCatching {
            Clash.isVkTurnRunning()
        }.getOrDefault(false)

        if (coreRunning)
            return

        logWarning("VK TURN fallback expected to run, but core is stopped after $reason; restarting")
        runningArgs = null
        openedCaptchaUrl = null
        waitingForCaptcha = false
        lastAvailableEndpointCount = 0
        watchdog?.cancel()
        watchdog = null
        cancelCaptchaNotification()
        startProcess(args)
    }

    private fun scheduleHealthWatchdog(reason: String) {
        val args = runningArgs ?: return
        val scope = moduleScope ?: return

        if (waitingForCaptcha) {
            logInfo("VK TURN fallback health watchdog after $reason postponed: waiting for captcha")
            return
        }

        if (resumeWatchdog?.isActive == true)
            return

        resumeWatchdog = scope.launch {
            delay(HEALTH_WATCHDOG_DELAY)

            resumeWatchdog = null

            if (runningArgs != args || waitingForCaptcha)
                return@launch

            val coreRunning = runCatching {
                Clash.isVkTurnRunning()
            }.getOrDefault(false)

            if (!coreRunning) {
                logWarning("VK TURN fallback health watchdog after $reason: core is stopped; restarting")
                restartProcess(args, "health watchdog after $reason: core stopped")
                return@launch
            }

            val availableEndpoints = runCatching {
                availableEndpointCount()
            }.getOrElse {
                logWarning("VK TURN fallback health watchdog after $reason health check failed", it)

                0
            }

            if (availableEndpoints > 0) {
                logInfo("VK TURN fallback health watchdog after $reason: availableEndpoints=$availableEndpoints")
                noteEndpointAvailability(availableEndpoints, "health watchdog after $reason")
                return@launch
            }

            logWarning(
                "VK TURN fallback health watchdog after $reason: no available endpoints " +
                        "after ${HEALTH_WATCHDOG_DELAY / 1000}s; restarting"
            )

            restartProcess(args, "health watchdog after $reason failed")
        }
    }

    private suspend fun readFallbackArguments(): List<String>? = withContext(Dispatchers.IO) {
        val profile = store.activeProfile
        if (profile == null) {
            logInfo("VK TURN fallback active profile is absent")

            return@withContext null
        }

        val config = service.importedDir
            .resolve(profile.toString())
            .resolve("config.yaml")

        if (!config.isFile) {
            logInfo("VK TURN fallback config is absent: ${config.absolutePath}")

            return@withContext null
        }

        val firstLine = config.bufferedReader().use { it.readLine() }
        if (firstLine == null) {
            logInfo("VK TURN fallback config is empty: ${config.absolutePath}")

            return@withContext null
        }

        val comment = firstLine.trim().takeIf { it.startsWith("#") }
            ?.drop(1)
            ?.trim()
        if (comment == null) {
            logInfo("VK TURN fallback first config line is not a comment")

            return@withContext null
        }

        if (!comment.startsWith("-")) {
            logInfo("VK TURN fallback first comment does not look like arguments")

            return@withContext null
        }

        runCatching {
            parseCommandLine(comment)
        }.getOrElse {
            logWarning("VK TURN fallback arguments are invalid", it)

            null
        }?.takeIf { it.isNotEmpty() }
    }

    private suspend fun availableEndpointCount(): Int {
        val groups = Clash.queryGroupNames(false)

        if (groups.isEmpty())
            return STOP_THRESHOLD

        groups.forEach {
            runCatching {
                withTimeoutOrNull(HEALTH_CHECK_TIMEOUT) {
                    Clash.healthCheck(it).await()
                }
            }.onFailure { e ->
                logInfo("VK TURN fallback health check failed for $it", e)
            }
        }

        return groups.flatMap { group ->
            runCatching {
                Clash.queryGroup(group, ProxySort.Delay).proxies.filter(::isAvailableEndpoint)
            }.getOrDefault(emptyList())
        }.map { it.name }.distinct().size
    }

    private fun isAvailableEndpoint(proxy: Proxy): Boolean {
        if (proxy.isGroup)
            return false

        if (proxy.type in NON_ENDPOINT_TYPES)
            return false

        return proxy.delay in 1 until UNAVAILABLE_DELAY
    }

    private suspend fun startProcess(args: List<String>) {
        if (runningArgs == args) {
            if (Clash.isVkTurnRunning()) {
                scheduleHealthWatchdog("periodic check with no available endpoints")
                return
            }

            logWarning("VK TURN fallback state was running, but core is stopped; restarting")
            runningArgs = null
            openedCaptchaUrl = null
            waitingForCaptcha = false
            lastAvailableEndpointCount = 0
            watchdog?.cancel()
            watchdog = null
            cancelCaptchaNotification()
        }

        if (runningArgs != null)
            stopProcess("fallback arguments changed")

        val vkLink = findArgument(args, "-vk-link")
        if (vkLink != null && !isVkLinkReachable(vkLink)) {
            logWarning(
                "VK TURN fallback start postponed: ${Uri.parse(vkLink).host ?: vkLink} is unavailable"
            )
            return
        }

        runCatching {
            logInfo("VK TURN fallback starting in core: ${args.joinToString(" ")}")

            Clash.startVkTurn(args)
        }.onSuccess {
            runningArgs = args
            logInfo("VK TURN fallback started")
            scheduleHealthWatchdog("start")
        }.onFailure {
            logWarning("VK TURN fallback start failed: ${it.message}", it)
        }
    }

    private suspend fun restartProcess(args: List<String>, reason: String) {
        logInfo("VK TURN fallback restarting: $reason")

        runningArgs = null
        openedCaptchaUrl = null
        waitingForCaptcha = false
        lastAvailableEndpointCount = 0
        cancelCaptchaNotification()

        runCatching {
            Clash.stopVkTurn()
        }.onFailure {
            logWarning("VK TURN fallback stop before restart failed: ${it.message}", it)
        }

        startProcess(args)
    }

    private fun findArgument(args: List<String>, name: String): String? {
        args.forEachIndexed { index, argument ->
            when {
                argument == name -> return args.getOrNull(index + 1)
                argument.startsWith("$name=") -> return argument.substringAfter('=')
            }
        }

        return null
    }

    private suspend fun isVkLinkReachable(link: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return@withContext false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return@withContext false
        val port = uri.port.takeIf { it > 0 } ?: when (uri.scheme?.lowercase()) {
            "http" -> 80
            else -> 443
        }

        val physicalNetworks = connectivity?.allNetworks.orEmpty().filter { network ->
            connectivity?.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }

        runCatching {
            val networkTargets = physicalNetworks.flatMap { network ->
                runCatching { network.getAllByName(host).toList() }
                    .getOrDefault(emptyList())
                    .mapNotNull { address ->
                        address.hostAddress?.let { network to it }
                    }
            }

            check(networkTargets.isNotEmpty()) { "no physical network can resolve $host" }

            var lastError: Throwable? = null
            for ((network, address) in networkTargets) {
                val connected = runCatching {
                    network.socketFactory.createSocket().use { socket ->
                        checkReachabilitySocket(socket, host, address, port, uri)
                    }
                }.onFailure { lastError = it }.isSuccess

                if (connected)
                    return@runCatching
            }

            throw lastError ?: IllegalStateException("unable to connect to $host")
        }.onFailure {
            logInfo("VK TURN fallback reachability check failed for $host:$port: ${it.message}")
        }.isSuccess
    }

    private fun checkReachabilitySocket(
        socket: Socket,
        tlsHost: String,
        address: String,
        port: Int,
        uri: Uri,
    ) {
        socket.connect(InetSocketAddress(address, port), VK_REACHABILITY_TIMEOUT)
        socket.soTimeout = VK_REACHABILITY_TIMEOUT

        if (uri.scheme.equals("https", ignoreCase = true)) {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, tlsHost, port, false) as SSLSocket
            tls.use {
                it.soTimeout = VK_REACHABILITY_TIMEOUT
                it.startHandshake()
            }
        }
    }

    private fun stopProcess(reason: String) {
        val coreRunning = runCatching {
            Clash.isVkTurnRunning()
        }.getOrDefault(false)

        if (runningArgs == null && !coreRunning)
            return

        runningArgs = null
        openedCaptchaUrl = null
        waitingForCaptcha = false
        lastAvailableEndpointCount = null
        watchdog?.cancel()
        watchdog = null
        resumeWatchdog?.cancel()
        resumeWatchdog = null
        cancelCaptchaNotification()

        logInfo("VK TURN fallback stopping: $reason")

        runCatching {
            Clash.stopVkTurn()
        }.onFailure {
            logWarning("VK TURN fallback stop failed: ${it.message}", it)
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

    private fun noteEndpointAvailability(availableEndpoints: Int, reason: String) {
        val previous = lastAvailableEndpointCount
        lastAvailableEndpointCount = availableEndpoints

        if (previous != 0 || availableEndpoints <= 0)
            return

        logInfo(
            "VK TURN fallback endpoints recovered after $reason; " +
                    "closing active Clash connections"
        )

        runCatching {
            Clash.closeAllConnections()
        }.onFailure {
            logWarning("VK TURN fallback failed to close active connections: ${it.message}", it)
        }
    }

    private fun handleProcessLine(line: String) {
        if (line.contains("CAPTCHA_URL") ||
            line.contains("ACTION REQUIRED") ||
            line.contains("Triggering manual captcha fallback", ignoreCase = true)) {
            logInfo("VK TURN fallback event received: $line")
        }

        if (line.contains("Established DTLS connection!", ignoreCase = true) ||
            line.contains("DTLS connection established", ignoreCase = true)) {
            logInfo("VK TURN fallback DTLS established")
        }

        if (line.contains("ACTION REQUIRED") ||
            line.contains("Triggering manual captcha fallback", ignoreCase = true)) {
            openedCaptchaUrl = null
            waitingForCaptcha = true
            resumeWatchdog?.cancel()
            resumeWatchdog = null
        }

        extractCaptchaUrl(line)?.let(::handleCaptchaUrl)
    }

    private fun extractCaptchaUrl(line: String): CaptchaUrl? {
        val raw = CAPTCHA_URL_MARKERS
            .firstNotNullOfOrNull { marker ->
                line.substringAfter(marker, missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            ?: CAPTCHA_URL_REGEX.find(line)?.value
            ?.trim()
            ?: return null

        return normalizeCaptchaUrl(raw)
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

        waitingForCaptcha = true
        resumeWatchdog?.cancel()
        resumeWatchdog = null

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
        private const val INITIAL_DELAY = 5_000L
        private const val CHECK_INTERVAL = 30_000L
        private const val RUNNING_WATCHDOG_INTERVAL = 15_000L
        private const val HEALTH_WATCHDOG_DELAY = 20_000L
        private const val HEALTH_CHECK_TIMEOUT = 30_000L
        private const val VK_REACHABILITY_TIMEOUT = 5_000
        private const val UNAVAILABLE_DELAY = 0xffff
        private const val STOP_THRESHOLD = 2
        private const val VK_TURN_LOG_PREFIX = "[VK_TURN]"

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
