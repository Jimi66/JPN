package com.jpn.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AutoSubscriptionScheduler {
    private const val WORK_NAME = "jpn_subscription_auto_update"

    fun update(context: Context) {
        val prefs = context.getSharedPreferences("jpn_settings", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(SubscriptionAutoUpdateActivity.KEY_ENABLED, true)
        val url = prefs.getString("subscription_url", null)
        if (!enabled || url.isNullOrBlank()) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        val hours = prefs.getInt(SubscriptionAutoUpdateActivity.KEY_INTERVAL,
            SubscriptionAutoUpdateActivity.DEFAULT_INTERVAL).coerceIn(6, 24)
        val request = PeriodicWorkRequestBuilder<AutoSubscriptionWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}
