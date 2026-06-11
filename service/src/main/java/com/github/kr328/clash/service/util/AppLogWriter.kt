package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import java.io.File

object AppLogWriter {
    private val lock = Any()
    private var file: File? = null

    fun append(
        context: Context,
        level: LogMessage.Level,
        message: String,
        throwable: Throwable? = null,
        source: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val lines = buildList {
            add(message)

            if (throwable != null) {
                throwable.stackTraceToString()
                    .lineSequence()
                    .forEach(::add)
            }
        }

        synchronized(lock) {
            val target = resolveFile(context).also {
                file = it
            }

            runCatching {
                target.appendText(lines.joinToString(separator = "\n") {
                    "$now:${level.name}:${formatMessage(it, source)}"
                } + "\n")
            }
        }
    }

    private fun resolveFile(context: Context): File {
        val logsDir = context.cacheDir.resolve("logs")
        logsDir.mkdirs()

        val current = file?.takeIf { it.isFile }
        val latest = logsDir.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                LOG_FILE_REGEX.matchEntire(file.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.let { it to file }
            }
            ?.maxByOrNull { it.first }
            ?.second

        return when {
            latest != null && (current == null || latest.lastModified() >= current.lastModified()) -> latest
            current != null -> current
            else -> logsDir.resolve("clash-${System.currentTimeMillis()}.log")
        }
    }

    private fun formatMessage(message: String, source: String?): String {
        if (source.isNullOrBlank())
            return message

        return "[$source] $message"
    }

    private val LOG_FILE_REGEX = Regex("clash-(\\d+)\\.log")
}
