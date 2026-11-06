package com.villamediana.surco.ui.sheets

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.villamediana.surco.ui.theme.Dimens
import com.villamediana.surco.ui.theme.SurcoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Short confirmation after an action ("Adicionada à fila", "Removida · Desfazer"). Screens that own
 * a snackbar host provide one through [LocalSheetFeedback]; anywhere else it falls back to a toast
 * (the sheet is already gone when the message shows, so it cannot live inside the sheet).
 */
@Stable
interface SheetFeedback {
    fun show(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)
}

val LocalSheetFeedback = staticCompositionLocalOf<SheetFeedback?> { null }

@Composable
fun rememberSheetFeedback(): SheetFeedback {
    val provided = LocalSheetFeedback.current
    val context = LocalContext.current.applicationContext
    return provided ?: remember(context) { ToastFeedback(context) }
}

private class ToastFeedback(private val context: Context) : SheetFeedback {
    private val main = Handler(Looper.getMainLooper())
    override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

/** Snackbar-backed feedback; [scope] must outlive the sheet (the screen's scope). */
class SnackbarFeedback(private val host: SnackbarHostState, private val scope: CoroutineScope) : SheetFeedback {
    override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/** The Surco snackbar: raised warm surface, paper text, tangerine action. */
@Composable
fun SurcoSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data ->
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter - 8.dp)) {
            Snackbar(
                shape = RoundedCornerShape(14.dp),
                containerColor = SurcoColors.Surface3,
                contentColor = SurcoColors.Text,
                action = data.visuals.actionLabel?.let { label ->
                    {
                        TextButton(onClick = { data.performAction() }) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = SurcoColors.Accent)
                        }
                    }
                },
            ) {
                Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, color = SurcoColors.Text)
            }
        }
    }
}
