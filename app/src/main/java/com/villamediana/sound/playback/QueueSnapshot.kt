package com.villamediana.sound.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The queue as saved in settings ([com.villamediana.sound.data.SettingsStore.setLastQueueJson]) so
 * it survives the app being killed: the headphone play button resumes it and the mini player shows
 * the last song when the app opens.
 */
@Serializable
data class QueueSnapshot(
    /** Track ids in timeline (unshuffled) order; online songs by their transient (negative) id. */
    val ids: List<Long>,
    /** Timeline index of the current song. */
    val index: Int,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    /** Play order (timeline indexes) when [shuffle] is on; empty = let the player decide. */
    val order: List<Int> = emptyList(),
    val repeat: RepeatMode = RepeatMode.OFF,
    val source: SavedSource? = null,
    val smart: Boolean = false,
    val savedAt: Long = 0,
    /** What each online song in [ids] is (video id + what to show), so it streams again on resume. */
    val online: List<OnlineItem> = emptyList(),
    /** Timeline indexes of the songs the app added to keep the queue going. */
    val suggested: List<Int> = emptyList(),
) {
    @Serializable
    data class SavedSource(val type: String, val title: String, val id: String? = null) {
        fun toPlaySource(): PlaySource? =
            runCatching { PlaySource(PlaySource.Type.valueOf(type), title, id) }.getOrNull()

        companion object {
            fun of(source: PlaySource?): SavedSource? = source?.let { SavedSource(it.type.name, it.title, it.id) }
        }
    }

    val isValid: Boolean get() = ids.isNotEmpty() && index in ids.indices

    /** Online songs of the queue by their transient id. */
    val onlineById: Map<Long, OnlineItem> get() = online.associateBy { it.trackId }

    fun encode(): String = json.encodeToString(serializer(), this)

    /**
     * Drops songs that are no longer in the library (and online ids without their [online] entry),
     * remapping the current index (the next surviving song when the current one is gone, from its
     * start) and the shuffle order. [existing] lists the library ids still there; online songs
     * always survive.
     */
    fun retain(existing: Set<Long>): QueueSnapshot? {
        val streams = online.mapTo(HashSet()) { it.trackId }
        val alive: (Long) -> Boolean = { id -> if (id < 0) id in streams else id in existing }
        if (ids.all(alive)) return takeIf { it.isValid }
        val keep = ids.indices.filter { alive(ids[it]) }
        if (keep.isEmpty()) return null
        val newIndexOf = IntArray(ids.size) { -1 }
        keep.forEachIndexed { newIdx, oldIdx -> newIndexOf[oldIdx] = newIdx }
        val currentSurvives = index in ids.indices && newIndexOf[index] >= 0
        val newIndex = when {
            currentSurvives -> newIndexOf[index]
            else -> keep.firstOrNull { it > index }?.let { newIndexOf[it] } ?: 0
        }
        val newOrder = if (order.size == ids.size) order.mapNotNull { old -> newIndexOf.getOrNull(old)?.takeIf { it >= 0 } } else emptyList()
        val keptIds = keep.map { ids[it] }
        val keptOnline = keptIds.filter { it < 0 }.toSet()
        val keptSuggested = suggested.mapNotNull { old -> newIndexOf.getOrNull(old)?.takeIf { it >= 0 } }
        return copy(
            ids = keptIds,
            suggested = keptSuggested,
            online = online.filter { it.trackId in keptOnline }.distinctBy { it.videoId },
            index = newIndex,
            positionMs = if (currentSurvives) positionMs else 0,
            order = newOrder,
        )
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun decode(text: String?): QueueSnapshot? {
            if (text.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.isValid }
        }
    }
}
