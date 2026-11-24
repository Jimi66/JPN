package com.jpn.app.vpn

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent, bounded diagnostic journal for the TURN child process.
 * Potentially sensitive URLs and credential-like values are redacted before storage.
 */
object TurnDiagnostics {
    private const val PREFS = "jpn_settings"
    private const val KEY_LOG = "turn_diagnostics_log"
    private const val KEY_RUNNING = "turn_diagnostics_running"
    private const val KEY_EXIT = "turn_diagnostics_exit"
    private const val KEY_STARTED_AT = "turn_diagnostics_started_at"
    private const val MAX_LINES = 300
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun record(context: Context, message: String) {
        val safeMessage = redact(message).take(1200)
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getString(KEY_LOG, "").orEmpty()
            val lines = previous.lineSequence().filter { it.isNotBlank() }.toMutableList()
            lines.add("[${timestampFormat.format(Date())}] $safeMessage")
            val bounded = lines.takeLast(MAX_LINES).joinToString("\n")
            prefs.edit().putString(KEY_LOG, bounded).apply()
        }
    }

    fun setProcessState(context: Context, running: Boolean, exitCode: Int?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_EXIT, exitCode?.toString() ?: "")
        if (running) editor.putLong(KEY_STARTED_AT, System.currentTimeMillis())
        else editor.remove(KEY_STARTED_AT)
        editor.apply()
    }

    fun processUptime(context: Context): String {
        val startedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return ""
        val seconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L)) / 1000L
        return when {
            seconds < 60 -> seconds.toString() + " сек."
            seconds < 3600 -> (seconds / 60).toString() + " мин. " + (seconds % 60) + " сек."
            else -> (seconds / 3600).toString() + " ч. " + ((seconds % 3600) / 60) + " мин."
        }
    }

    fun isProcessRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RUNNING, false)

    fun exitCode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXIT, "").orEmpty()

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG, "").orEmpty()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOG).apply()
        record(context, "Журнал очищен пользователем")
    }

    private fun redact(input: String): String {
        var value = input.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[ссылка скрыта]")
        value = value.replace(
            Regex("(?i)(token|secret|password|private[-_ ]?key|credential)(\\s*[=:]\\s*)\\S+"),
            "$1$2[скрыто]"
        )
        value = value.replace(Regex("(?i)vk_profile\\.json"), "[путь профиля]")
        // Never expose endpoint addresses in a shared diagnostic export.
        value = value.replace(
            Regex("(?<![\\w.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?::\\d{1,5})?"),
            "[IP скрыт]"
        )
        value = value.replace(
            Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?:%[\\w.-]+)?(?::\\d{1,5})?(?![0-9a-f:])"),
            "[IPv6 скрыт]"
        )
        return value
    }
}
