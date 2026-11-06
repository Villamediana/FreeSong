package com.villamediana.sound.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanerTest {

    private fun video(title: String, uploader: String) = TitleCleaner.clean(title, uploader, fromMusicCatalog = false)
    private fun catalog(title: String, artist: String) = TitleCleaner.clean(title, artist, fromMusicCatalog = true)

    private fun check(title: String, uploader: String, expectTitle: String, expectArtist: String) {
        val r = video(title, uploader)
        assertEquals("title of <$title>", expectTitle, r.title)
        assertEquals("artist of <$title>", expectArtist, r.artist)
    }

    // ---------------------------------------------------------------- noise, English

    @Test fun officialVideoVariants() {
        check("Soda Stereo - De Música Ligera (Official Video)", "Soda Stereo", "De Música Ligera", "Soda Stereo")
        check("Coldplay - Yellow (Official Video)", "Coldplay", "Yellow", "Coldplay")
        check("The Weeknd - Blinding Lights (Official Music Video)", "TheWeekndVEVO", "Blinding Lights", "The Weeknd")
        check("Dua Lipa - Levitating [Official Audio]", "Dua Lipa", "Levitating", "Dua Lipa")
        check("Billie Eilish - bad guy (Official Lyric Video)", "Billie Eilish", "bad guy", "Billie Eilish")
        check("Imagine Dragons - Believer (Lyrics)", "Taj Tracks", "Believer", "Imagine Dragons")
        check("Tame Impala - The Less I Know The Better (Official Visualizer)", "Tame Impala", "The Less I Know The Better", "Tame Impala")
        check("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official", "Bohemian Rhapsody (Remastered)", "Queen")
        check("Rick Astley - Never Gonna Give You Up (Official Music Video) [4K]", "Rick Astley", "Never Gonna Give You Up", "Rick Astley")
        check("Eminem - Lose Yourself [HD]", "EminemVEVO", "Lose Yourself", "Eminem")
        check("Linkin Park - Numb | Official Video", "Linkin Park", "Numb", "Linkin Park")
        check("Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers", "Daft Punk", "Get Lucky (feat. Pharrell Williams, Nile Rodgers)", "Daft Punk")
    }

    // ---------------------------------------------------------------- noise, Spanish

    @Test fun spanishNoise() {
        check("Bad Bunny - DtMF (Video Oficial)", "Bad Bunny", "DtMF", "Bad Bunny")
        check("Shakira - Hips Don't Lie (Official 4K Video) ft. Wyclef Jean", "shakiraVEVO", "Hips Don't Lie (feat. Wyclef Jean)", "Shakira")
        check("Maná - Rayando el Sol (Video Oficial)", "Maná", "Rayando el Sol", "Maná")
        check("Karol G - Provenza (Letra/Lyrics)", "Latin Hits Lyrics", "Provenza", "Karol G")
        check("Juanes - La Camisa Negra (Audio Oficial)", "JuanesVEVO", "La Camisa Negra", "Juanes")
        check("Rosalía - DESPECHÁ (Video Oficial) | MOTOMAMI", "Rosalía", "DESPECHÁ", "Rosalía")
        check("Café Tacvba - Eres (Videoclip Oficial)", "Café Tacvba", "Eres", "Café Tacvba")
        check("Gustavo Cerati - Crimen (Video Lyric)", "Gustavo Cerati", "Crimen", "Gustavo Cerati")
        check("Peso Pluma - Ella Baila Sola (Letra)", "Peso Pluma", "Ella Baila Sola", "Peso Pluma")
        check("Soda Stereo - Persiana Americana (Video Con Letra)", "Rock Nacional", "Persiana Americana", "Soda Stereo")
    }

    // ---------------------------------------------------------------- noise, Portuguese

    @Test fun portugueseNoise() {
        check("Anitta - Envolver [Official Music Video]", "Anitta", "Envolver", "Anitta")
        check("Marília Mendonça - Supera (Clipe Oficial)", "Marília Mendonça", "Supera", "Marília Mendonça")
        check("Jorge & Mateus - Propaganda (Vídeo Oficial)", "Jorge e Mateus", "Propaganda", "Jorge & Mateus")
        check("Henrique e Juliano - Liberdade Provisória (Áudio Oficial)", "Henrique e Juliano", "Liberdade Provisória", "Henrique e Juliano")
        check("Tribalistas - Velha Infância (Videoclipe)", "Tribalistas", "Velha Infância", "Tribalistas")
        check("Legião Urbana - Tempo Perdido (Com Letra)", "Rock Brasil", "Tempo Perdido", "Legião Urbana")
        check("Gusttavo Lima - Bloqueado (Lyric Vídeo)", "Gusttavo Lima", "Bloqueado", "Gusttavo Lima")
        check("Pabllo Vittar - Disk Me (Visualizer)", "Pabllo Vittar", "Disk Me", "Pabllo Vittar")
        check("Ivete Sangalo - Sorte Grande (Ao Vivo) - Vídeo Oficial", "Ivete Sangalo", "Sorte Grande (Ao Vivo)", "Ivete Sangalo")
    }

    // ---------------------------------------------------------------- meaningful parts survive

    @Test fun keepsVersions() {
        check("Nirvana - Come As You Are (MTV Unplugged)", "Nirvana", "Come As You Are (MTV Unplugged)", "Nirvana")
        check("Soda Stereo - De Música Ligera (El Último Concierto)", "Soda Stereo", "De Música Ligera (El Último Concierto)", "Soda Stereo")
        check("Avicii - Levels (Skrillex Remix)", "Avicii", "Levels (Skrillex Remix)", "Avicii")
        check("Eric Clapton - Layla (Acoustic)", "Eric Clapton", "Layla (Acoustic)", "Eric Clapton")
        check("Pink Floyd - Wish You Were Here (2011 Remaster)", "Pink Floyd", "Wish You Were Here (2011 Remaster)", "Pink Floyd")
        check("Ivete Sangalo - Sorte Grande (Ao Vivo)", "Ivete Sangalo", "Sorte Grande (Ao Vivo)", "Ivete Sangalo")
        check("Coldplay - Viva La Vida (Live in São Paulo) [Official Video]", "Coldplay", "Viva La Vida (Live in São Paulo)", "Coldplay")
        check("Maná - Rayando el Sol (En Vivo) (Video Oficial)", "Maná", "Rayando el Sol (En Vivo)", "Maná")
    }

    @Test fun versionSuffixAfterDashIsNotAnArtist() {
        // Topic-style upload: no artist in the title, the dash introduces the version.
        check("Yesterday - Remastered 2009", "The Beatles - Topic", "Yesterday - Remastered 2009", "The Beatles")
        check("Mr. Brightside - Live at Wembley", "The Killers", "Mr. Brightside - Live at Wembley", "The Killers")
    }

    // ---------------------------------------------------------------- feat.

    @Test fun featNormalization() {
        assertEquals("Envolver (feat. X)", TitleCleaner.cleanTitle("Envolver (ft. X)"))
        assertEquals("Envolver (feat. X)", TitleCleaner.cleanTitle("Envolver (Ft X)"))
        assertEquals("Envolver (feat. X)", TitleCleaner.cleanTitle("Envolver (featuring X)"))
        assertEquals("Envolver (feat. X)", TitleCleaner.cleanTitle("Envolver (FEAT. X)"))
        assertEquals("Envolver (feat. X)", TitleCleaner.cleanTitle("Envolver feat. X"))
        assertEquals("Dona Maria (feat. Jorge)", TitleCleaner.cleanTitle("Dona Maria (Part. Jorge)"))
        assertEquals("Daft Punk", TitleCleaner.cleanArtist("Daft Punk"))
        assertEquals("FT Island", TitleCleaner.cleanArtist("FT Island"))
        assertEquals("Featurette", TitleCleaner.cleanTitle("Featurette"))
        check("Calvin Harris ft. Rihanna - This Is What You Came For (Official Video)", "CalvinHarrisVEVO", "This Is What You Came For (feat. Rihanna)", "Calvin Harris")
        check("Thiaguinho feat. Ludmilla - Ainda Bem (Clipe Oficial)", "Thiaguinho", "Ainda Bem (feat. Ludmilla)", "Thiaguinho")
        check("Bad Bunny x Jhay Cortez - DÁKITI (Video Oficial)", "Bad Bunny", "DÁKITI", "Bad Bunny x Jhay Cortez")
    }

    // ---------------------------------------------------------------- splitting

    @Test fun splitOrder() {
        // Reversed "Title - Artist" when the uploader is the artist.
        check("Envolver - Anitta", "Anitta", "Envolver", "Anitta")
        // "Song | Artist"
        check("Envolver | Anitta", "Anitta", "Envolver", "Anitta")
        // Label channel: artist in the title.
        check("MC Kevinho - Olha a Explosão (KondZilla)", "KondZilla", "Olha a Explosão (KondZilla)", "MC Kevinho")
        assertEquals("Unofficial", TitleCleaner.cleanArtist("Unofficial"))
        check("MC Kevinho - Olha a Explosão | KondZilla", "KondZilla", "Olha a Explosão", "MC Kevinho")
        // Quoted song name.
        check("Soda Stereo - \"De Música Ligera\" (Gira Me Verás Volver)", "Soda Stereo", "De Música Ligera (Gira Me Verás Volver)", "Soda Stereo")
        check("BTS (방탄소년단) 'Dynamite' Official MV", "HYBE LABELS", "Dynamite", "BTS (방탄소년단)")
        check("BLACKPINK - ‘Shut Down’ M/V", "BLACKPINK", "Shut Down", "BLACKPINK")
    }

    @Test fun noDashKeepsUploaderAsArtist() {
        check("Persiana Americana (Official Video)", "Soda Stereo", "Persiana Americana", "Soda Stereo")
        val r = video("Video Killed the Radio Star", "The Buggles")
        assertEquals("Video Killed the Radio Star", r.title)
        assertFalse(r.ambiguousSplit)
    }

    @Test fun ambiguousSplitIsFlagged() {
        val r = video("Bohemian Rhapsody - Queen", "Classic Rock Hits")
        assertEquals("Queen", r.title)
        assertEquals("Bohemian Rhapsody", r.artist)
        assertTrue(r.ambiguousSplit)
        assertFalse(video("Queen - Bohemian Rhapsody", "Queen").ambiguousSplit)
    }

    @Test fun catalogTitlesAreNotSplit() {
        val r = catalog("Mr. Brightside - Remastered", "The Killers")
        assertEquals("Mr. Brightside - Remastered", r.title)
        assertEquals("The Killers", r.artist)
        assertEquals(catalog("Pt. 2 - Interlude", "Artist").title, "Pt. 2 - Interlude")
        assertEquals("De Música Ligera (Remasterizado 2007)", catalog("De Música Ligera (Remasterizado 2007)", "Soda Stereo - Topic").title)
        assertEquals("Soda Stereo", catalog("De Música Ligera", "Soda Stereo - Topic").artist)
    }

    // ---------------------------------------------------------------- clutter

    @Test fun emojiAndHashtags() {
        check("🔥 Anitta - Envolver 🔥 #shorts #anitta", "Anitta", "Envolver", "Anitta")
        check("Bad Bunny - Tití Me Preguntó 💔 (Video Oficial)", "Bad Bunny", "Tití Me Preguntó", "Bad Bunny")
        assertEquals("Lovely", TitleCleaner.cleanTitle("♪ Lovely ♪"))
    }

    @Test fun trailingNoiseWithoutBrackets() {
        assertEquals("Envolver", TitleCleaner.cleanTitle("Envolver Official Music Video"))
        assertEquals("Envolver", TitleCleaner.cleanTitle("Envolver HD"))
        assertEquals("Envolver", TitleCleaner.cleanTitle("Envolver - Video Oficial"))
        // A single word that could be part of the title stays.
        assertEquals("Home Video", TitleCleaner.cleanTitle("Home Video"))
        assertEquals("Make It Official", TitleCleaner.cleanTitle("Make It Official"))
    }

    @Test fun prodCreditsAndTranslationTags() {
        assertEquals("Callaíta", TitleCleaner.cleanTitle("Callaíta (Prod. by Tainy)"))
        assertEquals("Despacito", TitleCleaner.cleanTitle("Despacito (Legendado)"))
        assertEquals("Despacito", TitleCleaner.cleanTitle("Despacito (Tradução)"))
        assertEquals("Hello", TitleCleaner.cleanTitle("Hello (Sub. Español)"))
        assertEquals("Hello (Spanish Version)", TitleCleaner.cleanTitle("Hello (Spanish Version)"))
    }

    // ---------------------------------------------------------------- artist names

    @Test fun artistCleanup() {
        assertEquals("Soda Stereo", TitleCleaner.cleanArtist("Soda Stereo - Topic"))
        assertEquals("Soda Stereo", TitleCleaner.cleanArtist("Soda Stereo - Tema"))
        assertEquals("Anitta", TitleCleaner.cleanArtist("AnittaVEVO"))
        assertEquals("Soda Stereo", TitleCleaner.cleanArtist("SodaStereoVEVO"))
        assertEquals("Queen", TitleCleaner.cleanArtist("Queen Official"))
        assertEquals("Soda Stereo", TitleCleaner.cleanArtist("Soda Stereo Oficial"))
        assertEquals("Marília Mendonça", TitleCleaner.cleanArtist("Marília Mendonça"))
        assertEquals("Vevo", TitleCleaner.cleanArtist("Vevo"))
        assertTrue(TitleCleaner.isTopicChannel("Soda Stereo - Topic"))
        assertFalse(TitleCleaner.isTopicChannel("Soda Stereo"))
    }

    // ---------------------------------------------------------------- matching helpers

    @Test fun baseTitleAndVersions() {
        assertEquals("De Música Ligera", TitleCleaner.baseTitle("De Música Ligera (Remasterizado 2007)"))
        assertEquals("Yesterday", TitleCleaner.baseTitle("Yesterday - Remastered 2009"))
        assertEquals("Envolver", TitleCleaner.baseTitle("Envolver (feat. X)"))
        assertEquals("This Is What You Came For", TitleCleaner.baseTitle("This Is What You Came For feat. Rihanna"))
        assertEquals(setOf(TitleCleaner.Version.LIVE), TitleCleaner.versions("De Música Ligera (En Vivo)"))
        assertEquals(setOf(TitleCleaner.Version.LIVE), TitleCleaner.versions("Sorte Grande (Ao Vivo)"))
        assertTrue(TitleCleaner.Version.REMIX in TitleCleaner.versions("Envolver Remix"))
        assertTrue(TitleCleaner.versions("De Música Ligera (Remasterizado 2007)").isEmpty())
        assertEquals("Bad Bunny", TitleCleaner.primaryArtist("Bad Bunny & Jhayco"))
        assertEquals("Anitta", TitleCleaner.primaryArtist("Anitta feat. Pedro Sampaio"))
        assertEquals("J Balvin", TitleCleaner.primaryArtist("J Balvin, Dua Lipa, Bad Bunny & Tainy"))
    }

    @Test fun noiseClassifier() {
        listOf("Official Video", "Video Oficial", "Vídeo Oficial", "Clipe Oficial", "Audio Oficial", "Áudio Oficial",
            "Official Music Video", "Lyric Video", "Lyrics", "Letra", "Visualizer", "HD", "4K", "Official Audio",
            "Videoclip Oficial", "Letra/Lyrics", "Official Video 2023", "M/V", "Com Letra").forEach {
            assertTrue("noise: $it", TitleCleaner.isNoise(it))
        }
        listOf("Live", "Ao Vivo", "Remix", "Acoustic", "Remastered 2011", "feat. X", "El Último Concierto", "2007").forEach {
            assertFalse("not noise: $it", TitleCleaner.isNoise(it))
        }
    }
}
