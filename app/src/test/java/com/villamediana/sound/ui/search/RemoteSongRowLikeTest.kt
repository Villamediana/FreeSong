package com.villamediana.sound.ui.search

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.villamediana.sound.online.RemoteTrack
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The heart next to the download button of an online song. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteSongRowLikeTest {
    @get:Rule val compose = createComposeRule()

    private val song = RemoteTrack("iris1234567", "https://music.youtube.com/watch?v=iris1234567", "Iris", "Goo Goo Dolls", fromMusicCatalog = true)

    private fun setUp(showDownload: Boolean, onLike: (Boolean) -> Unit) {
        compose.setContent {
            SoundTheme {
                var liked by remember { mutableStateOf(false) }
                RemoteSongRow(
                    track = song,
                    state = ItemDownload.None,
                    onClick = {},
                    onIndicatorClick = {},
                    liked = liked,
                    onLike = {
                        liked = it
                        onLike(it)
                    },
                    showDownload = showDownload,
                )
            }
        }
    }

    @Test fun heartAndDownloadSideBySide() {
        val likes = mutableListOf<Boolean>()
        setUp(showDownload = true) { likes += it }
        compose.onNodeWithContentDescription("Baixar").assertExists()
        compose.onNodeWithContentDescription("Curtir").performClick()
        compose.onNodeWithContentDescription("Remover das curtidas").performClick()
        assertEquals(listOf(true, false), likes)
    }

    @Test fun withCurtirJaBaixaOnlyTheHeartShows() {
        setUp(showDownload = false) {}
        compose.onNodeWithContentDescription("Curtir").assertExists()
        compose.onNodeWithContentDescription("Baixar").assertDoesNotExist()
    }

    @Test fun aQueuedDownloadWaitingForTheNetworkSaysSo() {
        compose.setContent {
            SoundTheme {
                RemoteSongRow(song, ItemDownload.Queued, onClick = {}, onIndicatorClick = {}, wait = com.villamediana.sound.download.DownloadWait.NETWORK)
            }
        }
        compose.onNodeWithContentDescription("Na fila. Baixa quando conectar").assertExists()
    }
}
