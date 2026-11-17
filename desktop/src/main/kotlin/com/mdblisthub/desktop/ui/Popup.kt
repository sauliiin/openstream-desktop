package com.mdblisthub.desktop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties

/** `androidx.compose.ui.window.Popup` with the TV scaling and remote keys — see [Dialog]. */
@Composable
fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.ui.window.Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            BackHandler(enabled = properties.dismissOnBackPress && onDismissRequest != null) {
                onDismissRequest?.invoke()
            }
            Box(Modifier.remoteControl()) { content() }
        }
    }
}
