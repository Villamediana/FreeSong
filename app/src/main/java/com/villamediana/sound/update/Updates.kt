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
    /** Hands the APK to Android; the answer arrives later through [Updates.onInstallStatus]. */
    fun install(apk: File)
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
     * The version the user tapped "Atualizar" on, waiting for the install permission. Android may
     * restart the app when that permission is given; the update carries on from here when it does.
     */
    val wants: String? = null,
)

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
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _sheet = MutableStateFlow(false)
    /** Whether the update sheet is open. */
    val sheet: StateFlow<Boolean> = _sheet

    private val _confirm = MutableStateFlow<Intent?>(null)
    /** Android's "Atualizar este app?" screen, waiting for the app to be on screen to open it. */
    val confirm: StateFlow<Intent?> = _confirm

    private val checking = Mutex()
    private var work: Job? = null
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
        // Back after Android restarted the app for the install permission: the same update, carried on.
        if (saved.wants == release.version) {
            _sheet.value = true
            update()
        }
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

    /** The button: permission (once), download (or the one already here), install. */
    fun update() {
        val release = _state.value.release ?: return
        if (work?.isActive == true) return
        work = scope.launch {
            if (!installer.canInstall()) {
                withContext(Dispatchers.IO) { memory.change { it.copy(wants = release.version) } }
                _state.value = UpdateState.NeedsPermission(release)
                return@launch
            }
            withContext(Dispatchers.IO) { if (memory.read().wants != null) memory.change { it.copy(wants = null) } }
            val apk = try {
                withContext(Dispatchers.IO) { ready(release) ?: download(release) }
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(release, e.reason, e.message)
                return@launch
            }
            val info = withContext(Dispatchers.IO) { runCatching { installer.inspect(apk) }.getOrNull() }
            if (info == null || info.packageName != current.packageName || info.versionCode <= current.versionCode) {
                apk.delete()
                _state.value = UpdateState.Failed(release, UpdateFailure.PACKAGE, info?.let { "${it.packageName} ${it.versionCode}" })
                return@launch
            }
            _state.value = UpdateState.Installing(release)
            memory.change { it.copy(installing = release.version) }
            try {
                withContext(Dispatchers.IO) { installer.install(apk) }
            } catch (e: Exception) {
                memory.change { it.copy(installing = null) }
                _state.value = UpdateState.Failed(release, if (e is IOException) UpdateFailure.STORAGE else UpdateFailure.INSTALL, e.message)
            }
        }
    }

    /** Back on screen (from the system settings): with the permission given, the update carries on. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && installer.canInstall()) update()
    }

    /** Android's answer to the install. */
    fun onInstallStatus(result: InstallResult, message: String?, confirmation: Intent?) {
        val release = _state.value.release
        when (result) {
            InstallResult.NEEDS_CONFIRMATION -> _confirm.value = confirmation
            // The new version replaces this process; nothing left to do here.
            InstallResult.SUCCESS -> Unit
            InstallResult.CANCELLED -> {
                memory.change { it.copy(installing = null) }
                if (release != null) _state.value = UpdateState.Available(release)
            }
            else -> {
                memory.change { it.copy(installing = null) }
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
     * Called by the new version right after Android replaced the app: the version it now is, when
     * the update came from here (the "atualizado" notification); null otherwise. Clears the APK.
     */
    fun afterUpdate(): String? {
        val saved = memory.read()
        val mine = saved.installing != null && Versions.compare(saved.installing, currentVersion) == 0
        if (saved.installing != null) memory.change { it.copy(installing = null) }
        clearApks()
        return if (mine) currentVersion else null
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

    private suspend fun download(release: AppRelease): File {
        apkDir.mkdirs()
        apkDir.listFiles()?.forEach { it.delete() }
        val target = apkFile(release)
        var shown = -1
        downloader.download(release, target) { fraction ->
            val pct = (fraction * 100).toInt()
            if (pct != shown) {
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
