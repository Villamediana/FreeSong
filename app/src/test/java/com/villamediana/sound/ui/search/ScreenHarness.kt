package com.villamediana.sound.ui.search

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.rememberNavController
import com.villamediana.sound.ui.nav.LocalNavigator
import com.villamediana.sound.ui.nav.Navigator
import com.villamediana.sound.ui.theme.SoundTheme
import java.io.File

/** Hosts a screen with ViewModels built from fakes (the screens' own `viewModel {}` finds them in the store). */
class ScreenHarness(private val rule: ComposeContentTestRule) {
    val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = store
    }

    inline fun <reified VM : ViewModel> put(vm: VM, key: String? = null) {
        val provider = ViewModelProvider(store, viewModelFactory { initializer { vm } })
        if (key == null) provider[VM::class.java] else provider[key, VM::class.java]
    }

    fun show(content: @Composable () -> Unit) {
        rule.setContent {
            SoundTheme {
                val controller = rememberNavController()
                val navigator = remember(controller) { Navigator(controller) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalNavigator provides navigator) {
                    content()
                }
            }
        }
    }

    /** Best effort PNG of the screen into build/screenshots (only with native graphics). */
    fun screenshot(name: String) {
        runCatching {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    fun clear() = store.clear()
}
