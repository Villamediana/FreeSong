package com.villamediana.sound.smart

import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.social.NumArtist
import com.villamediana.sound.social.NumPeriod
import com.villamediana.sound.social.Numbers
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import kotlin.random.Random

/** What the friends like nudges the suggestions: more when they agree, in full when they are along. */
class FriendsTasteTest {
    private val now = 1_800_000_000_000L
    private fun song(id: String, artist: String) = RemoteTrack(id, "https://music.youtube.com/watch?v=$id", "Song $id", artist, durationMs = 200_000)
    private fun friend(id: String, vararg artists: String) = FriendsTaste.Friend(id, artists.toList())
    private fun heard(id: String, artist: String) = Listen(id, "Song $id", artist, null, now - 86_400_000L, 200_000, 200_000, true, false)

    @Test
    fun aFriendsFavouriteCountsTwoAgreeingCountMoreAndTheOneAlongCountsInFull() {
        val friends = FriendsTaste.of(listOf(friend("ana", "Djavan", "Gal Costa", "Marisa Monte"), friend("bia", "Marisa Monte feat. Seu Jorge", "djavan")))
        val likes = friends.likes()
        // The favourite of one, the second of the other: more than either alone, never past 1.
        assertEquals(1.0 - 0.5 * (1.0 - 0.5 / 1.25), likes.getValue("djavan"), 1e-9)
        assertTrue(likes.getValue("djavan") > likes.getValue("gal costa"))
        // The same artist under another credit is the same artist.
        assertTrue(likes.getValue("marisa monte") > 0.5)
        assertTrue(likes.values.all { it in 0.0..1.0 })
        // Listening along: what she likes counts in full, the rest as before.
        val along = friends.likes(setOf("ana"))
        assertEquals(1.0, along.getValue("djavan"), 1e-9)
        assertTrue(along.getValue("gal costa") > likes.getValue("gal costa"))
        // Nobody, or friends who show nothing: no nudge.
        assertTrue(FriendsTaste.of(emptyList()).isEmpty)
        assertTrue(FriendsTaste.of(listOf(friend("cris"), friend("dani", " "))).likes().isEmpty())
    }

    @Test
    fun whatAFriendShowsTheirFriendsIsWhatIsRead() {
        val ana = Profile(
            id = "ana", username = "ana", name = "Ana",
            top = listOf(SongRef("v1", "Sina", "Djavan"), SongRef("v2", "Baby", "Gal Costa")),
            numbers = Numbers(month = NumPeriod(top = listOf(NumArtist("Liniker", 300))), year = NumPeriod(top = listOf(NumArtist("Djavan", 900)))),
        )
        // This month's first, then the year's, then the artists of the top songs.
        assertEquals(listOf("Liniker", "Djavan", "Djavan", "Gal Costa"), ana.favourites().artists)
        // Someone who isn't a friend shows none of it.
        assertTrue(FriendsTaste.of(listOf(Profile("x", "x", "X").favourites())).isEmpty)
    }

    @Test
    fun aSongOfAnArtistTheFriendsLikeRisesButTheListenersOwnTasteLeads() {
        val listens = (1..6).map { heard("m$it", "Mine") } + heard("s1", "Sometimes")
        val plain = TasteProfile.build(listens, emptyList(), now)
        val withFriends = TasteProfile.build(listens, emptyList(), now, friends = FriendsTaste.of(listOf(friend("ana", "Theirs"), friend("bia", "Theirs"))))
        assertEquals(0.0, plain.friendsLike("Theirs"), 0.0)
        assertEquals(0.75, withFriends.friendsLike("Theirs feat. Someone"), 1e-9)
        // The taste itself is the listener's: friends change nothing in it.
        assertEquals(plain.artists, withFriends.artists)
        assertFalse(withFriends.isKnownArtist("Theirs"))

        // Side by side in the radio (how close a song is still decides: the taste is a share of it).
        val radio = listOf(song("a", "Nobody 1"), song("b", "Nobody 2"), song("c", "Nobody 3"), song("mine", "Mine"), song("t", "Theirs")) +
            (1..10).map { song("z$it", "Filler $it") }
        val before = Recommend.fromRadios(listOf(radio), plain, emptySet(), 10).map { it.videoId }
        val after = Recommend.fromRadios(listOf(radio), withFriends, emptySet(), 10).map { it.videoId }
        assertTrue(after.indexOf("t") < before.indexOf("t"))
        // The listener's favourite still comes before the friends'.
        assertTrue(after.indexOf("mine") < after.indexOf("t"))
    }

    @Test
    fun whoIsAlongInTheJamLeansTheRadioTheirWay() {
        val friends = FriendsTaste.of(listOf(friend("ana", "Theirs")))
        val base = TasteProfile.build((1..4).map { heard("m$it", "Mine") }, emptyList(), now, friends = friends)
        val alone = base.inMoment(MusicDna.build(emptyList(), emptySet(), now), DnaMoment(20, DayOfWeek.FRIDAY))
        val together = base.inMoment(MusicDna.build(emptyList(), emptySet(), now), DnaMoment(20, DayOfWeek.FRIDAY, people = setOf("ana")))
        assertEquals(0.5, alone.friendsLike("Theirs"), 1e-9)
        assertEquals(1.0, together.friendsLike("Theirs"), 1e-9)

        val list = (1..10).map { song("f$it", "Filler $it") } + song("t", "Theirs") + (11..20).map { song("f$it", "Filler $it") }
        fun top(profile: TasteProfile) = (0 until 200).count { i ->
            RadioMix.rank(listOf(list), emptyList(), profile, Session(), emptySet(), 21, Random(i)).take(8).any { it.videoId == "t" }
        }
        val plain = top(TasteProfile.build((1..4).map { heard("m$it", "Mine") }, emptyList(), now))
        // Halfway down the radio: sometimes near the top by itself, more with a friend who likes it, most with her along.
        assertTrue("plain $plain alone ${top(alone)} together ${top(together)}", plain < top(alone) && top(alone) < top(together))
    }

    @Test
    fun whatIsSkippedAgainAndAgainStaysOutWhoeverLikesIt() {
        val skips = (1..7).map { Listen("n$it", "Song n$it", "Never", null, now - it * 3_600_000L, 5_000, 200_000, false, true) }
        val profile = TasteProfile.build(skips, emptyList(), now, friends = FriendsTaste.of(listOf(friend("ana", "Never"), friend("bia", "Never"))))
        val radio = listOf(song("new", "Never"), song("ok", "Other"))
        assertEquals(listOf("ok"), Recommend.fromRadios(listOf(radio), profile, emptySet(), 5).map { it.videoId })
        assertEquals(listOf("ok"), RadioMix.rank(listOf(radio), emptyList(), profile, Session(), emptySet(), 5, Random(1)).map { it.videoId })
    }
}
