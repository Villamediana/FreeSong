package com.villamediana.sound.update

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Where an update stands: the sheet and the row in Ajustes › Sobre are drawn from it. */
sealed interface UpdateState {
    /** The release this state is about, when there is one. */
    val release: AppRelease? get() = null

    /** Nothing to show (never checked, or checked quietly and there is nothing new). */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(override val release: AppRelease) : UpdateState
    /** Android still has to let FreeSong install apps (asked once, in the system settings). */
    data class NeedsPermission(override val release: AppRelease) : UpdateState
    data class Downloading(override val release: AppRelease, val fraction: Float) : UpdateState
    data class Installing(override val release: AppRelease) : UpdateState
    data class Failed(override val release: AppRelease, val reason: UpdateFailure, val detail: String? = null) : UpdateState
}

enum class UpdateFailure { NETWORK, INTEGRITY, STORAGE, PACKAGE, CONFLICT, INSTALL }

/** How Android answered the install (PackageInstaller statuses, in the app's own words). */
enum class InstallResult { NEEDS_CONFIRMATION, SUCCESS, CANCELLED, CONFLICT, STORAGE, FAILED }

/** What an APK file says it is. */
data class ApkInfo(val packageName: String, val versionCode: Long)

/** The phone's side of an install (Android's PackageInstaller in the app; a fake in the tests). */
interface ApkInstaller {
    /** Whether the user let FreeSong install apps ("Instalar apps desconhecidos"). */
    fun canInstall(): Boolean
    fun inspect(apk: File): ApkInfo?
    /**
     * Hands the APK to Android; the answer arrives later through [Updates.onInstallStatus]. [ask]:
     * Android shows its "Atualizar?" (the button's update never goes in behind the person's back).
     */
    fun install(apk: File, ask: Boolean = false)

    /** Drops an install handed to Android and not done yet (an automatic one waiting). */
    fun cancelPending() {}

    /**
     * Whether Android lets FreeSong update itself without asking: Android 12+, the install
     * permission given, and the FreeSong on the phone installed by FreeSong itself.
     */
    fun canInstallQuietly(): Boolean = false

    /**
     * Hands the APK to Android to install by itself once the app is not in use (not on screen, no
     * music): Android 14+. False when this Android can't wait for that itself.
     */
    fun installWhenIdle(apk: File): Boolean = false

    /**
     * Android's own installer for the APK: "Atualizar", its progress, then "Abrir" (the app closes
     * behind it). Null where there is none (then the APK goes to Android without that screen).
     */
    fun installScreen(apk: File): Intent? = null
}

/** What is kept between launches (filesDir/update.json). */
@Serializable
internal data class UpdateMemory(
    val etag: String? = null,
    val body: String? = null,
    val checkedAt: Long = 0L,
    /** The version the user said "Agora não" to: its sheet doesn't open by itself again. */
    val dismissed: String? = null,
    /** The version already announced in a notification. */
    val notified: String? = null,
    /** The version handed to Android: when the app comes back as it, the update went through. */
    val installing: String? = null,
    /**
     * The version the user tapped "Atualizar" on, until Android answers the install. Android may
     * close the app on the way (it restarts it when the install permission is given); the update
     * carries on by itself when the app opens again, without another tap.
     */
    val wants: String? = null,
    /** How many times [wants] carried on by itself at an opening (a cap, so it can never loop). */
    val resumed: Int = 0,
    /** The version downloaded to install by itself while the app is not in use. */
    val auto: String? = null,
    /** How [installing] went to Android: "now" (the button) or "auto" (by itself, the app not in use). */
    val mode: String? = null,
)

/** The version the new process is, right after Android replaced the app, and how it got there. */
data class Updated(val version: String, val automatic: Boolean)

internal class UpdateMemoryFile(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun read(): UpdateMemory = runCatching { json.decodeFromString(UpdateMemory.serializer(), file.readText()) }.getOrDefault(UpdateMemory())

    @Synchronized
    fun change(block: (UpdateMemory) -> UpdateMemory): UpdateMemory {
        val next = block(read())
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(UpdateMemory.serializer(), next))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        return next
    }
}

