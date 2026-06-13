package com.kancy.display_test

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log entry with timestamp, category, and message.
 *
 * The display string is formatted once at construction and cached in [formatted]. Formatting
 * (especially the timestamp) is the dominant cost when logs flood, and the previous design
 * re-ran it for the whole buffer on every new line — so it must happen exactly once per entry.
 */
data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val category: LogCategory,
    val message: String
) {
    val formatted: String = "[${tsFormat.get()!!.format(Date(timestamp))}] [${category.tag}] $message"

    /** @deprecated use [formatted] — kept so older callers compile. */
    fun format(): String = formatted

    companion object {
        // SimpleDateFormat is not thread-safe and is expensive to construct; share one per thread.
        private val tsFormat = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        }
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
    private var nextId = 0L    // Monotonic id for stable LazyColumn keys (guarded by `logs`).

    /** Appends a log entry and returns it (so callers can update the UI incrementally). */
    fun addLog(message: String, category: LogCategory? = null): LogEntry {
        val cat = category ?: LogCategory.fromMessage(message)
        return synchronized(logs) {
            val entry = LogEntry(nextId++, System.currentTimeMillis(), cat, message)
            logs.add(entry)
            if (logs.size > maxLogs) {
                logs.removeAt(0)
            }
            entry
        }
    }

    /** Max number of entries retained — display lists should mirror this bound. */
    val capacity: Int get() = maxLogs

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
