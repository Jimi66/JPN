package com.jpn.app

import android.content.Context
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads missing Xray GeoSite/GeoIP databases on the first app launch.
 * Existing valid databases are left untouched. Failed downloads are retried
 * the next time the app starts.
 */
internal object GeoDatabaseInstaller {
    private const val PREFS = "jpn_settings"
    private const val GEO_DEFAULT =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/"
    @Volatile private var running = false

    fun ensure(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (running) return
            val geoSite = File(appContext.filesDir, "geosite.dat")
            val geoIp = File(appContext.filesDir, "geoip.dat")
            val missing = listOf(
                Triple("site_geosite_url", "geosite.dat", geoSite),
                Triple("site_geoip_url", "geoip.dat", geoIp)
            ).filter { (_, _, file) -> !isValidDatabase(file) }
            if (missing.isEmpty()) return
            running = true
            Thread {
                val failures = mutableListOf<String>()
                try {
                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    missing.forEach { (urlKey, fileName, _) ->
                        val defaultName = fileName
                        val address = prefs.getString(urlKey, null)
                            ?.takeIf { it.isNotBlank() } ?: GEO_DEFAULT + defaultName
                        runCatching { download(appContext.filesDir, address, fileName) }
                            .onFailure { failures.add("$fileName: ${it.message ?: "ошибка загрузки"}") }
                    }
                } finally {
                    running = false
                }
                if (failures.isNotEmpty()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(
                            appContext,
                            "Не удалось загрузить базы GeoSite/GeoIP. Повторим при следующем запуске.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }

    private fun download(directory: File, address: String, fileName: String) {
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
