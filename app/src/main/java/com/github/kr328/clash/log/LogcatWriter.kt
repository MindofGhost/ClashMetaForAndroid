package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.BufferedWriter
import java.io.FileWriter

class LogcatWriter(context: Context) : AutoCloseable {
    private val file = LogFile.generate()
    private val logFile = context.logsDir.resolve(file.fileName).apply {
        writeText("")
    }
    private val writer = BufferedWriter(FileWriter(logFile, true))

    override fun close() {
        writer.close()
    }

    fun appendMessage(message: LogMessage) {
        writer.appendLine(FORMAT.format(message.time.time, message.level.name, message.message))
        writer.flush()
    }

    companion object {
        private const val FORMAT = "%d:%s:%s"
    }
}
