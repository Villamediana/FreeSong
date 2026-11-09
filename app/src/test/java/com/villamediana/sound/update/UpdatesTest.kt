package com.villamediana.sound.update

import com.villamediana.sound.update.UpdateFixtures.FakeGitHub
import com.villamediana.sound.update.UpdateFixtures.FakeGitHub.Answer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UpdatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apkBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private var latest: String = UpdateFixtures.releaseOf("1.13.0", apkBytes)
    private var apk: ByteArray = apkBytes
    private var etag = "\"v1\""
    private var down = false
    private lateinit var github: FakeGitHub
    private lateinit var installer: FakeInstaller
    private lateinit var memory: UpdateMemoryFile
    private lateinit var apkDir: File
    private var now = 1_000_000_000L

    private class FakeInstaller : ApkInstaller {
        var allowed = true
        /** Android 12+ and FreeSong installed by FreeSong: no question asked. */
        var quiet = false
        /** Android 14+: Android waits for the app to be out of use by itself. */
        var waitsBySelf = false
        var info: (File) -> ApkInfo? = { ApkInfo("com.villamediana.sound", 59) }
        val installed = mutableListOf<ByteArray>()
        val whenIdle = mutableListOf<ByteArray>()
        var throwOnInstall: Exception? = null
        override fun canInstall() = allowed
        override fun inspect(apk: File) = info(apk)
        override fun install(apk: File) {
            throwOnInstall?.let { throw it }
            synchronized(installed) { installed += apk.readBytes() }
        }
        override fun canInstallQuietly() = allowed && quiet
        override fun installWhenIdle(apk: File): Boolean {
            if (!waitsBySelf) return false
            synchronized(whenIdle) { whenIdle += apk.readBytes() }
            return true
        }
    }

    private val found = mutableListOf<String>()

    @Before
    fun setUp() {
        github = FakeGitHub { r -> answer(r) }
        installer = FakeInstaller()
        memory = UpdateMemoryFile(File(tmp.root, "files/update.json"))
        apkDir = File(tmp.root, "cache/update")
    }

    @After
    fun tearDown() = scope.cancel()

    private fun answer(r: Request): Answer {
        if (down) throw IOException("offline")
        return if (r.url.toString() == ReleaseFeed.LATEST_URL) {
            if (r.header("If-None-Match") == etag) Answer(304) else Answer(200, latest.toByteArray(), etag)
        } else {
            Answer(200, apk)
        }
    }

    private fun updates(version: String = "1.12.4", code: Long = 58, enabled: Boolean = true) = Updates(
        scope = scope,
        source = ReleaseSource(github.client, "test"),
        downloader = ApkDownloader(github.client, "test"),
        installer = installer,
        memory = memory,
        apkDir = apkDir,
        current = ApkInfo("com.villamediana.sound", code),
        currentVersion = version,
        enabled = enabled,
        clock = { now },
        onFound = { synchronized(found) { found += it.version } },
        noticeMs = 0,
    )

    private fun apiCalls() = github.count { it.url.toString() == ReleaseFeed.LATEST_URL }
    private fun apkCalls() = github.count { it.url.toString().startsWith(UpdateFixtures.APK_HOST) }

    private fun Updates.awaitState(pred: (UpdateState) -> Boolean): UpdateState =
        runBlocking { withTimeout(10_000) { state.first(pred) } }

    @Test
    fun aNewerReleaseIsFoundAndItsSheetOpens() = runBlocking {
        val u = updates()
        val s = u.check(manual = false)
        assertTrue(s is UpdateState.Available)
        assertEquals("1.13.0", s.release!!.version)
        assertTrue(u.sheet.value)
        val sent = github.requests.first()
        assertEquals("application/vnd.github+json", sent.header("Accept"))
        assertEquals("test", sent.header("User-Agent"))
    }

    @Test
    fun theSameVersionIsUpToDateQuietlyOrOutLoud() = runBlocking {
        latest = UpdateFixtures.releaseOf("1.12.4", apkBytes)
        val u = updates()
        assertEquals(UpdateState.Idle, u.check(manual = false))
        assertEquals(UpdateState.UpToDate, u.check(manual = true))
        assertFalse(u.sheet.value)
        // An older release on GitHub is not an update either.
        latest = UpdateFixtures.releaseOf("1.9.0", apkBytes)
        etag = "\"v2\""
        assertEquals(UpdateState.UpToDate, u.check(manual = true))
    }

    @Test
    fun noUpdatesInDebugBuilds() = runBlocking {
        val u = updates(enabled = false)
        assertEquals(UpdateState.Idle, u.check(manual = true))
        u.checkIfDue()
        assertEquals(0, apiCalls())
    }

    @Test
    fun theEtagMakesAnUnchangedAnswerFree() = runBlocking {
        val u = updates()
        u.check(manual = false)
        u.check(manual = false)
        assertEquals(2, apiCalls())
        assertEquals("\"v1\"", github.requests.last().header("If-None-Match"))
        // 304: the release from the first answer is still the one.
        assertEquals("1.13.0", u.state.value.release?.version)
    }

    @Test
    fun aQuietCheckRunsAtMostEverySixHours() {
        val u = updates()
        u.checkIfDue()
        u.awaitState { it is UpdateState.Available }
        assertEquals(1, apiCalls())
        now += Updates.CHECK_EVERY_MS - 1
        u.checkIfDue()
        Thread.sleep(200)
        assertEquals(1, apiCalls())
        now += 1
        u.checkIfDue()
        runBlocking { withTimeout(5_000) { while (apiCalls() < 2) kotlinx.coroutines.delay(10) } }
    }

    @Test
    fun aFailedCheckOnlyShowsWhenAskedFor() = runBlocking {
        down = true
        val u = updates()
        assertEquals(UpdateState.Idle, u.check(manual = false))
        assertEquals(UpdateState.CheckFailed, u.check(manual = true))
        // A GitHub error (rate limit, outage) is a failed check too.
        down = false
        github.handler = { Answer(403) }
        assertEquals(UpdateState.CheckFailed, u.check(manual = true))
    }

    @Test
    fun notNowKeepsTheSheetClosedForThatVersionOnly() = runBlocking {
        val u = updates()
        u.check(manual = false)
        u.closeSheet()
        Thread.sleep(100)
        u.check(manual = false)
        assertFalse(u.sheet.value)
        // Ajustes › Procurar atualização opens it anyway.
        u.check(manual = true)
        assertTrue(u.sheet.value)
        u.closeSheet()
        Thread.sleep(100)
        // A newer version than the one turned down opens by itself again.
        latest = UpdateFixtures.releaseOf("1.13.1", apkBytes)
        etag = "\"v2\""
        u.check(manual = false)
        assertTrue(u.sheet.value)
    }

    @Test
    fun updatingDownloadsChecksAndHandsTheApkToAndroid() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        val s = u.awaitState { it is UpdateState.Installing }
        assertEquals("1.13.0", s.release!!.version)
        runBlocking { withTimeout(5_000) { while (installer.installed.isEmpty()) kotlinx.coroutines.delay(10) } }
        assertTrue(installer.installed.single().contentEquals(apkBytes))
        assertEquals("1.13.0", memory.read().installing)
        assertTrue(File(apkDir, "FreeSong-1.13.0.apk").isFile)
    }

    @Test
    fun withoutPermissionItAsksAndCarriesOnWhenGiven() {
        installer.allowed = false
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.NeedsPermission }
        assertEquals(0, apkCalls())
        // Back from the settings without allowing: still asking.
        u.onResume()
        Thread.sleep(100)
        assertTrue(u.state.value is UpdateState.NeedsPermission)
        installer.allowed = true
        u.onResume()
        u.awaitState { it is UpdateState.Installing }
        // A check meanwhile doesn't throw the permission step away.
    }

    @Test
    fun whenAndroidRestartsTheAppForThePermissionTheUpdateCarriesOn() {
        installer.allowed = false
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.NeedsPermission }
        assertEquals("1.13.0", memory.read().wants)
        // Permission given in the system settings; Android killed the process; the app comes back on screen.
        installer.allowed = true
        val back = updates()
        back.onResume()
        back.awaitState { it is UpdateState.Installing }
        assertTrue(back.sheet.value)
        // Kept until Android answers the install (closed again on the way, it carries on again).
        assertEquals("1.13.0", memory.read().wants)
        // Without the permission it comes back to the same step, sheet open.
        installer.allowed = false
        memory.change { it.copy(wants = "1.13.0", installing = null, resumed = 0) }
        val again = updates()
        again.onResume()
        again.awaitState { it is UpdateState.NeedsPermission }
        assertTrue(again.sheet.value)
    }

    private fun awaitInstalls(n: Int) = runBlocking { withTimeout(5_000) { while (installer.installed.size < n) kotlinx.coroutines.delay(10) } }

    @Test
    fun closedMidWayTheUpdateCarriesOnWhenTheAppIsBackOnScreen() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Installing }
        awaitInstalls(1)
        // Android closed the app before the "Instalar?" screen was answered. The process comes back
        // in the background (music, a notification): nothing is installed from there.
        val background = updates()
        background.awaitState { it is UpdateState.Available }
        Thread.sleep(200)
        assertEquals(1, installer.installed.size)
        // On screen: straight to Android's screen again, no tap, no second download.
        background.onResume()
        background.awaitState { it is UpdateState.Installing }
        awaitInstalls(2)
        assertTrue(background.sheet.value)
        assertEquals(1, apkCalls())
    }

    @Test
    fun itCarriesOnByItselfAFewTimesAtMostAndNeverAfterANo() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        awaitInstalls(1)
        // Closed again and again on the way: it carries on MAX_RESUMES times, then waits for the button.
        repeat(Updates.MAX_RESUMES) { i ->
            val back = updates()
            back.onResume()
            awaitInstalls(i + 2)
        }
        val tired = updates()
        tired.onResume()
        Thread.sleep(300)
        assertEquals(Updates.MAX_RESUMES + 1, installer.installed.size)
        assertTrue(tired.state.value is UpdateState.Available)
        // A tap starts over; "Cancelar" on Android's screen is a no: the next opening leaves it be.
        tired.update()
        awaitInstalls(Updates.MAX_RESUMES + 2)
        tired.onInstallStatus(InstallResult.CANCELLED, null, null)
        assertNull(memory.read().wants)
        val after = updates()
        after.onResume()
        Thread.sleep(300)
        assertEquals(Updates.MAX_RESUMES + 2, installer.installed.size)
    }

    @Test
    fun aFailureDoesNotCarryOnByItself() {
        apk = ByteArray(10)
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Failed }
        assertNull(memory.read().wants)
        val back = updates()
        back.onResume()
        Thread.sleep(300)
        // No second download by itself: the button says "Tentar de novo".
        assertEquals(1, apkCalls())
        assertTrue(back.state.value is UpdateState.Available)
    }

    @Test
    fun closingTheSheetAfterAFailureAlsoTurnsTheVersionDown() {
        apk = ByteArray(10)
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Failed }
        u.closeSheet()
        Thread.sleep(100)
        assertEquals("1.13.0", memory.read().dismissed)
        runBlocking { u.check(manual = false) }
        assertFalse(u.sheet.value)
    }

    @Test
    fun closingTheSheetMidDownloadOnlyHidesIt() {
        installer.allowed = true
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Installing }
        u.closeSheet()
        Thread.sleep(100)
        assertFalse(u.sheet.value)
        assertNull(memory.read().dismissed)
    }

    @Test
    fun aCheckDuringThePermissionStepKeepsIt() = runBlocking {
        installer.allowed = false
        val u = updates()
        u.check(manual = false)
        u.update()
        u.awaitState { it is UpdateState.NeedsPermission }
        u.check(manual = true)
        assertTrue(u.state.value is UpdateState.NeedsPermission)
    }

    @Test
    fun aCorruptedDownloadIsThrownAwayAndSaysSo() {
        apk = apkBytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        val s = u.awaitState { it is UpdateState.Failed } as UpdateState.Failed
        assertEquals(UpdateFailure.INTEGRITY, s.reason)
        assertTrue(apkDir.listFiles().orEmpty().isEmpty())
        assertTrue(installer.installed.isEmpty())
        // Fixed on the server: trying again works.
        apk = apkBytes
        u.update()
        u.awaitState { it is UpdateState.Installing }
    }

    @Test
    fun aDownloadThatFailsIsANetworkFailure() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        github.handler = { r -> if (r.url.toString() == ReleaseFeed.LATEST_URL) Answer(304) else throw IOException("reset") }
        u.update()
        val s = u.awaitState { it is UpdateState.Failed } as UpdateState.Failed
        assertEquals(UpdateFailure.NETWORK, s.reason)
    }

    @Test
    fun anApkOfAnotherAppOrAnOlderBuildIsRefused() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        installer.info = { ApkInfo("com.example.other", 99) }
        u.update()
        assertEquals(UpdateFailure.PACKAGE, (u.awaitState { it is UpdateState.Failed } as UpdateState.Failed).reason)
        assertTrue(apkDir.listFiles().orEmpty().isEmpty())

        val older = updates()
        runBlocking { older.check(manual = true) }
        installer.info = { ApkInfo("com.villamediana.sound", 58) }
        older.update()
        assertEquals(UpdateFailure.PACKAGE, (older.awaitState { it is UpdateState.Failed } as UpdateState.Failed).reason)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun cancellingAndroidsScreenGoesBackAndTheNextTapSkipsTheDownload() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Installing }
        runBlocking { withTimeout(5_000) { while (installer.installed.isEmpty()) kotlinx.coroutines.delay(10) } }
        u.onInstallStatus(InstallResult.CANCELLED, null, null)
        assertTrue(u.state.value is UpdateState.Available)
        assertNull(memory.read().installing)
        u.update()
        u.awaitState { it is UpdateState.Installing }
        runBlocking { withTimeout(5_000) { while (installer.installed.size < 2) kotlinx.coroutines.delay(10) } }
        assertEquals(1, apkCalls())
    }

    @Test
    fun androidsAnswersBecomeStates() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Installing }
        u.onInstallStatus(InstallResult.CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null)
        val s = u.state.value as UpdateState.Failed
        assertEquals(UpdateFailure.CONFLICT, s.reason)
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", s.detail)
        u.onInstallStatus(InstallResult.STORAGE, null, null)
        assertEquals(UpdateFailure.STORAGE, (u.state.value as UpdateState.Failed).reason)
        u.onInstallStatus(InstallResult.FAILED, null, null)
        assertEquals(UpdateFailure.INSTALL, (u.state.value as UpdateState.Failed).reason)
    }

    @Test
    fun anInstallThatThrowsIsAFailureNotAHang() {
        installer.throwOnInstall = SecurityException("no")
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        assertEquals(UpdateFailure.INSTALL, (u.awaitState { it is UpdateState.Failed } as UpdateState.Failed).reason)
        assertNull(memory.read().installing)
    }

    @Test
    fun anUpdateFoundBeforeIsBackWithoutTheNetwork() {
        runBlocking { updates().check(manual = false) }
        down = true
        val again = updates()
        again.awaitState { it is UpdateState.Available }
        assertEquals("1.13.0", again.state.value.release?.version)
        assertEquals(1, apiCalls())
    }

    @Test
    fun theNewVersionKnowsItCameFromHereAndClearsTheApk() {
        val u = updates()
        runBlocking { u.check(manual = false) }
        u.update()
        u.awaitState { it is UpdateState.Installing }
        runBlocking { withTimeout(5_000) { while (installer.installed.isEmpty()) kotlinx.coroutines.delay(10) } }
        // Android replaced the app: the new process is 1.13.0 (from the button: it may open again).
        val after = updates(version = "1.13.0", code = 59)
        assertEquals(Updated("1.13.0", automatic = false), after.afterUpdate())
        assertNull(memory.read().installing)
        assertTrue(apkDir.listFiles().orEmpty().isEmpty())
        // Installed some other way (adb, the file manager): no "atualizado" notification.
        assertNull(after.afterUpdate())
    }

    private fun statesDuring(u: Updates, block: suspend () -> Unit): List<UpdateState> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<UpdateState>()
        val job = scope.launch { u.state.collect { seen += it } }
        runBlocking { block() }
        Thread.sleep(100)
        job.cancel()
        return seen
    }

    @Test
    fun aNewVersionGetsReadyByItselfAndAndroidInstallsItWhenTheAppIsNotInUse() {
        installer.quiet = true
        installer.waitsBySelf = true // Android 14+
        val u = updates()
        runBlocking { u.check(manual = false) }
        assertEquals(listOf("1.13.0"), found) // the worker (Wi-Fi) is called
        val seen = statesDuring(u) { assertTrue(u.prepareAuto()) }
        // Quietly: nothing on the sheet moved, nothing installed in the person's face.
        assertTrue(seen.none { it is UpdateState.Downloading || it is UpdateState.Installing })
        assertTrue(installer.installed.isEmpty())
        assertTrue(installer.whenIdle.single().contentEquals(apkBytes))
        assertEquals("1.13.0", u.autoReady.value)
        assertEquals("1.13.0", memory.read().installing)
        // Done while the app was not in use: the new version knows (only a notice, it doesn't pop up).
        assertEquals(Updated("1.13.0", automatic = true), updates(version = "1.13.0", code = 59).afterUpdate())
    }

    @Test
    fun onAndroid12And13ItGoesInWhenTheAppLeavesTheScreen() {
        installer.quiet = true
        val u = updates()
        runBlocking {
            assertTrue(u.prepareAuto()) // checks by itself too
            assertTrue(installer.whenIdle.isEmpty())
            assertEquals("1.13.0", u.autoReady.value)
            assertTrue(installer.installed.isEmpty())
            // The app went to the background, no music.
            assertTrue(u.onIdle())
            assertTrue(installer.installed.single().contentEquals(apkBytes))
            assertFalse(u.onIdle()) // once
        }
        assertEquals(1, apkCalls())
    }

    @Test
    fun whereAndroidWouldAskNothingIsDownloadedByItself() {
        installer.quiet = false // Android 11, or the FreeSong on the phone came from a file manager
        val u = updates()
        runBlocking {
            assertFalse(u.prepareAuto())
            assertFalse(u.onIdle())
        }
        assertEquals(0, apkCalls())
        assertNull(u.autoReady.value)
    }

    @Test
    fun anAutomaticInstallThatDoesntGoStaysQuiet() {
        installer.quiet = true
        installer.waitsBySelf = true
        val u = updates()
        runBlocking { u.prepareAuto() }
        // Timed out, or Android wants a "yes" after all: no failure on the sheet, no "Instalar?" popping up.
        u.onInstallStatus(InstallResult.NEEDS_CONFIRMATION, null, android.content.Intent("x"))
        assertNull(u.confirm.value)
        assertTrue(u.state.value is UpdateState.Available)
        assertNull(u.autoReady.value)
        assertNull(memory.read().installing)
        // Ready again next time.
        runBlocking { assertTrue(u.prepareAuto()) }
        assertEquals(2, installer.whenIdle.size)
        u.onInstallStatus(InstallResult.FAILED, "timeout", null)
        assertTrue(u.state.value is UpdateState.Available)
    }

    @Test
    fun theButtonInstallsNowEvenWithAnAutomaticOneWaiting() {
        installer.quiet = true
        installer.waitsBySelf = true
        val u = updates()
        runBlocking { u.prepareAuto() }
        u.update()
        u.awaitState { it is UpdateState.Installing }
        runBlocking { withTimeout(5_000) { while (installer.installed.isEmpty()) kotlinx.coroutines.delay(10) } }
        // No second download; and the new version knows it came from the button.
        assertEquals(1, apkCalls())
        assertEquals(Updated("1.13.0", automatic = false), updates(version = "1.13.0", code = 59).afterUpdate())
    }

    @Test
    fun theNotificationAnnouncesEachVersionOnceAndNeverATurnedDownOne() = runBlocking {
        val u = updates()
        u.check(manual = false)
        assertEquals("1.13.0", u.toAnnounce()?.version)
        assertNull(u.toAnnounce())
        latest = UpdateFixtures.releaseOf("1.13.1", apkBytes)
        etag = "\"v2\""
        u.check(manual = false)
        u.closeSheet()
        Thread.sleep(100)
        assertNull(u.toAnnounce())
    }

    @Test
    fun theSheetOpensFromTheNotificationOnAColdStart() {
        runBlocking { updates().check(manual = false) }
        val cold = updates()
        cold.openSheet()
        runBlocking { withTimeout(5_000) { cold.sheet.first { it } } }
    }

    @Test
    fun anUnreadableMemoryStartsClean() {
        File(tmp.root, "files").mkdirs()
        File(tmp.root, "files/update.json").writeText("{broken")
        assertEquals(UpdateMemory(), memory.read())
        memory.change { it.copy(dismissed = "1.0.0") }
        assertEquals("1.0.0", memory.read().dismissed)
    }
}
