package com.mdblisthub.desktop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties

/**
 * `androidx.compose.ui.window.Dialog` for the TV screens on desktop.
 *
 * A dialog is its own composition root, so it neither inherits the TV canvas
 * scaling nor the arrow-key focus walking of the main content. This re-applies
 * both, so a dialog looks and navigates like it does on the TV.
 */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalDensity provides density) {
            // Registered before the content's own handlers, so those still
            // win; otherwise Esc dismisses this dialog rather than reaching
            // the screen underneath it.
            BackHandler(enabled = properties.dismissOnBackPress) { onDismissRequest() }
            Box(Modifier.remoteControl()) { content() }
        }
    }
}
