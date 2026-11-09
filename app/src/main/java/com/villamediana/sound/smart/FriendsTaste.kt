package com.villamediana.sound.smart

/**
 * What the listener's friends like, read from what each friend already shows their friends (their
 * most heard artists and top songs): nothing more is asked for and nothing leaves the phone. An
 * artist a friend listens a lot counts; one several friends agree on counts more; a friend
 * listening along right now (in the jam) counts in full. It only nudges the suggestions: the
 * listener's own taste leads, and what they keep skipping stays out whoever likes it. Pure.
 */
class FriendsTaste private constructor(
    /** Friend id → (artist key → 0..1: how much of that friend's listening it is). */
    private val byFriend: Map<String, Map<String, Double>>,
) {
    /** One friend: their favourite artists as written, the most heard first. */
    data class Friend(val id: String, val artists: List<String>)

    val isEmpty: Boolean get() = byFriend.isEmpty()

    /** Artist key → 0..1: how much the friends like it, the ones in [along] in full. */
    fun likes(along: Set<String> = emptySet()): Map<String, Double> {
        if (byFriend.isEmpty()) return emptyMap()
        // Each friend who likes it leaves less room for "nobody does": two agreeing say more than one.
        val nobody = HashMap<String, Double>()
        for ((id, artists) in byFriend) {
            val weight = if (id in along) ALONG else FRIEND
            for ((artist, share) in artists) nobody[artist] = (nobody[artist] ?: 1.0) * (1.0 - weight * share)
        }
        return nobody.mapValues { 1.0 - it.value }
    }

    companion object {
        val NONE = FriendsTaste(emptyMap())

        /** What a friend's favourite artist counts, and the same when that friend is listening along. */
        const val FRIEND = 0.5
        const val ALONG = 1.0
        /** Each place down a friend's list counts this much less. */
        private const val STEP = 0.25

        fun of(friends: List<Friend>): FriendsTaste {
            val out = HashMap<String, Map<String, Double>>()
            for (f in friends) {
                val shares = LinkedHashMap<String, Double>()
                for (name in f.artists) {
                    val key = artistKey(name)
                    if (key.isEmpty() || key in shares) continue
                    shares[key] = 1.0 / (1.0 + STEP * shares.size)
                }
                if (shares.isNotEmpty()) out[f.id] = shares
            }
            return if (out.isEmpty()) NONE else FriendsTaste(out)
        }
    }
}
