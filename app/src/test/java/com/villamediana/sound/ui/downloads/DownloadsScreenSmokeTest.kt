package com.villamediana.sound.ui.downloads

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.villamediana.sound.R
import com.villamediana.sound.data.LibraryRepository
import com.villamediana.sound.data.db.DownloadState
import com.villamediana.sound.data.db.DownloadTask
import com.villamediana.sound.data.db.SoundDatabase
import com.villamediana.sound.ui.search.FakeDownloads
import com.villamediana.sound.ui.search.FakePlayer
import com.villamediana.sound.ui.search.ScreenHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SoundDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var harness: ScreenHarness
    private val downloads = FakeDownloads()

    @Before
    fun setUp() {
        db = SoundDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        harness = ScreenHarness(rule)
        harness.put(DownloadsViewModel(downloads, LibraryRepository(db, scope), FakePlayer()))
    }

    @After
    fun tearDown() {
        harness.clear()
        scope.cancel()
        db.close()
    }

    private fun task(id: Long, title: String, state: DownloadState, batch: String? = null, progress: Float = 0f, error: String? = null, createdAt: Long = 1_000) =
        DownloadTask(
            id = id, videoId = "v$id", url = "u$id", title = title, artist = "Soda Stereo", state = state, progress = progress,
            bytesDone = if (state == DownloadState.RUNNING) 2_300_000 else 0, bytesTotal = if (state == DownloadState.RUNNING) 4_600_000 else 0,
            batchId = batch, batchTitle = batch, targetPosition = id.toInt(), error = error, createdAt = createdAt,
        )

    @Test
    fun emptyState() {
        harness.show { DownloadsScreen() }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(context.getString(R.string.dl_empty_title))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(context.getString(R.string.dl_empty_action)).assertExists()
    }

    @Test
    fun batchesLooseSongsAndActions() {
        downloads.taskFlow.value = listOf(
            task(1, "Cuando Pase el Temblor", DownloadState.DONE, batch = "Rock nacional"),
            task(2, "Trátame Suavemente", DownloadState.RUNNING, batch = "Rock nacional", progress = 0.5f),
            task(3, "Prófugos", DownloadState.QUEUED, batch = "Rock nacional"),
            task(4, "Té para Tres", DownloadState.FAILED, error = "O YouTube recusou o download", createdAt = 500),
            task(5, "Nada Personal", DownloadState.DONE, createdAt = 400),
        )
        harness.show { DownloadsScreen() }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("Rock nacional")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Trátame Suavemente").assertExists()
        rule.onNodeWithText(context.getString(R.string.dl_retry_failed)).assertExists()
        rule.onNodeWithText(context.getString(R.string.dl_clear_done)).assertExists()
        rule.onNodeWithText(context.getString(R.string.dl_loose_title)).assertExists()
        harness.screenshot("downloads")

        // Cancel one song with its ring button.
        rule.onAllNodesWithContentDescription(context.getString(R.string.dl_cancel))[0].performClick()
        rule.waitUntil(20_000) { downloads.taskFlow.value.any { it.state == DownloadState.CANCELED } }

        // "Cancelar tudo" asks first.
        rule.onNodeWithText(context.getString(R.string.dl_cancel_all)).performClick()
        rule.onNodeWithText(context.getString(R.string.dl_cancel_all_title)).assertExists()
        harness.screenshot("downloads_cancel_all")
        rule.onNodeWithText(context.getString(R.string.dl_keep)).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText(context.getString(R.string.dl_cancel_all_title))).fetchSemanticsNodes().size)
    }
}
