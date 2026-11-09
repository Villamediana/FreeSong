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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeRight
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
    @org.junit.Before
    fun blockPlaybackBinding() = com.villamediana.sound.testing.blockMediaSessionBinding()
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
                FriendRow(FriendLine(ana, shared(3, "uana", SharedSong.IN, "Garota de Ipanema", "Tom Jobim", seen = false), 1), onChat = { opened = "ana" }, onProfile = { opened = "ana-page" })
                FriendRow(FriendLine(bruno, shared(2, "ubru", SharedSong.OUT, "Vapor Barato", "O Rappa"), 0, typing = true), onChat = {}, onProfile = {})
            }
        }
        compose.onNodeWithText("@miguel").assertExists()
        compose.onNodeWithText("Garota de Ipanema · Tom Jobim").assertExists()
        // Bruno is writing now: that shows instead of the last message.
        compose.onNodeWithText("escrevendo…").assertExists()
        compose.onNodeWithText("3 na jam").assertExists()
        // The unread count sits on the conversation's button.
        compose.onNodeWithText("1").assertExists()
        shot("social-friends")
        // The line opens the conversation; the person button, the page.
        compose.onNodeWithText("Ana Lima").performClick()
        assertEquals("ana", opened)
        compose.onNodeWithContentDescription("Perfil de Ana Lima").performClick()
        assertEquals("ana-page", opened)
        compose.onNodeWithContentDescription("Conversa com Ana Lima").performClick()
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

    /** The pages need the navigator (back button) like the app's detail pages. */
    @Composable
    private fun PageHost(dark: Boolean = true, content: @Composable () -> Unit) {
        val controller = androidx.navigation.compose.rememberNavController()
        val navigator = androidx.compose.runtime.remember(controller) { com.villamediana.sound.ui.nav.Navigator(controller) }
        SoundTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.villamediana.sound.ui.nav.LocalNavigator provides navigator,
                com.villamediana.sound.ui.theme.LocalBottomInset provides 24.dp,
            ) { content() }
        }
    }

    private val top5 = listOf(
        song(1, "Oceano", "Djavan"), song(2, "Trem-Bala", "Ana Vilela"), song(3, "Ainda Bem", "Marisa Monte"),
        song(4, "Velha Infância", "Tribalistas"), song(5, "Sozinho", "Caetano Veloso"),
    )

    @Test
    fun yourPageIsLikeAnArtistsAndTheAtIsNotEditable() {
        var edit = 0
        compose.setContent {
            PageHost {
                ProfilePage(
                    me = me.copy(top = top5),
                    numbers = null,
                    code = "7K2Q-M9XD-4TFA-H3RB",
                    photoBusy = false,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onPhoto = {}, onEdit = { edit++ }, onMenu = {}, onCopy = {}, onNewCode = {}, onUseCode = {},
                )
            }
        }
        compose.onAllNodesWithText("Miguel").onFirst().assertExists()
        compose.onNodeWithText("@miguel", substring = true).assertExists()
        compose.onNodeWithText("120 h").assertExists()
        compose.onNodeWithText("Oceano").assertExists()
        // The @ is shown, never a field to change it.
        assertEquals(0, compose.onAllNodesWithText("Nome de usuário").fetchSemanticsNodes().size)
        shot("social-profile")
        compose.onNodeWithText("Editar nome").performClick()
        assertEquals(1, edit)
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onFirst().performScrollToNode(androidx.compose.ui.test.hasText("7K2Q-M9XD-4TFA-H3RB"))
        shot("social-profile-code")
    }

    @Test
    fun aFriendsPageOpensTheConversation() {
        var chats = 0
        compose.setContent {
            PageHost {
                FriendPage(
                    person = ana.copy(top = top5),
                    isFriend = true,
                    unread = 2,
                    relation = "friends",
                    jam = PersonJam.NONE,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onChat = { chats++ }, onJam = {}, onMenu = {}, onAdd = {},
                )
            }
        }
        compose.onAllNodesWithText("Ana Lima").onFirst().assertExists()
        compose.onNodeWithText("Amigos desde", substring = true).assertExists()
        compose.onNodeWithText("52 h").assertExists()
        // The songs swapped live in the conversation now, not on the page.
        check(compose.onAllNodesWithText("Músicas trocadas").fetchSemanticsNodes().isEmpty())
        shot("social-friend")
        compose.onNodeWithText("Conversar · 2").performClick()
        assertEquals(1, chats)
    }

    private fun text(id: Long, dir: String, text: String, at: Long, mine: String? = null, theirs: String? = null) =
        SharedSong(id = id, friendId = "uana", direction = dir, deliveryId = id, videoId = "", title = "", artist = "", at = at, seen = true, kind = SharedSong.TEXT, text = text, myReaction = mine, theirReaction = theirs)

    @Test
    fun theConversationHasTextsSongsAndReactions() {
        val day = java.time.LocalDate.now().atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val messages = listOf(
            text(1, SharedSong.IN, "Ouviu o disco novo?", day - 86_400_000L),
            text(2, SharedSong.OUT, "Ainda não! Manda uma", day - 86_400_000L + 60_000, theirs = "😂"),
            shared(3, "uana", SharedSong.IN, "Garota de Ipanema", "Tom Jobim").copy(at = day, seen = true, myReaction = "❤️"),
            text(4, SharedSong.IN, "Essa aqui é demais", day + 30_000),
            text(5, SharedSong.OUT, "Que linda 🥹\nVou ouvir no caminho", day + 120_000, theirs = "❤️", mine = "❤️"),
        )
        var sent = 0
        var played: SharedSong? = null
        var draft by androidx.compose.runtime.mutableStateOf("")
        compose.setContent {
            PageHost {
                ChatPage(
                    friend = ana,
                    messages = messages,
                    pending = emptyList(),
                    draft = draft,
                    onDraft = { draft = it },
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onSend = { sent++ },
                    onPickSong = {},
                    onPlay = { played = it },
                    onReact = { _, _ -> },
                    onOpenFriend = {},
                )
            }
        }
        compose.onNodeWithText("Hoje").assertExists()
        compose.onNodeWithText("Ontem").assertExists()
        compose.onNodeWithText("Ouviu o disco novo?").assertExists()
        compose.onNodeWithText("❤️ 2").assertExists()
        shot("social-chat")
        compose.onNodeWithText("Garota de Ipanema").performClick()
        assertEquals(3L, played?.id)
        // Nothing typed: send does nothing.
        compose.onNodeWithContentDescription("Enviar").performClick()
        assertEquals(0, sent)
        draft = "bora"
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Enviar").performClick()
        assertEquals(1, sent)
    }

    @Test
    fun anEmptyConversationInvitesTheFirstMessage() {
        var pending by androidx.compose.runtime.mutableStateOf(listOf(PendingText(1, "oi!")))
        compose.setContent {
            PageHost(dark = false) {
                ChatPage(
                    friend = ana, messages = emptyList(), pending = pending, draft = "", onDraft = {},
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onSend = {}, onPickSong = {}, onPlay = {}, onReact = { _, _ -> }, onOpenFriend = {},
                )
            }
        }
        // A text on its way shows already.
        compose.onNodeWithText("oi!").assertExists()
        pending = emptyList()
        compose.waitForIdle()
        compose.onNodeWithText("Mande um oi ou uma música.").assertExists()
        shot("social-chat-empty")
    }

    @Test
    fun dayLabelsFallWhereTheDayChangesAndARunStaysTogether() {
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 11, 9)
        val t = { d: Int, h: Int -> java.time.LocalDate.of(2026, 11, d).atTime(h, 0).toInstant(zone).toEpochMilli() }
        val rows = chatRows(
            listOf(text(1, SharedSong.IN, "a", t(8, 9)), text(2, SharedSong.IN, "b", t(8, 10)), text(3, SharedSong.OUT, "c", t(9, 9)), text(4, SharedSong.OUT, "d", t(9, 9))),
            zone, today,
        )
        assertEquals(listOf("day", "text", "text", "day", "text", "text"), rows.map { it.type })
        assertEquals(listOf(true, false, true, false), rows.filterIsInstance<ChatRow.Message>().map { it.joined })
    }

    @Test
    fun someoneNotYetAFriend() {
        compose.setContent {
            PageHost(dark = false) {
                FriendPage(
                    person = carla, isFriend = false, unread = 0, relation = null, jam = PersonJam.NONE,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onChat = {}, onJam = {}, onMenu = {}, onAdd = {},
                )
            }
        }
        compose.onNodeWithText("Adicionar").assertExists()
        compose.onNodeWithText("Vocês ainda não são amigos.").assertExists()
        shot("social-stranger")
    }

    @Test
    fun framingAPhotoInTheCircle() {
        var applied: androidx.compose.ui.geometry.Rect? = null
        val photo = android.graphics.Bitmap.createBitmap(900, 600, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val c = android.graphics.Canvas(this)
            c.drawColor(android.graphics.Color.rgb(40, 120, 110))
            c.drawCircle(450f, 300f, 220f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(230, 180, 140) })
        }
        compose.setContent {
            PageHost { PhotoCropContent(photo.asImageBitmap(), onCancel = {}, onApply = { applied = it }) }
        }
        compose.onNodeWithText("Ajustar foto").assertExists()
        shot("social-photo-crop")
        compose.onNodeWithText("Aplicar").performClick()
        val cut = applied!!
        // Untouched: the middle square of the photo.
        assertEquals(cut.width, cut.height, 0.5f)
        assertEquals(150f, cut.left, 1f)
        assertEquals(600f, cut.width, 1f)
    }

    @Test
    fun aJamInvite() {
        compose.setContent {
            Page {
                InviteLine(JamInvite(Jam("j", "uana"), ana), onJoin = {}, onDecline = {})
            }
        }
        compose.onNodeWithText("Ana Lima te chamou pra uma jam").assertExists()
    }

    @Test
    fun yourMonthLivesInYourCardAndYourArtistsAndRhythmFollow() {
        val numbers = com.villamediana.sound.social.NumPeriod(
            minutes = 2_412, songs = 212, artists = 74, hour = 22,
            weekdays = listOf(300L, 240L, 360L, 300L, 540L, 420L, 180L),
            top = listOf(
                com.villamediana.sound.social.NumArtist("Maná", 500),
                com.villamediana.sound.social.NumArtist("Paramore", 350),
                com.villamediana.sound.social.NumArtist("Djavan", 250),
            ),
        )
        compose.setContent {
            PageHost {
                ProfilePage(
                    me = me.copy(top = top5),
                    numbers = numbers,
                    code = "7K2Q-M9XD-4TFA-H3RB",
                    photoBusy = false,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onPhoto = {}, onEdit = {}, onMenu = {}, onCopy = {}, onNewCode = {}, onUseCode = {},
                )
            }
        }
        // The month in the card, under the hours.
        compose.onAllNodesWithText("ESTE MÊS").onFirst().assertExists()
        compose.onNodeWithText(java.text.NumberFormat.getIntegerInstance().format(2_412)).assertExists()
        compose.onNodeWithText("212").assertExists()
        // No more Mês/Ano switch and no "Seus números" block (Miguel).
        check(compose.onAllNodesWithText("Ano").fetchSemanticsNodes().isEmpty())
        check(compose.onAllNodesWithText("Seus números").fetchSemanticsNodes().isEmpty())
        shot("social-profile-numbers")
        val list = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onFirst()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Seus artistas"))
        compose.onNodeWithText("Maná").assertExists()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Você é da noite"))
        compose.onNodeWithText("O som sobe mais às 22h").assertExists()
        shot("social-profile-numbers-2")
    }

    @Test
    fun anEmptyMonthSaysSoInTheCard() {
        compose.setContent {
            PageHost {
                ProfilePage(
                    me = me,
                    numbers = com.villamediana.sound.social.NumPeriod(),
                    code = null,
                    photoBusy = false,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onPhoto = {}, onEdit = {}, onMenu = {}, onCopy = {}, onNewCode = {}, onUseCode = {},
                )
            }
        }
        compose.onNodeWithText("Ouça umas músicas e seus números do mês aparecem aqui.").assertExists()
        check(compose.onAllNodesWithText("Seu ritmo").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFriendsPageShowsTheirNumbers() {
        val friend = me.copy(
            id = "ufriend", username = "ana_v", name = "Ana Vilela", relation = "friends", friendsSince = 1_700_000_000_000,
            numbers = com.villamediana.sound.social.Numbers(
                month = com.villamediana.sound.social.NumPeriod(minutes = 900, songs = 80, artists = 30, hour = 9, top = listOf(com.villamediana.sound.social.NumArtist("Rubel", 200))),
                year = com.villamediana.sound.social.NumPeriod(minutes = 12_000, songs = 700, artists = 150, hour = 9),
            ),
        )
        compose.setContent {
            PageHost {
                FriendPage(
                    person = friend, isFriend = true, relation = "friends", unread = 0, jam = PersonJam.NONE,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onChat = {}, onJam = {}, onMenu = {}, onAdd = {},
                )
            }
        }
        compose.onNodeWithText(java.text.NumberFormat.getIntegerInstance().format(900)).assertExists()
        shot("social-friend-numbers")
        val list = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
        list.performScrollToNode(androidx.compose.ui.test.hasText("Artistas de Ana"))
        compose.onNodeWithText("Rubel").assertExists()
        list.performScrollToNode(androidx.compose.ui.test.hasText("Ana é da manhã"))
        compose.onNodeWithText("Ritmo de Ana").assertExists()
    }

    @Test
    fun aConversationAnswersCorrectsAndSpeaks() {
        val day = java.time.LocalDate.now().atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val messages = listOf(
            text(1, SharedSong.IN, "Ouviu o disco novo?", day),
            text(2, SharedSong.OUT, "Ouvi! Que disco", day + 60_000).copy(replyTo = 1, editedAt = day + 90_000),
            SharedSong(
                id = 3, friendId = "uana", direction = SharedSong.IN, deliveryId = 3, videoId = "", title = "", artist = "", at = day + 120_000,
                seen = true, kind = SharedSong.VOICE, voiceMs = 12_000, voiceWave = "0369cfc963" + "0369cfc963", replyTo = 1,
            ),
        )
        var cancelled = 0
        var replied: SharedSong? = null
        var edited: SharedSong? = null
        compose.setContent {
            PageHost {
                ChatPage(
                    friend = ana, messages = messages, pending = emptyList(), draft = "", onDraft = {},
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onSend = {}, onPickSong = {}, onPlay = {}, onReact = { _, _ -> }, onOpenFriend = {},
                    typing = true,
                    replyTo = messages[0],
                    onReply = { replied = it },
                    onEdit = { edited = it },
                    onCancelCompose = { cancelled++ },
                )
            }
        }
        // Ana writing: in the header and as dots at the foot of the conversation.
        compose.onNodeWithText("escrevendo…").assertExists()
        // The answer carries the message it answers; the corrected text says so.
        assertEquals(4, compose.onAllNodesWithText("Ouviu o disco novo?").fetchSemanticsNodes().size)
        compose.onNodeWithText("editada", substring = true).assertExists()
        // The voice message: its length; the quotes say who said what they answer.
        compose.onNodeWithText("0:12").assertExists()
        assertEquals(2, compose.onAllNodesWithText("Ana").fetchSemanticsNodes().size)
        // Answering Ana now: the bar over the box, with a way out.
        compose.onNodeWithText("Respondendo Ana").assertExists()
        shot("social-chat-reply")
        compose.onNodeWithContentDescription("compose-cancel").performClick()
        assertEquals(1, cancelled)
        // Hold your own text: answer it, or correct it.
        compose.onNodeWithText("Ouvi! Que disco").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Editar").performClick()
        assertEquals(2L, edited?.id)
        // Drag a message to the right: it is answered.
        compose.onNodeWithText("0:12").performTouchInput { swipeRight(startX = centerX - 200f, endX = centerX + 120f) }
        compose.waitForIdle()
        assertEquals(3L, replied?.id)
    }

    @Test
    fun aFriendInYourJamSaysSo() {
        var opened = 0
        compose.setContent {
            PageHost(dark = false) {
                FriendPage(
                    person = ana, isFriend = true, unread = 0, relation = "friends", jam = PersonJam.TOGETHER,
                    snackbar = com.villamediana.sound.ui.detail.rememberDetailSnackbar(),
                    onChat = {}, onJam = {}, onOpenJam = { opened++ }, onMenu = {}, onAdd = {},
                )
            }
        }
        compose.onNodeWithText("Chamar pra jam").assertDoesNotExist()
        compose.onNodeWithText("Na jam").performClick()
        assertEquals(1, opened)
    }
}
