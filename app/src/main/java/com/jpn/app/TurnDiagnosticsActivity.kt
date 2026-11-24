package com.jpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jpn.app.vpn.TurnDiagnostics

class TurnDiagnosticsActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var countersView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JpnTheme.applyBeforeCreate(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16), dp(10) + insets.systemWindowInsetTop,
                dp(16), dp(16) + insets.systemWindowInsetBottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "‹   Назад"
            textSize = 18f
            setTextColor(0xFF514B83.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(14))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Диагностика TURN"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEDEBF7.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        root.addView(statusView)
        countersView = TextView(this).apply {
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE8F0F7.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        root.addView(countersView)
        root.addView(TextView(this).apply {
            text = "Последние 300 строк. Ссылки, IP-адреса и значения похожие на ключи автоматически скрываются."
            textSize = 12f
            setPadding(0, dp(12), 0, dp(8))
        })
        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF17191F.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setTextColor(0xFFE6E6EA.toInt())
        }
        root.addView(logView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
        ))
        root.addView(Button(this).apply {
            text = "Обновить журнал"
            setOnClickListener { refreshLog() }
        })
        root.addView(Button(this).apply {
            text = "Скопировать журнал"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("JPN TURN diagnostics", buildExportText()))
                Toast.makeText(this@TurnDiagnosticsActivity, "Журнал скопирован", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Отправить журнал"
            setOnClickListener {
                runCatching {
                    val file = File(cacheDir, "jpn-turn-diagnostics.txt")
                    file.writeText(buildExportText())
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@TurnDiagnosticsActivity, "$packageName.fileprovider", file
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "JPN TURN diagnostics")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Отправить журнал диагностики"))
                }.onFailure {
                    Toast.makeText(this@TurnDiagnosticsActivity, "Не удалось подготовить файл журнала", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Очистить журнал"
            setOnClickListener {
                TurnDiagnostics.clear(this@TurnDiagnosticsActivity)
                refreshLog()
            }
        })

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        JpnTheme.apply(this, scroll)
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        val running = TurnDiagnostics.isProcessRunning(this)
        val exit = TurnDiagnostics.exitCode(this)
        statusView.text = when {
            running -> "Состояние: TURN-процесс отмечен как запущенный\nВремя работы: " + TurnDiagnostics.processUptime(this)
            exit.isNotBlank() -> "Состояние: TURN-процесс остановлен. Код завершения: $exit"
            else -> "Состояние: TURN-процесс сейчас не отмечен как запущенный"
        }
        val log = TurnDiagnostics.read(this)
        val lines = log.lineSequence().toList()
        val established = lines.count { it.contains("Established DTLS connection", ignoreCase = true) }
        val closed = lines.count { it.contains("Closed DTLS connection", ignoreCase = true) }
        val failures = lines.count {
            val line = it.lowercase()
            line.contains("error") || line.contains("failed") || line.contains("ошиб")
        }
        val requested = Regex("Запрошено потоков: (\\d+)").find(log)?.groupValues?.get(1) ?: "—"
        countersView.text = "Запрошено потоков: $requested\\n" +
            "Установлено DTLS: $established\\n" +
            "Закрыто DTLS: $closed\\n" +
            "Активно по журналу: ${(established - closed).coerceAtLeast(0)}\\n" +
            "Строк с ошибками: $failures\\n" +
            "Запуск отдельных worker/получение credentials: требуют расширенного журнала ядра"
        logView.text = log.ifBlank { "Журнал пока пуст. Запусти подключение через TURN, затем обнови журнал." }
    }

    private fun buildExportText(): String {
        val running = TurnDiagnostics.isProcessRunning(this)
        val exit = TurnDiagnostics.exitCode(this)
        return buildString {
            appendLine("JPN TURN diagnostics")
            appendLine("Process running: $running")
            if (exit.isNotBlank()) appendLine("Exit code: $exit")
            appendLine()
            append(TurnDiagnostics.read(this@TurnDiagnosticsActivity))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
