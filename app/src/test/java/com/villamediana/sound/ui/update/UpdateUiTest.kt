package com.villamediana.sound.ui.update

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.villamediana.sound.ui.theme.SoundColors
import com.villamediana.sound.ui.theme.SoundTheme
import com.villamediana.sound.update.ApkDownloader
import com.villamediana.sound.update.ApkInfo
import com.villamediana.sound.update.ApkInstaller
import com.villamediana.sound.update.AppRelease
import com.villamediana.sound.update.ReleaseSource
import com.villamediana.sound.update.UpdateFailure
import com.villamediana.sound.update.UpdateFixtures
import com.villamediana.sound.update.UpdateMemoryFile
import com.villamediana.sound.update.UpdateState
import com.villamediana.sound.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The update sheet in each step and the row in Ajustes. SOUND_SHOTS=1 writes PNGs to app/build/sound-shots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val shots = System.getenv("SOUND_SHOTS") == "1"

    private val release = AppRelease(
        version = "1.13.0",
        notes = "- O app avisa quando sai versão nova e se atualiza com um toque\n- Letra com acordes mais limpa\n- Correções na busca",
        apkUrl = "${UpdateFixtures.APK_HOST}/v1.13.0/FreeSong-1.13.0.apk",
        apkName = "FreeSong-1.13.0.apk",
        sizeBytes = 26_100_000,
        sha256 = null,
    )

    private fun shot(name: String) {
        if (!shots) return
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("user.dir"), "build/sound-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        SoundTheme {
            Box(Modifier.fillMaxWidth().background(SoundColors.Surface2).padding(top = 24.dp, bottom = 16.dp)) { content() }
        }
    }

    @Test
    fun availableShowsVersionNotesAndTheTwoChoices() {
        var updated = 0
        var dismissed = 0
        compose.setContent {
            Sheet { UpdateSheetBody(UpdateState.Available(release), release, onUpdate = { updated++ }, onAllow = {}, onDismiss = { dismissed++ }) }
        }
        compose.onNodeWithText("Nova versão").assertExists()
        compose.onNodeWithText("1.13.0", substring = true).assertExists()
        compose.onNodeWithText("Letra com acordes mais limpa").assertExists()
        shot("update-available")
        // The label is drawn twice (under and over the fill); a tap on it lands on the button.
        compose.onAllNodesWithText("Atualizar")[0].performClick()
        compose.onNodeWithText("Agora não").performClick()
        assertEquals(1, updated)
        assertEquals(1, dismissed)
    }

    @Test
    fun downloadingFillsTheButtonAndHidesNotNow() {
        compose.setContent { Sheet { UpdateSheetBody(UpdateState.Downloading(release, 0.42f), release, {}, {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("42%", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Agora não").assertDoesNotExist()
        shot("update-downloading")
    }

    @Test
    fun installingBreathes() {
        compose.setContent { Sheet { UpdateSheetBody(UpdateState.Installing(release), release, {}, {}, {}) } }
        compose.onAllNodesWithText("Instalando…")[0].assertExists()
        shot("update-installing")
    }

    @Test
    fun thePermissionStepSaysWhyAndOpensTheSettings() {
        var allowed = 0
        var updated = 0
        compose.setContent {
            Sheet { UpdateSheetBody(UpdateState.NeedsPermission(release), release, onUpdate = { updated++ }, onAllow = { allowed++ }, onDismiss = {}) }
        }
        compose.onNodeWithText("Permita que o FreeSong instale as próprias atualizações.").assertExists()
        shot("update-permission")
        compose.onAllNodesWithText("Permitir")[0].performClick()
        assertEquals(1, allowed)
        assertEquals(0, updated)
    }

    @Test
    fun aFailureSaysWhatHappenedAndOffersToTryAgain() {
        var updated = 0
        compose.setContent {
            Sheet { UpdateSheetBody(UpdateState.Failed(release, UpdateFailure.NETWORK, "reset"), release, onUpdate = { updated++ }, onAllow = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("A conexão caiu no meio do download.").assertExists()
        shot("update-failed")
        compose.onAllNodesWithText("Tentar de novo")[0].performClick()
        assertEquals(1, updated)
    }

    // ------------------------------------------------------------------ Ajustes › Sobre

    private fun updates(latest: String, version: String = "1.12.4"): Updates {
        val github = UpdateFixtures.FakeGitHub { r ->
            if (r.url.toString().contains("api.github.com")) UpdateFixtures.FakeGitHub.Answer(200, latest.toByteArray(), "\"e\"")
            else UpdateFixtures.FakeGitHub.Answer(404)
        }
        val installer = object : ApkInstaller {
            override fun canInstall() = true
            override fun inspect(apk: File): ApkInfo? = null
            override fun install(apk: File, ask: Boolean) = Unit
        }
        return Updates(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            source = ReleaseSource(github.client, "test"),
            downloader = ApkDownloader(github.client, "test"),
            installer = installer,
            memory = UpdateMemoryFile(File(tmp.root, "update.json")),
            apkDir = File(tmp.root, "apk"),
            current = ApkInfo("com.villamediana.sound", 58),
            currentVersion = version,
            enabled = true,
        )
    }

    @Test
    fun theRowOffersTheVersionFound() {
        val u = updates(UpdateFixtures.release("1.13.0"))
        runBlocking { u.check(manual = false) }
        compose.setContent { Sheet { UpdateRow(u) } }
        compose.onNodeWithText("Atualizar para 1.13.0").assertExists()
        shot("update-row-available")
    }

    @Test
    fun theRowChecksAndSaysWhenItIsTheLatest() {
        val u = updates(UpdateFixtures.release("1.12.4"))
        compose.setContent { Sheet { UpdateRow(u) } }
        compose.onNodeWithText("Procurar atualização").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Você está na versão mais recente").fetchSemanticsNodes().isNotEmpty() }
        shot("update-row-latest")
    }

    @Test
    fun theRowIsAbsentInDebugBuilds() {
        val off = Updates(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            source = ReleaseSource(okhttp3.OkHttpClient(), "test"),
            downloader = ApkDownloader(okhttp3.OkHttpClient(), "test"),
            installer = object : ApkInstaller {
                override fun canInstall() = true
                override fun inspect(apk: File): ApkInfo? = null
                override fun install(apk: File, ask: Boolean) = Unit
            },
            memory = UpdateMemoryFile(File(tmp.root, "off.json")),
            apkDir = File(tmp.root, "off"),
            current = ApkInfo("com.villamediana.sound.debug", 58),
            currentVersion = "1.12.4",
            enabled = false,
        )
        compose.setContent { Sheet { UpdateRow(off) } }
        compose.onNodeWithText("Procurar atualização").assertDoesNotExist()
    }
}
