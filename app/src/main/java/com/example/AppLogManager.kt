package com.example

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val label: String) {
    INFO("INFO"),
    SUCCESS("SUCCESS"),
    WARN("WARN"),
    ERROR("ERROR"),
    DEBUG("DEBUG")
}

data class LogEntry(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val details: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    fun toFormattedString(): String {
        val header = "[$formattedTime] [${level.name}] [$tag] $message"
        return if (!details.isNullOrBlank()) {
            "$header\n    $details"
        } else {
            header
        }
    }
}

object AppLogManager {

    private const val MAX_LOG_ENTRIES = 500
    private val idGenerator = AtomicLong(1L)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    init {
        i("System", "YouTube Streamer initialized. Logs enabled for live diagnostics.")
    }

    fun i(tag: String, message: String, details: String? = null) {
        addEntry(LogLevel.INFO, tag, message, details)
        safeLog(LogLevel.INFO, tag, if (details != null) "$message | $details" else message)
    }

    fun s(tag: String, message: String, details: String? = null) {
        addEntry(LogLevel.SUCCESS, tag, message, details)
        safeLog(LogLevel.SUCCESS, tag, if (details != null) "SUCCESS: $message | $details" else "SUCCESS: $message")
    }

    fun w(tag: String, message: String, details: String? = null) {
        addEntry(LogLevel.WARN, tag, message, details)
        safeLog(LogLevel.WARN, tag, if (details != null) "$message | $details" else message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            it.printStackTrace(pw)
            sw.toString().trim()
        }
        addEntry(LogLevel.ERROR, tag, message, stackTrace)
        safeLog(LogLevel.ERROR, tag, message, throwable)
    }

    fun d(tag: String, message: String, details: String? = null) {
        addEntry(LogLevel.DEBUG, tag, message, details)
        safeLog(LogLevel.DEBUG, tag, if (details != null) "$message | $details" else message)
    }

    private fun safeLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        try {
            when (level) {
                LogLevel.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.INFO, LogLevel.SUCCESS -> Log.i(tag, message)
                LogLevel.DEBUG -> Log.d(tag, message)
            }
        } catch (_: Throwable) {
            // Ignored in JVM test environment where android.util.Log is not mocked
        }
    }

    private fun addEntry(level: LogLevel, tag: String, message: String, details: String?) {
        val entry = LogEntry(
            id = idGenerator.getAndIncrement(),
            level = level,
            tag = tag,
            message = message,
            details = details
        )
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_LOG_ENTRIES) {
                updated.takeLast(MAX_LOG_ENTRIES)
            } else {
                updated
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun getAllLogsAsText(): String {
        val currentLogs = _logs.value
        if (currentLogs.isEmpty()) {
            return "No logs recorded."
        }
        val sb = StringBuilder()
        sb.append("=== YOUTUBE VIDEO STREAMER DIAGNOSTIC LOGS ===\n")
        sb.append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("Total entries: ${currentLogs.size}\n\n")
        currentLogs.forEach { entry ->
            sb.append(entry.toFormattedString()).append("\n")
        }
        return sb.toString().trimEnd()
    }
}
