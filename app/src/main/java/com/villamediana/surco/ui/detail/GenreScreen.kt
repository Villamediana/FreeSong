package com.villamediana.surco.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.villamediana.surco.ui.nav.GenreRoute

/** Placeholder — implemented by its module. */
@Composable
fun GenreScreen(route: GenreRoute) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Genre") }
}