/**
 * The app updating itself outside a store: it asks GitHub for the latest release, and with one tap
 * downloads the APK (checked against GitHub's SHA-256), and hands it to Android, which only accepts
 * it over the installed app when it carries the same signature.
 *
 * Checks quietly at most every [CHECK_EVERY_MS] (app opening and a daily background check); the sheet
 * opens by itself once per version.
 */
class Updates internal constructor(
    private val scope: CoroutineScope,
    private val source: ReleaseSource,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val memory: UpdateMemoryFile,
    private val apkDir: File,
    private val current: ApkInfo,
    private val currentVersion: String,
    /** Off in debug builds: their package (".debug") is not the one the releases update. */
    val enabled: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A newer version was found: the automatic update gets ready (a worker, on Wi-Fi). */
    private val onFound: (AppRelease) -> Unit = {},
    /** The button was tapped: the automatic update stands down (its worker is called off). */
    private val onManual: () -> Unit = {},
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _sheet = MutableStateFlow(false)
    /** Whether the update sheet is open. */
    val sheet: StateFlow<Boolean> = _sheet

    /** Updates install by themselves on this phone (Android 12+, FreeSong installed by FreeSong). */
    val installsBySelf: Boolean get() = enabled && runCatching { installer.canInstallQuietly() }.getOrDefault(false)

    private val _auto = MutableStateFlow<String?>(null)
    /** The version downloaded and waiting to install by itself when the app is not in use. */
    val autoReady: StateFlow<String?> = _auto

    private val _confirm = MutableStateFlow<Intent?>(null)
    /** Android's "Atualizar este app?" screen, waiting for the app to be on screen to open it. */
    val confirm: StateFlow<Intent?> = _confirm

    private val checking = Mutex()
    private var work: Job? = null
    /** Android's installer screen was opened for the button's update (it says nothing back when backed out of). */
    @Volatile private var screenOpened = false
    /** The button was tapped in this run of the app: nothing installs by itself from here on. */
    @Volatile private var manual = false
    private val restored: Job? = if (enabled) scope.launch(Dispatchers.IO) { restore() } else null

    /** What the last answer said, without the network: an update found before is still there. */
    private fun restore() {
        val saved = memory.read()
        val release = saved.body?.let(ReleaseFeed::parse)?.takeIf { Versions.isNewer(it.version, currentVersion) }
        if (release == null) {
            clearApks()
            return
        }
        _state.compareAndSet(UpdateState.Idle, UpdateState.Available(release))
        if (saved.auto == release.version && ready(release) != null) _auto.value = release.version
    }

    /** A quiet check when the last one is older than [CHECK_EVERY_MS] (app on screen). */
    fun checkIfDue() {
        if (!enabled) return
        scope.launch {
            val saved = memory.read()
            if (saved.body == null || clock() - saved.checkedAt >= CHECK_EVERY_MS) check(manual = false)
        }
    }

    /**
     * Asks GitHub now. [manual] (Ajustes) shows "Procurando…" and the outcome; a found update opens
     * the sheet when it is [manual] or when its version wasn't turned down before.
     */
    suspend fun check(manual: Boolean): UpdateState {
        if (!enabled) return _state.value
        return checking.withLock {
            val before = _state.value
            if (before is UpdateState.Downloading || before is UpdateState.Installing) return@withLock before
            if (manual) _state.value = UpdateState.Checking
            val saved = memory.read()
            val answer = try {
                withContext(Dispatchers.IO) { source.latest(saved.etag.takeIf { saved.body != null }) }
            } catch (e: IOException) {
                // An update already found stays; only an asked-for check with nothing to show says it failed.
                _state.value = if (manual && before.release == null) UpdateState.CheckFailed else before
                return@withLock _state.value
            }
            val now = clock()
            val body = when (answer) {
                is ReleaseSource.Answer.Fresh -> {
                    memory.change { it.copy(etag = answer.etag, body = answer.body, checkedAt = now) }
                    answer.body
                }
                ReleaseSource.Answer.Unchanged -> {
                    memory.change { it.copy(checkedAt = now) }
                    saved.body
                }
            }
            val release = body?.let(ReleaseFeed::parse)?.takeIf { Versions.isNewer(it.version, currentVersion) }
            if (release == null) {
                withContext(Dispatchers.IO) { clearApks() }
                _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                return@withLock _state.value
            }
            // Same version still mid-way (permission, failure): it stays there, with the fresh details.
            val same = before.release?.version == release.version
            _state.value = when {
                same && before is UpdateState.NeedsPermission -> UpdateState.NeedsPermission(release)
                same && before is UpdateState.Failed -> before.copy(release = release)
                else -> UpdateState.Available(release)
            }
            if (manual || release.version != saved.dismissed) _sheet.value = true
            onFound(release)
            _state.value
        }
    }

    /** Ajustes' "Procurar atualização" (on the app's scope: leaving the screen doesn't cut it). */
    fun checkNow() {
        if (enabled) scope.launch { check(manual = true) }
    }

    /** The sheet from Ajustes or from the notification (on a cold start, once the saved answer is read). */
    fun openSheet() {
        if (!enabled) return
        scope.launch {
            restored?.join()
            if (_state.value.release != null) _sheet.value = true
        }
    }

    /** "Agora não" / swiped away: that version doesn't open by itself again. A download or install goes on. */
    fun closeSheet() {
        _sheet.value = false
        val s = _state.value
        val release = s.release ?: return
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return
        scope.launch(Dispatchers.IO) { memory.change { it.copy(dismissed = release.version, wants = null) } }
    }

    /**
     * The button: permission (once), download (or the one already here), install, in one go. [fresh]
     * is a tap (the carry-on count starts over); false when the app carries on by itself.
     */
    fun update(fresh: Boolean = true) {
        val release = _state.value.release ?: return
        if (work?.isActive == true) return
        manual = true
        work = scope.launch {
            // An automatic install waiting with Android would go in the moment Android's screen covers
            // the app (the app looks "not in use" then): it's called off first.
            standDownAuto()
            // Kept until Android answers the install: if the app is closed on the way, it carries on.
            withContext(Dispatchers.IO) {
                memory.change { it.copy(wants = release.version, resumed = if (fresh) 0 else it.resumed) }
            }
            if (!installer.canInstall()) {
                _state.value = UpdateState.NeedsPermission(release)
                return@launch
            }
            val apk = try {
                withContext(Dispatchers.IO) { ready(release) ?: download(release) }
            } catch (e: UpdateException) {
                fail(release, e.reason, e.message)
                return@launch
            }
            val info = withContext(Dispatchers.IO) { runCatching { installer.inspect(apk) }.getOrNull() }
            if (info == null || info.packageName != current.packageName || info.versionCode <= current.versionCode) {
                apk.delete()
                fail(release, UpdateFailure.PACKAGE, info?.let { "${it.packageName} ${it.versionCode}" })
                return@launch
            }
            _state.value = UpdateState.Installing(release)
            memory.change { it.copy(installing = release.version, mode = MODE_NOW) }
            // Android's own installer: "Atualizar", then "Abrir" when it's done — the app closes behind it.
            val screen = runCatching { installer.installScreen(apk) }.getOrNull()
            if (screen != null) {
                screenOpened = true
                _confirm.value = screen
                return@launch
            }
            askAndroid(release, apk)
        }
    }

    /** The button's update without Android's installer screen: its "Atualizar?" all the same, never quietly. */
    private suspend fun askAndroid(release: AppRelease, apk: File) {
        try {
            withContext(Dispatchers.IO) { installer.install(apk, ask = true) }
        } catch (e: Exception) {
            fail(release, if (e is IOException) UpdateFailure.STORAGE else UpdateFailure.INSTALL, e.message)
        }
    }

    private suspend fun standDownAuto() {
        runCatching { onManual() }
        withContext(Dispatchers.IO) {
            runCatching { installer.cancelPending() }
            memory.change { m -> if (m.mode == MODE_AUTO) m.copy(auto = null, installing = null, mode = null) else m.copy(auto = null) }
        }
        _auto.value = null
    }

    /**
     * Android's installer screen couldn't be opened (none on this phone answers): the APK goes to
     * Android with its "Atualizar?" instead. Anything else that failed to open: a failure the sheet shows.
     */
    fun screenUnavailable(message: String?) {
        if (!screenOpened) {
            onInstallStatus(InstallResult.FAILED, message, null)
            return
        }
        screenOpened = false
        val release = _state.value.release ?: return
        scope.launch {
            val apk = withContext(Dispatchers.IO) { ready(release) }
            if (apk == null) fail(release, UpdateFailure.INSTALL, message) else askAndroid(release, apk)
        }
    }

    /**
     * The automatic update, run by a worker on Wi-Fi: the newer version is downloaded quietly
     * (nothing on screen changes) and handed to Android to install once the app is not in use
     * ([ApkInstaller.installWhenIdle], Android 14+), or kept for [onIdle] (Android 12–13). Only where
     * Android lets FreeSong update itself without asking. True when the version is ready.
     */
    suspend fun prepareAuto(): Boolean {
        if (!enabled || !installer.canInstallQuietly()) return false
        restored?.join()
        if (_state.value.release == null) check(manual = false)
        val s = _state.value
        val release = s.release ?: return false
        if (manual || s is UpdateState.Downloading || s is UpdateState.Installing || work?.isActive == true) return false
        val saved = withContext(Dispatchers.IO) { memory.read() }
        if (saved.installing == release.version) return true // already with Android
        val apk = try {
            withContext(Dispatchers.IO) { ready(release) ?: download(release, quiet = true) }
        } catch (e: UpdateException) {
            return false
        }
        val info = withContext(Dispatchers.IO) { runCatching { installer.inspect(apk) }.getOrNull() }
        if (info == null || info.packageName != current.packageName || info.versionCode <= current.versionCode) {
            apk.delete()
            return false
        }
        // The button may have been tapped during the download: then it's the button's.
        if (manual || work?.isActive == true) return false
        withContext(Dispatchers.IO) { memory.change { it.copy(auto = release.version) } }
        _auto.value = release.version
        val handed = withContext(Dispatchers.IO) { runCatching { installer.installWhenIdle(apk) }.getOrDefault(false) }
        if (handed) withContext(Dispatchers.IO) { memory.change { it.copy(installing = release.version, mode = MODE_AUTO) } }
        return true
    }

    /**
     * The app is not in use (in the background, no music): a version ready to install by itself
     * goes in now — for the Androids that can't wait for that themselves (12–13). True when handed.
     */
    suspend fun onIdle(): Boolean {
        if (!enabled || !installer.canInstallQuietly()) return false
        restored?.join()
        val release = _state.value.release ?: return false
        if (manual || work?.isActive == true) return false
        val saved = withContext(Dispatchers.IO) { memory.read() }
        if (saved.auto != release.version || saved.installing == release.version) return false
        val apk = withContext(Dispatchers.IO) { ready(release) } ?: return false
        withContext(Dispatchers.IO) { memory.change { it.copy(installing = release.version, mode = MODE_AUTO) } }
        return try {
            withContext(Dispatchers.IO) { installer.install(apk) }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { memory.change { it.copy(installing = null, mode = null) } }
            false
        }
    }

    /** A failure the sheet shows; nothing carries on by itself after it (the button tries again). */
    private fun fail(release: AppRelease, reason: UpdateFailure, detail: String?) {
        memory.change { it.copy(installing = null, wants = null) }
        _state.value = UpdateState.Failed(release, reason, detail)
    }

    /**
     * The app on screen. Back from the system settings with the permission given, the update
     * carries on; and when Android closed the app mid-way (it restarts it for the install
     * permission, or closed it during the download or the "Instalar?" screen), the same update
     * carries on by itself, a few times at most. Only on screen: an install never starts while
     * the app runs in the background (it would cut the music).
     */
    fun onResume() {
        if (!enabled) return
        scope.launch {
            restored?.join()
            val s = _state.value
            val release = s.release ?: return@launch
            if (work?.isActive == true) return@launch
            when (s) {
                // Back on screen, still the old version, Android's installer gone: the person backed
                // out of it ("Cancelar", back). The button is there again; nothing carries on by itself.
                is UpdateState.Installing -> if (screenOpened && _confirm.value == null) {
                    screenOpened = false
                    withContext(Dispatchers.IO) { memory.change { it.copy(installing = null, wants = null, mode = null) } }
                    _state.value = UpdateState.Available(release)
                }
                is UpdateState.NeedsPermission -> if (installer.canInstall()) update()
                is UpdateState.Available -> {
                    val saved = withContext(Dispatchers.IO) { memory.read() }
                    if (saved.wants == release.version && saved.resumed < MAX_RESUMES) {
                        withContext(Dispatchers.IO) { memory.change { it.copy(resumed = it.resumed + 1) } }
                        _sheet.value = true
                        update(fresh = false)
                    }
                }
                else -> Unit
            }
        }
    }

    /** Android's answer to the install. */
    fun onInstallStatus(result: InstallResult, message: String?, confirmation: Intent?) {
        val release = _state.value.release
        // An automatic install that didn't go (timed out, Android wants a "yes" after all): quietly
        // back to waiting — no "Instalar?" popping up, no failure on the sheet. The button still works.
        if (memory.read().mode == MODE_AUTO && result != InstallResult.SUCCESS) {
            memory.change { it.copy(installing = null, mode = null, auto = null) }
            _auto.value = null
            return
        }
        when (result) {
            InstallResult.NEEDS_CONFIRMATION -> _confirm.value = confirmation
            // The new version replaces this process; nothing left to do here.
            InstallResult.SUCCESS -> Unit
            // "Cancelar" on Android's screen: the person said no; nothing carries on by itself.
            InstallResult.CANCELLED -> {
                memory.change { it.copy(installing = null, wants = null) }
                if (release != null) _state.value = UpdateState.Available(release)
            }
            else -> {
                memory.change { it.copy(installing = null, wants = null) }
                val reason = when (result) {
                    InstallResult.CONFLICT -> UpdateFailure.CONFLICT
                    InstallResult.STORAGE -> UpdateFailure.STORAGE
                    else -> UpdateFailure.INSTALL
                }
                if (release != null) _state.value = UpdateState.Failed(release, reason, message)
            }
        }
    }

    /** The confirmation screen was opened (or can't be): it is not opened twice. */
    fun confirmShown() {
        _confirm.value = null
    }

    /**
     * Called by the new version right after Android replaced the app: the version it now is and
     * whether it came by itself (the app not in use) or from the button, when the update came from
     * here; null otherwise. Clears the APK.
     */
    fun afterUpdate(): Updated? {
        val saved = memory.read()
        val mine = saved.installing != null && Versions.compare(saved.installing, currentVersion) == 0
        if (saved.installing != null || saved.wants != null || saved.auto != null) {
            memory.change { it.copy(installing = null, wants = null, resumed = 0, auto = null, mode = null) }
        }
        _auto.value = null
        clearApks()
        return if (mine) Updated(currentVersion, automatic = saved.mode == MODE_AUTO) else null
    }

    /**
     * The update to announce in a notification (background check): once per version, and never one
     * the user already turned down in the sheet.
     */
    fun toAnnounce(): AppRelease? {
        val release = (_state.value as? UpdateState.Available)?.release ?: return null
        val saved = memory.read()
        if (release.version == saved.notified || release.version == saved.dismissed) return null
        memory.change { it.copy(notified = release.version) }
        return release
    }

    // ------------------------------------------------------------------ files

    private fun apkFile(release: AppRelease): File = File(apkDir, "FreeSong-${release.version}.apk")

    /** The APK of [release] already downloaded and intact (a second tap, or after a cancelled install). */
    private fun ready(release: AppRelease): File? {
        val file = apkFile(release).takeIf { it.isFile } ?: return null
        val intact = when {
            release.sha256 != null -> sha256(file) == release.sha256
            release.sizeBytes > 0 -> file.length() == release.sizeBytes
            else -> false
        }
        if (!intact) file.delete()
        return file.takeIf { intact }
    }

    /** [quiet]: the automatic update's download, which doesn't show on the sheet. */
    private suspend fun download(release: AppRelease, quiet: Boolean = false): File {
        apkDir.mkdirs()
        apkDir.listFiles()?.forEach { it.delete() }
        val target = apkFile(release)
        var shown = -1
        downloader.download(release, target) { fraction ->
            val pct = (fraction * 100).toInt()
            if (!quiet && pct != shown) {
                shown = pct
                _state.value = UpdateState.Downloading(release, fraction)
            }
        }
        return target
    }

    private fun clearApks() {
        apkDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val CHECK_EVERY_MS = 6L * 3_600_000
        /** Openings an update carries on by itself after one tap (then it waits for the button). */
        const val MAX_RESUMES = 3
        internal const val MODE_NOW = "now"
        internal const val MODE_AUTO = "auto"

        internal fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class UpdateException(val reason: UpdateFailure, message: String? = null) : IOException(message)
