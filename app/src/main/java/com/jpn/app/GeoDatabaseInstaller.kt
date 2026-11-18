package com.jpn.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Validates and installs the GeoSite/GeoIP databases required before app use. */
internal object GeoDatabaseInstaller {
    private const val PREFS = "jpn_settings"
    private const val GEO_DEFAULT =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/"

    fun areDatabasesValid(context: Context): Boolean {
        val dir = context.applicationContext.filesDir
        return isValidDatabase(File(dir, "geosite.dat")) &&
            isValidDatabase(File(dir, "geoip.dat"))
    }

    fun installMissing(
        context: Context,
        onProgress: (Int, String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val appContext = context.applicationContext
        Thread {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val items = listOf(
                Triple("site_geosite_url", "geosite.dat", "GeoSite"),
                Triple("site_geoip_url", "geoip.dat", "GeoIP")
            ).filter { (_, fileName, _) ->
                !isValidDatabase(File(appContext.filesDir, fileName))
            }
            if (items.isEmpty()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete(true, "Обе базы уже установлены")
                }
                return@Thread
            }

            var completed = 0
            val errors = mutableListOf<String>()
            items.forEach { (urlKey, fileName, label) ->
                val address = prefs.getString(urlKey, null)?.takeIf { it.isNotBlank() }
                    ?: GEO_DEFAULT + fileName
                try {
                    download(appContext.filesDir, address, fileName) { filePercent ->
                        val overall = ((completed * 100) + filePercent) / items.size
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onProgress(overall, "Загрузка $label… $filePercent%")
                        }
                    }
                    completed++
                    val overall = completed * 100 / items.size
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProgress(overall, "$label загружена")
                    }
                } catch (error: Exception) {
                    errors.add("$label: ${error.message ?: "ошибка загрузки"}")
                }
            }
            val success = errors.isEmpty() && areDatabasesValid(appContext)
            val details = if (success) "Базы GeoSite и GeoIP успешно загружены"
                else errors.joinToString("\n").ifBlank { "Не удалось проверить загруженные базы" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(success, details)
            }
        }.start()
    }

    private fun download(
        directory: File,
        address: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        val temp = File(directory, "$fileName.tmp")
        try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JPN-Android")
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val expected = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 100L * 1024 * 1024) {
                            throw IllegalStateException("Файл больше 100 МБ")
                        }
                        output.write(buffer, 0, count)
                        if (expected > 0) onProgress((total * 100 / expected).toInt().coerceIn(0, 99))
                    }
                    if (total < 1024) throw IllegalStateException("Файл слишком маленький")
                }
            }
            if (!isValidDatabase(temp)) {
                throw IllegalStateException("Некорректный формат Geo-базы")
            }

            val target = File(directory, fileName)
            val backup = File(directory, "$fileName.bak")
            backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("Не удалось сохранить старую базу")
            }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IllegalStateException("Не удалось установить базу")
            }
            backup.delete()
            onProgress(100)
        } catch (error: Exception) {
            temp.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    private fun isValidDatabase(file: File): Boolean =
        GeoDatabaseReader.readCategories(file).isNotEmpty()
}
