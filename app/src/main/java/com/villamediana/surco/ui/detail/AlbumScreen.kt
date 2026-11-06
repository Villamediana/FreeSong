package com.villamediana.surco.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.villamediana.surco.ui.nav.AlbumRoute

/** Placeholder — implemented by its module. */
@Composable
fun AlbumScreen(route: AlbumRoute) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Album") }
}
