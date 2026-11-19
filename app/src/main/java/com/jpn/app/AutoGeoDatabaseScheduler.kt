package com.jpn.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AutoGeoDatabaseScheduler {
    private const val WORK_NAME = "jpn_geo_database_auto_update"

    fun update(context: Context) {
        val prefs = context.getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(GeoDatabaseAutoUpdateActivity.KEY_ENABLED, true)
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }

        val hours = prefs.getInt(
            GeoDatabaseAutoUpdateActivity.KEY_INTERVAL,
            GeoDatabaseAutoUpdateActivity.DEFAULT_INTERVAL
        ).coerceIn(6, 24)

        val request = PeriodicWorkRequestBuilder<AutoGeoDatabaseWorker>(
            hours.toLong(), TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
