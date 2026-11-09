package com.villamediana.sound.smart

import com.villamediana.sound.data.db.Place
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The places music is heard at, learned from the phone's location alone (pure): a fix close to a
 * known place belongs to it (and nudges its middle a little), a fix far from all of them starts a
 * new one. No names: a place is just where you keep coming back to.
 */
object PlaceClusters {
    /** A fix this close to a place's middle is that place (a house, an office, a gym). */
    const val RADIUS_M = 180.0
    /** The middle stops moving much after this many fixes. */
    private const val SETTLE = 40

    /** The place for a fix at [lat],[lon]: an existing one (moved a little) or a new one (id 0). */
    fun locate(places: List<Place>, lat: Double, lon: Double, now: Long): Place {
        val near = places.map { it to distanceM(it.lat, it.lon, lat, lon) }
            .filter { it.second <= RADIUS_M }
            .minByOrNull { it.second }
            ?.first
            ?: return Place(lat = lat, lon = lon, fixes = 1, firstAt = now, lastAt = now)
        val n = near.fixes.coerceAtMost(SETTLE)
        return near.copy(
            lat = (near.lat * n + lat) / (n + 1),
            lon = (near.lon * n + lon) / (n + 1),
            fixes = near.fixes + 1,
            lastAt = maxOf(near.lastAt, now),
        )
    }

    /** Great-circle distance in meters. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
