package com.jpn.app

import android.content.Context

/**
 * Seeds the initial direct-routing allow-list once, without enabling routing
 * or overwriting entries the user may already have configured.
 */
internal object DefaultSiteRoutingRules {
    private const val PREFS = "jpn_settings"
    private const val SEEDED_KEY = "site_default_rules_seeded_v1"

    private val geoSites = listOf(
        "YANDEX",
        "OKKO",
        "VK",
        "KINOPOISK",
        "CATEGORY-RU",
        "REDDIT"
    )

    private val ips = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "224.0.0.0/4",
        "255.255.255.255"
    )

    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEDED_KEY, false)) return

        fun existing(key: String): LinkedHashSet<String> =
            prefs.getString(key, "").orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toCollection(linkedSetOf())

        val geosite = existing("site_direct_geosite").apply { addAll(geoSites) }
        val geoip = existing("site_direct_geoip").apply { add("RU") }
        val manualIps = existing("site_manual_ips").apply { addAll(ips) }

        prefs.edit()
            .putString("site_direct_geosite", geosite.joinToString("\n"))
            .putString("site_direct_geoip", geoip.joinToString("\n"))
            .putString("site_manual_ips", manualIps.joinToString("\n"))
            .putBoolean(SEEDED_KEY, true)
            .apply()
    }
}
