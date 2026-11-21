package com.omgodse.notally.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Capacidad de acceso a red, sin bloquear VPN que no supere VALIDATED. */
internal object RoseDriveNetwork {
    private fun capabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)
    }

    fun hasNetwork(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun hasValidatedInternet(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
