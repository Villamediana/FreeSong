package com.villamediana.sound.download

/**
 * Why queued downloads aren't running right now. The queue itself waits on its own (the worker
 * only runs with a connection — an unmetered one with "Baixar só no Wi-Fi" — and picks up where it
 * stopped when the network comes back); this tells the screens what to say.
 */
enum class DownloadWait {
    /** Downloads run now. */
    NONE,

    /** No internet: they start by themselves on the first connection. */
    NETWORK,

    /** "Baixar só no Wi-Fi" on a metered network: they start on the Wi-Fi. */
    WIFI,
    ;

    companion object {
        fun of(online: Boolean, unmetered: Boolean, wifiOnly: Boolean): DownloadWait = when {
            !online -> NETWORK
            wifiOnly && !unmetered -> WIFI
            else -> NONE
        }
    }
}
