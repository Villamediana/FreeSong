package com.villamediana.sound.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseFeedTest {
    @Test
    fun readsTheRealLatestRelease() {
        val r = ReleaseFeed.parse(UpdateFixtures.release1124)!!
        assertEquals("1.12.4", r.version)
        assertEquals("FreeSong-1.12.4.apk", r.apkName)
        assertEquals("https://github.com/Villamediana/FreeSong/releases/download/v1.12.4/FreeSong-1.12.4.apk", r.apkUrl)
        assertEquals(25_984_012L, r.sizeBytes)
        assertEquals("1a2e7f5515b2fdbcaf7feb56d7f66c7ca2ae3be8ba5d3eae3981a04485701958", r.sha256)
        assertTrue(r.notes.startsWith("Primeira versão pública."))
    }

    @Test
    fun draftsPreReleasesAndReleasesWithoutAnApkAreNotUpdates() {
        assertNull(ReleaseFeed.parse(UpdateFixtures.release("1.13.0", draft = true)))
        assertNull(ReleaseFeed.parse(UpdateFixtures.release("1.13.0", prerelease = true)))
        assertNull(ReleaseFeed.parse(UpdateFixtures.release("1.13.0", apkName = "FreeSong-1.13.0.zip")))
        assertNull(ReleaseFeed.parse(UpdateFixtures.release("nightly")))
        assertNull(ReleaseFeed.parse("not json"))
        assertNull(ReleaseFeed.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun aMissingOrStrangeDigestIsLeftOut() {
        assertNull(ReleaseFeed.parse(UpdateFixtures.release("1.13.0", digest = null))!!.sha256)
        assertNull(ReleaseFeed.parse(UpdateFixtures.release("1.13.0", digest = "md5:abc"))!!.sha256)
        val upper = "sha256:" + "AB".repeat(32)
        assertEquals("ab".repeat(32), ReleaseFeed.parse(UpdateFixtures.release("1.13.0", digest = upper))!!.sha256)
    }

    @Test
    fun theTagMayComeWithOrWithoutItsV() {
        assertEquals("1.13.0", ReleaseFeed.parse(UpdateFixtures.release("1.13.0", tag = "1.13.0"))!!.version)
        assertEquals("1.13.0", ReleaseFeed.parse(UpdateFixtures.release("1.13.0", tag = "v1.13.0"))!!.version)
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Versions.isNewer("1.13.0", "1.12.4"))
        assertTrue(Versions.isNewer("1.12.10", "1.12.9"))
        assertTrue(Versions.isNewer("2.0", "1.99.99"))
        assertTrue(Versions.isNewer("1.12.4.1", "1.12.4"))
        assertFalse(Versions.isNewer("1.12.4", "1.12.4"))
        assertFalse(Versions.isNewer("1.12.4", "1.12.4.0"))
        assertFalse(Versions.isNewer("1.9.3", "1.12.4"))
        assertFalse(Versions.isNewer("abc", "1.0.0"))
        assertFalse(Versions.isNewer("1.13.0", "dev"))
        assertEquals(0, Versions.compare("1.13.0-beta", "1.13.0"))
    }

    @Test
    fun notesBecomePlainLines() {
        val lines = ReleaseNotes.lines(
            """
            ## Novidades

            - **Atualização** sem loja: o app avisa e se instala
            * Letra com [acordes](https://example.com) mais limpos
            1. Busca `mais rápida`

            ---
            Obrigado por testar.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ReleaseNotes.Line.Heading("Novidades"),
                ReleaseNotes.Line.Bullet("Atualização sem loja: o app avisa e se instala"),
                ReleaseNotes.Line.Bullet("Letra com acordes mais limpos"),
                ReleaseNotes.Line.Bullet("Busca mais rápida"),
                ReleaseNotes.Line.Paragraph("Obrigado por testar."),
            ),
            lines,
        )
        assertTrue(ReleaseNotes.lines("").isEmpty())
        assertEquals(listOf(ReleaseNotes.Line.Paragraph("Negrito")), ReleaseNotes.lines("**Negrito**"))
    }
}
