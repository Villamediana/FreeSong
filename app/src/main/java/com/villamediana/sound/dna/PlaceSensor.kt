package com.villamediana.sound.dna

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.villamediana.sound.data.db.PlaceDao
import com.villamediana.sound.smart.PlaceClusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, as one of the learned places — only with "Lembrar lugares" on and the
 * location allowed. Light on the battery: never GPS (the network's / the system's own fix), at
 * most one ask every [EVERY_MS], and a fix the system already has is used first.
 */
class PlaceSensor(
    private val context: Context,
    private val dao: PlaceDao,
    private val enabled: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var askedAt = 0L
    @Volatile private var place: Long? = null
    @Volatile private var placeAt = 0L

    /** The last place found, while it is recent (no asking). */
    val last: Long? get() = place?.takeIf { clock() - placeAt < KEEP_MS }

    fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Allowed with the screen off too ("Permitir o tempo todo"): places are learned while music plays in the pocket. */
    fun permittedAlways(): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The place the phone is at now, or null (places off, not allowed, no fix). */
    suspend fun now(): Long? {
        if (!enabled() || !permitted()) return null
        val t = clock()
        if (t - askedAt < EVERY_MS) return last
        askedAt = t
        val loc = runCatching { fix() }.getOrNull() ?: return last
        return lock.withLock {
            val found = PlaceClusters.locate(dao.all(), loc.latitude, loc.longitude, clock())
            val id = if (found.id == 0L) dao.insert(found) else found.also { dao.update(it) }.id
            place = id
            placeAt = clock()
            id
        }
    }

    /** Forgets every place (and where each listen was): what was heard stays. */
    suspend fun forget() = lock.withLock {
        dao.clear()
        dao.clearFromEvents()
        dao.clearFromOnline()
        place = null
    }

    @Suppress("MissingPermission")
    private suspend fun fix(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        // A fix the system already has, recent and good enough, costs nothing.
        val cached = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { clock() - it.time < FRESH_MS && it.accuracy <= MAX_ACCURACY_M }
            .maxByOrNull { it.time }
        if (cached != null) return cached
        val provider = providers.dropLast(1).firstOrNull { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(ASK_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        val cancel = CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (cont.isActive) cont.resume(location)
                            }
                            @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        }
                        cont.invokeOnCancellation { lm.removeUpdates(listener) }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                }
            }
        }?.takeIf { it.accuracy <= MAX_ACCURACY_M }
    }

    private companion object {
        const val EVERY_MS = 10 * 60_000L
        const val FRESH_MS = 15 * 60_000L
        /** A place found stays "where you are" this long without a new fix. */
        const val KEEP_MS = 45 * 60_000L
        const val ASK_TIMEOUT_MS = 20_000L
        /** A fix vaguer than this says nothing about a place (Android's "aproximada" is ~2–3 km and still counts). */
        const val MAX_ACCURACY_M = 3_500f
    }
}
