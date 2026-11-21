package com.jpn.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AutoGeoDatabaseWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(GeoDatabaseAutoUpdateActivity.KEY_ENABLED, true)) {
            return Result.success()
        }

        return try {
            GeoDatabaseInstaller.updateDatabases(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
