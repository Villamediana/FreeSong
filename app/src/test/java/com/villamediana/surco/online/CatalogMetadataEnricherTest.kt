package com.villamediana.surco.online

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMetadataEnricherTest {

    // ---------------------------------------------------------------- matcher (pure)

    @Test fun parsesITunesJson() {
        val tracks = ITunesMatcher.parse(OnlineFixtures.itunesDeMusicaLigera)
        assertEquals(6, tracks.size)
        assertEquals("De Música Ligera", tracks[0].trackName)
        assertEquals("Canción Animal", tracks[0].collectionName)
        assertEquals(212747L, tracks[0].trackTimeMillis)
        assertEquals("Alternativo e rock latino", tracks[0].primaryGenreName)
    }

    @Test fun picksTheStudioOriginalForARemaster() {
        val q = ITunesMatcher.Query("De Música Ligera (Remasterizado 2007)", "Soda Stereo", album = null, durationMs = 213_000)
        val m = ITunesMatcher.best(q, ITunesMatcher.parse(OnlineFixtures.itunesDeMusicaLigera))
        assertNotNull(m)
        assertEquals("Canción Animal", m!!.track.collectionName)
    }

    @Test fun liveVersionMatchesLiveCandidate() {
        val q = ITunesMatcher.Query("De Música Ligera (En Vivo)", "Soda Stereo", album = null, durationMs = 291_000)
        val m = ITunesMatcher.best(q, ITunesMatcher.parse(OnlineFixtures.itunesDeMusicaLigera))
        assertEquals("El Último Concierto B (En Vivo)", m!!.track.collectionName)
    }

    @Test fun durationOutsideToleranceIsRejected() {
        val q = ITunesMatcher.Query("De Música Ligera", "Soda Stereo", album = null, durationMs = 400_000)
        assertNull(ITunesMatcher.best(q, ITunesMatcher.parse(OnlineFixtures.itunesDeMusicaLigera)))
    }

    @Test fun lookalikesAreRejected() {
        val q = ITunesMatcher.Query("DtMF", "Bad Bunny", album = null, durationMs = 237_000)
        assertNull(ITunesMatcher.best(q, ITunesMatcher.parse(OnlineFixtures.itunesDtmfLookalikes)))
    }

    @Test fun albumHintPrefersThatRelease() {
        val q = ITunesMatcher.Query("De Música Ligera", "Soda Stereo", album = "Lo Esencial: Soda Stereo", durationMs = 211_000)
        val m = ITunesMatcher.best(q, ITunesMatcher.parse(OnlineFixtures.itunesDeMusicaLigera))
        assertEquals("Lo Esencial: Soda Stereo", m!!.track.collectionName)
    }

    @Test fun albumTitleArtworkAndYear() {
        assertNull(ITunesMatcher.albumTitle("Envolver - Single"))
        assertEquals("Languis", ITunesMatcher.albumTitle("Languis - EP"))
        assertEquals("Canción Animal", ITunesMatcher.albumTitle("Canción Animal"))
        assertEquals(1990, ITunesMatcher.year("1990-08-07T07:00:00Z"))
        assertNull(ITunesMatcher.year("unknown"))
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/60/mzi.qyqtvoxs.jpg/1200x1200bb.jpg",
            ITunesMatcher.artwork("https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/60/mzi.qyqtvoxs.jpg/100x100bb.jpg"),
        )
        assertEquals("Soda Stereo De Música Ligera", ITunesMatcher.term(ITunesMatcher.Query("De Música Ligera (Remasterizado 2007)", "Soda Stereo", null, null)))
    }

    // ---------------------------------------------------------------- enrich (fake HTTP)

    private fun input(title: String, artist: String, catalog: Boolean, durationMs: Long?, album: String? = null, albumArtist: String? = null) =
        MetaInput(title, artist, album, albumArtist, trackNumber = null, year = null, durationMs = durationMs, fromMusicCatalog = catalog)

    @Test fun enrichCatalogSong() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.itunesDeMusicaLigera }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        val meta = enricher.enrich(input("De Música Ligera (Remasterizado 2007)", "Soda Stereo", catalog = true, durationMs = 213_000))
        assertEquals("De Música Ligera (Remasterizado 2007)", meta.title)
        assertEquals("Soda Stereo", meta.artist)
        assertEquals("Canción Animal", meta.album)
        assertEquals("Soda Stereo", meta.albumArtist)
        assertEquals("Alternativo e rock latino", meta.genre)
        assertEquals(1990, meta.year)
        assertEquals(6, meta.trackNumber)
        assertTrue(meta.artworkUrl!!.endsWith("/1200x1200bb.jpg"))
        val url = fake.requests.single().url
        assertEquals("BR", url.queryParameter("country"))
        assertEquals("song", url.queryParameter("entity"))
        assertEquals("Soda Stereo De Música Ligera", url.queryParameter("term"))
    }

    @Test fun enrichVideoFixesSpellingAndSingleHasNoAlbum() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.itunesEnvolver }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        val meta = enricher.enrich(input("ANITTA - ENVOLVER (Official Music Video)", "Anitta", catalog = false, durationMs = 194_000))
        assertEquals("Envolver", meta.title)
        assertEquals("Anitta", meta.artist)
        assertNull(meta.album)
        assertNull(meta.trackNumber)
        assertEquals("Pop em espanhol", meta.genre)
        assertEquals(2021, meta.year)
    }

    @Test fun albumDownloadValuesWin() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.itunesDeMusicaLigera }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        val meta = enricher.enrich(
            MetaInput("De Música Ligera", "Soda Stereo", album = "Obras Cumbres", albumArtist = "Soda Stereo - Topic",
                trackNumber = 3, year = 2002, durationMs = 213_000, fromMusicCatalog = true),
        )
        assertEquals("Obras Cumbres", meta.album)
        assertEquals("Soda Stereo", meta.albumArtist)
        assertEquals(3, meta.trackNumber)
        assertEquals(2002, meta.year)
        assertEquals("Alternativo e rock latino", meta.genre)
    }

    @Test fun songAlbumFromSearchGetsCatalogDetailsWhenItIsTheSameRelease() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.itunesDeMusicaLigera }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        val meta = enricher.enrich(input("De Música Ligera", "Soda Stereo", catalog = true, durationMs = 213_000, album = "Canción Animal"))
        assertEquals("Canción Animal", meta.album)
        assertEquals("Soda Stereo", meta.albumArtist)
        assertEquals(6, meta.trackNumber)
        assertEquals(1990, meta.year)
    }

    @Test fun cachedLookupsDoNotHitTheNetworkAgain() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.itunesDeMusicaLigera }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        val i = input("De Música Ligera", "Soda Stereo", catalog = true, durationMs = 213_000)
        val a = enricher.enrich(i)
        val b = enricher.enrich(i)
        assertEquals(a, b)
        assertEquals(1, fake.requests.size)
    }

    @Test fun throttledReturnsCleanedInputAndBacksOff() = runTest {
        var now = 1_000_000L
        val fake = OnlineFixtures.FakeHttp { 429 to "" }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR", clock = { now })
        val meta = enricher.enrich(input("Soda Stereo - Persiana Americana (Official Video)", "Soda Stereo", catalog = false, durationMs = 295_000))
        assertEquals("Persiana Americana", meta.title)
        assertEquals("Soda Stereo", meta.artist)
        assertNull(meta.genre)
        assertEquals(1, fake.requests.size)
        // Still cooling down: no new request.
        enricher.enrich(input("Anitta - Envolver", "Anitta", catalog = false, durationMs = 194_000))
        assertEquals(1, fake.requests.size)
        // After the cool-down it asks again.
        now += 31_000
        enricher.enrich(input("Anitta - Envolver", "Anitta", catalog = false, durationMs = 194_000))
        assertEquals(2, fake.requests.size)
    }

    @Test fun networkFailureNeverThrows() = runTest {
        val fake = OnlineFixtures.FakeHttp { throw java.net.UnknownHostException("offline") }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        val meta = enricher.enrich(input("Envolver (Official Video)", "AnittaVEVO", catalog = false, durationMs = 194_000))
        assertEquals("Envolver", meta.title)
        assertEquals("Anitta", meta.artist)
    }

    @Test fun ambiguousSplitIsResolvedByTheCatalog() = runTest {
        val fake = OnlineFixtures.FakeHttp { 200 to OnlineFixtures.itunesEnvolver }
        val enricher = CatalogMetadataEnricher(fake.client, country = "BR")
        // "Title - Artist" uploaded by a random channel: the catalog says which side is the artist.
        val meta = enricher.enrich(input("Envolver - Anitta (Letra)", "Musica Latina Letras", catalog = false, durationMs = 194_000))
        assertEquals("Envolver", meta.title)
        assertEquals("Anitta", meta.artist)
        assertEquals("Pop em espanhol", meta.genre)
    }

    @Test fun defaultCountry() {
        assertEquals("BR", CatalogMetadataEnricher.defaultCountry(java.util.Locale("pt", "BR")))
        assertEquals("US", CatalogMetadataEnricher.defaultCountry(java.util.Locale("es")))
    }
}
