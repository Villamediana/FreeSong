package com.villamediana.surco.ui.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.villamediana.surco.ui.nav.VideoRoute

/** Placeholder — full-screen music video (graph.video). */
@Composable
fun VideoScreen(route: VideoRoute) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Video") }
}
