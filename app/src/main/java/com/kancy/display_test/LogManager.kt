package com.kancy.display_test

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log entry with timestamp, category, and message.
 */
data class LogEntry(
    val timestamp: Long,
    val category: LogCategory,
    val message: String
) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
        return "[$ts] [${category.tag}] $message"
    }
}

/**
 * Log categories for filtering and grouping.
 */
enum class LogCategory(val tag: String, val emoji: String) {
    DISPLAY("Display", "🖥️"),
    ROOT("Root", "🔑"),
    CROSVM("Crosvm", "⚙️"),
    INPUT("Input", "⌨️"),
    CONNECTION("Connection", "🔌"),
    ERROR("Error", "❌"),
    INFO("Info", "ℹ️");

    companion object {
        fun fromMessage(message: String): LogCategory {
            return when {
                message.contains("root", ignoreCase = true) -> ROOT
                message.contains("crosvm", ignoreCase = true) ||
                    message.contains("display service", ignoreCase = true) -> CROSVM
                message.contains("input", ignoreCase = true) ||
                    message.contains("touch", ignoreCase = true) ||
                    message.contains("mouse", ignoreCase = true) ||
                    message.contains("key", ignoreCase = true) ||
                    message.contains("tablet mode", ignoreCase = true) -> INPUT
                message.contains("surface", ignoreCase = true) ||
                    message.contains("cursor", ignoreCase = true) ||
                    message.contains("display config", ignoreCase = true) -> DISPLAY
                message.contains("connect", ignoreCase = true) ||
                    message.contains("disconnect", ignoreCase = true) ||
                    message.contains("binder", ignoreCase = true) -> CONNECTION
                message.startsWith("❌") || message.contains("failed", ignoreCase = true) -> ERROR
                else -> INFO
            }
        }
    }
}

/**
 * Log manager with categorization, filtering, and export.
 */
class LogManager {
    private val logs = mutableListOf<LogEntry>()
    private val maxLogs = 500  // Keep last 500 entries

    fun addLog(message: String, category: LogCategory? = null) {
        val cat = category ?: LogCategory.fromMessage(message)
        val entry = LogEntry(System.currentTimeMillis(), cat, message)
        synchronized(logs) {
            logs.add(entry)
            if (logs.size > maxLogs) {
                logs.removeAt(0)
            }
        }
    }

    fun getAllLogs(): List<LogEntry> = synchronized(logs) { logs.toList() }

    fun getLogsByCategory(category: LogCategory): List<LogEntry> =
        synchronized(logs) { logs.filter { it.category == category } }

    fun clearLogs() = synchronized(logs) { logs.clear() }

    fun exportToString(): String {
        return synchronized(logs) {
            buildString {
                appendLine("=== display_app Diagnostic Logs ===")
                appendLine("Export time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Total entries: ${logs.size}")
                appendLine()
                logs.forEach { entry ->
                    appendLine(entry.format())
                }
            }
        }
    }

    fun getLogsByCategories(categories: Set<LogCategory>): List<LogEntry> =
        synchronized(logs) { logs.filter { it.category in categories } }
}
