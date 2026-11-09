package com.villamediana.sound.ui.social

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.villamediana.sound.data.ThemeMode
import com.villamediana.sound.data.db.SharedSong
import com.villamediana.sound.social.AccountStatus
import com.villamediana.sound.social.FriendRequest
import com.villamediana.sound.social.Jam
import com.villamediana.sound.social.JamInvite
import com.villamediana.sound.social.JamState
import com.villamediana.sound.social.Profile
import com.villamediana.sound.social.SongRef
import com.villamediana.sound.ui.components.FilterPill
import com.villamediana.sound.ui.theme.Dimens
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.theme.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The social pieces as they look (SOUND_SHOTS=1 writes PNGs to app/build/sound-shots/). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
class SocialUiTest {
    @get:Rule val compose = createComposeRule()
    private val shots = System.getenv("SOUND_SHOTS") == "1"

    private val day = 1_790_000_000_000L
    private val ana = Profile("uana", "ana.lima", "Ana Lima", since = day, listenedMs = 52 * 3_600_000L, relation = "friends", friendsSince = day)
    private val bruno = Profile("ubru", "bruno", "Bruno Costa", since = day, relation = "friends", friendsSince = day)
    private val carla = Profile("ucar", "carla_m", "Carla Mendes", since = day)
    private val me = Profile("ume", "miguel", "Miguel", since = day, listenedMs = 120 * 3_600_000L)
    private fun song(n: Int, title: String, artist: String) = SongRef("vid${n.toString().padStart(8, '0')}", title, artist)
    private fun shared(id: Long, friend: String, dir: String, title: String, artist: String, seen: Boolean = true) =
        SharedSong(id = id, friendId = friend, direction = dir, deliveryId = id, videoId = "vid$id", title = title, artist = artist, at = day + id, seen = seen)

    private fun shot(name: String) {
        if (!shots) return
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Page(dark: Boolean = true, content: @Composable () -> Unit) {
        SoundTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            Column(Modifier.fillMaxWidth().background(SoundColors.Ink).padding(vertical = 16.dp)) { content() }
        }
    }

    @Test
    fun theFriendsPage() {
        var opened = ""
        compose.setContent {
            Page {
                Text("Amigos", style = MaterialTheme.typography.displaySmall, color = SoundColors.Text, modifier = Modifier.padding(horizontal = Dimens.Gutter).padding(bottom = 18.dp))
                MeHeader(AccountStatus.READY, me, onEdit = { opened = "edit" }, onCreate = {}, onRetry = {})
                Spacer(Modifier.height(18.dp))
                JamCard(
                    Jam("j", "ume", members = listOf(me, ana, bruno), state = JamState(listOf(song(1, "Oceano", "Djavan")), 0, 0, true)),
                    onOpen = {},
                    modifier = Modifier.padding(horizontal = Dimens.Gutter),
                )
                Row(Modifier.padding(horizontal = Dimens.Gutter).padding(top = 22.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Amigos · 1", selected = true, onClick = {})
                    FilterPill("Pessoas", selected = false, onClick = {})
                }
                RequestRow(FriendRequest(1, day, carla), onAccept = {}, onDecline = {}, onOpen = {})
                FriendRow(FriendLine(ana, shared(3, "uana", SharedSong.IN, "Garota de Ipanema", "Tom Jobim", seen = false), 1), onOpen = { opened = "ana" })
                FriendRow(FriendLine(bruno, shared(2, "ubru", SharedSong.OUT, "Vapor Barato", "O Rappa"), 0), onOpen = {})
            }
        }
        compose.onNodeWithText("@miguel").assertExists()
        compose.onNodeWithText("Garota de Ipanema · Tom Jobim").assertExists()
        compose.onNodeWithText("Você: Vapor Barato").assertExists()
        compose.onNodeWithText("3 na jam").assertExists()
        shot("social-friends")
        compose.onNodeWithText("Ana Lima").performClick()
        assertEquals("ana", opened)
    }

    @Test
    fun thePeopleList() {
        var added = 0
        compose.setContent {
            Page(dark = false) {
                SearchField("", {})
                PersonRow(carla, onAdd = { added++ }, onOpen = {})
                PersonRow(carla.copy(id = "u2", name = "Diego Alves", username = "diego", relation = "outgoing"), onAdd = {}, onOpen = {})
                PersonRow(carla.copy(id = "u3", name = "Elisa Rocha", username = "elisa", relation = "incoming", requestId = 4), onAdd = {}, onOpen = {})
                PersonRow(ana, onAdd = {}, onOpen = {})
            }
        }
        compose.onNodeWithText("Pedido enviado").assertExists()
        compose.onNodeWithText("Aceitar").assertExists()
        shot("social-people")
        compose.onNodeWithText("Adicionar").performClick()
        assertEquals(1, added)
    }

    @Test
    fun theProfileStatesWhileThereIsNoProfile() {
        compose.setContent {
            Page {
                MeHeader(AccountStatus.LOADING, null, {}, {}, {})
                MeHeader(AccountStatus.NONE, null, {}, {}, {})
                MeHeader(AccountStatus.OPTED_OUT, null, {}, {}, {})
            }
        }
        compose.onNodeWithText("Criando seu perfil…").assertExists()
        compose.onNodeWithText("Tentar de novo").assertExists()
        compose.onNodeWithText("Criar perfil").assertExists()
        shot("social-states")
    }

    @Test
    fun aFriendsSongsAndTheJamInvite() {
        compose.setContent {
            Page {
                StatsBlock(ana.copy(top = listOf(song(1, "Oceano", "Djavan"), song(2, "Trem-Bala", "Ana Vilela"), song(3, "Ainda Bem", "Marisa Monte"))))
                Spacer(Modifier.height(20.dp))
                SongBubble(shared(1, "uana", SharedSong.IN, "Garota de Ipanema", "Tom Jobim"), onPlay = {})
                SongBubble(shared(2, "uana", SharedSong.OUT, "Vapor Barato", "O Rappa"), onPlay = {})
                Spacer(Modifier.height(12.dp))
                InviteLine(JamInvite(Jam("j", "uana"), ana), onJoin = {}, onDecline = {})
            }
        }
        compose.onNodeWithText("52 h ouvidas").assertExists()
        compose.onNodeWithText("Ana Lima te chamou pra uma jam").assertExists()
        shot("social-friend-songs")
    }
}
