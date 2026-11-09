package com.villamediana.sound.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Android's side: the session, the manifest pieces, and the receiver reading PackageInstaller's answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theApkGoesIntoOneSessionForThisApp() {
        val apk = File(context.cacheDir, "FreeSong-9.9.9.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        SessionInstaller(context).install(apk)
        val sessions = context.packageManager.packageInstaller.allSessions
        assertEquals(1, sessions.size)
        assertEquals(context.packageName, sessions.single().appPackageName)
    }

    @Test
    fun aSessionLeftOpenIsAbandonedBeforeTheNextOne() {
        val apk = File(context.cacheDir, "FreeSong-9.9.9.apk").apply { writeBytes(ByteArray(1024)) }
        val installer = SessionInstaller(context)
        installer.install(apk)
        installer.install(apk)
        assertEquals(1, context.packageManager.packageInstaller.mySessions.size)
    }

    @Test
    fun permissionAndArchiveComeFromThePackageManager() {
        val installer = SessionInstaller(context)
        val pm = shadowOf(context.packageManager)
        pm.setCanRequestPackageInstalls(false)
        assertFalse(installer.canInstall())
        pm.setCanRequestPackageInstalls(true)
        assertTrue(installer.canInstall())

        val apk = File(context.cacheDir, "x.apk").apply { writeBytes(ByteArray(10)) }
        pm.setPackageArchiveInfo(apk.path, PackageInfo().apply { packageName = "com.villamediana.sound"; @Suppress("DEPRECATION") versionCode = 59 })
        assertEquals(ApkInfo("com.villamediana.sound", 59), installer.inspect(apk))
        assertNull(installer.inspect(File(context.cacheDir, "missing.apk")))
    }

    @Test
    fun theButtonsUpdateIsAndroidsOwnInstallerScreen() {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val apk = File(dir, "FreeSong-9.9.9.apk").apply { writeBytes(ByteArray(64)) }
        val installer = SessionInstaller(context)
        // No installer on the phone answers: none (the APK then goes to Android another way).
        assertNull(installer.installScreen(apk))
        @Suppress("DEPRECATION")
        val ask = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", apk),
            "application/vnd.android.package-archive",
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(
            ask,
            android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.packageinstaller"; name = "InstallStart" }
            },
        )
        val screen = installer.installScreen(apk)!!
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, screen.action)
        assertEquals("content", screen.data!!.scheme)
        assertTrue(screen.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // FreeSong stays the one that updates FreeSong.
        assertEquals(context.packageName, screen.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME))
        // Nothing asks Android for a result back: its last screen offers "Abrir".
        assertFalse(screen.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }

    @Test
    fun theManifestAsksToInstallAndListensForTheReplacedApp() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in info.requestedPermissions.orEmpty())
        val replaced = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0)
        assertTrue(replaced.any { it.activityInfo.name == UpdatedReceiver::class.java.name })
    }

    @Test
    fun theReceiverTurnsStatusesIntoResults() {
        val receiver = UpdateStatusReceiver()
        // Debug builds have the updater off, but the answers are still read the same way.
        fun send(status: Int, extra: Intent.() -> Unit = {}) = receiver.onReceive(
            context,
            Intent(UpdateStatusReceiver.ACTION).putExtra(PackageInstaller.EXTRA_STATUS, status).apply(extra),
        )
        val updates = com.villamediana.sound.SoundApplication.graph.updates
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        send(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, confirm) }
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", updates.confirm.value?.action)
        updates.confirmShown()
        assertNull(updates.confirm.value)
        // Something else than the status action is ignored.
        receiver.onReceive(context, Intent("other").putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm))
        assertNull(updates.confirm.value)
    }
}
