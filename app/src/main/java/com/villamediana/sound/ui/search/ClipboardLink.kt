package com.villamediana.sound.ui.search

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The YouTube link currently in the clipboard, or null. Android only lets the focused app read
 * the clipboard, so it is checked whenever the window gains focus and when the clip changes while
 * Sound is in front. Clips the system already classified as "not a URL" are not even read.
 */
@Composable
fun rememberClipboardYouTubeLink(): String? {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var link by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focused, clipboard) {
        if (focused) link = readYouTubeLink(context, clipboard)
    }
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { link = readYouTubeLink(context, clipboard) }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }
    return link
}

private fun readYouTubeLink(context: Context, clipboard: ClipboardManager?): String? = runCatching {
    val description = clipboard?.primaryClipDescription ?: return null
    if (!description.hasMimeType("text/*")) return null
    if (Build.VERSION.SDK_INT >= 31 &&
        description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
        description.getConfidenceScore(TextClassifier.TYPE_URL) <= 0f
    ) {
        return null
    }
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    YouTubeLinks.find(clip.getItemAt(0).coerceToText(context)?.toString())
}.getOrNull()
