package com.villamediana.sound.ui.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the phone has a network with internet right now — the online screens adapt to it. */
object Connectivity {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The network now: internet at all, and whether it is unmetered (Wi-Fi, not the carrier's data). */
    data class Net(val online: Boolean, val unmetered: Boolean)

    fun net(context: Context): Net {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Net(online = true, unmetered = true)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return Net(online = false, unmetered = false)
        return Net(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    /** [net] as it changes (the default network coming and going, or switching Wi-Fi ↔ data). */
    fun nets(context: Context): Flow<Net> = callbackFlow {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(Net(online = true, unmetered = true))
            awaitClose { }
            return@callbackFlow
        }
        trySend(net(app))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(net(app))
            }

            override fun onLost(network: Network) {
                trySend(Net(online = false, unmetered = false))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(Net(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    fun online(context: Context): Flow<Boolean> = callbackFlow {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        trySend(isOnline(app))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
