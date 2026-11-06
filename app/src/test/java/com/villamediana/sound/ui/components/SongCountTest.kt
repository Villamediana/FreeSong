package com.villamediana.sound.ui.components

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.R
import com.villamediana.sound.ui.settings.labelQuantity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Portuguese plural rules put 0 in "one": counts and labels must never read "0 música". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongCountTest {

    private val res: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    @Config(qualifiers = "pt-rBR")
    fun portugueseZeroIsNotSingular() {
        // The bug this guards against: the plural alone picks "one" for 0.
        assertEquals("0 música", res.getQuantityString(R.plurals.n_songs, 0, 0))
        assertEquals("Nenhuma música", songCount(res, 0))
        assertEquals("1 música", songCount(res, 1))
        assertEquals("2 músicas", songCount(res, 2))
        assertEquals("músicas", res.getQuantityString(R.plurals.home_settings_stat_songs, labelQuantity(0)))
        assertEquals("álbuns", res.getQuantityString(R.plurals.home_settings_stat_albums, labelQuantity(0)))
        assertEquals("artista", res.getQuantityString(R.plurals.home_settings_stat_artists, labelQuantity(1)))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishCounts() {
        assertEquals("Ninguna canción", songCount(res, 0))
        assertEquals("1 canción", songCount(res, 1))
        assertEquals("7 canciones", songCount(res, 7))
        assertEquals("canciones", res.getQuantityString(R.plurals.home_settings_stat_songs, labelQuantity(0)))
    }
}
