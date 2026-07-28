package com.logos.bibletranslate.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Synchronous connectivity check — every AI-window path (translation, chat, partner mode) needs a live network. */
object NetworkUtils {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
