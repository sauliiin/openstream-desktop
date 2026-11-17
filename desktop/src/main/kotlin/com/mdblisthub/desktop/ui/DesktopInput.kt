package com.mdblisthub.desktop.ui

import androidx.activity.compose.BackDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/**
 * The remote control, mapped onto a keyboard and mouse.
 *
 * * Arrows walk focus spatially, as the D-pad does. Compose for desktop only
 *   moves focus with Tab, and every TV screen expects arrows. Bubbling
 *   (`onKeyEvent`), so a component that uses an arrow itself — a row
 *   scrolling, a slider, a text cursor — still gets it first.
 * * Esc and the mouse's back button are Back.
 */
fun Modifier.remoteControl(onUnhandledBack: () -> Boolean = { false }): Modifier = composed {
    val focusManager = LocalFocusManager.current
    UnfocusedKeys.focusManager = focusManager
    UnfocusedKeys.onUnhandledBack = onUnhandledBack
    this
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Back)) {
                BackDispatcher.dispatch() || onUnhandledBack()
            } else false
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val direction = when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> return@onKeyEvent false
            }
            focusManager.moveFocus(direction)
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.button == PointerButton.Back) {
                        BackDispatcher.dispatch() || onUnhandledBack()
                    }
                }
            }
        }
}

/**
 * Keys that arrived while nothing had focus — after a navigation removed the
 * focused node, typically. Android TV handles this case itself (Back still
 * reaches the Activity; the first D-pad press puts focus back into the
 * screen), so the window does the same with whatever the scene did not use.
 */
object UnfocusedKeys {
    internal var focusManager: androidx.compose.ui.focus.FocusManager? = null
    internal var onUnhandledBack: () -> Boolean = { false }

    fun handle(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.Escape, Key.Back -> BackDispatcher.dispatch() || onUnhandledBack()
            Key.DirectionLeft -> focusManager?.moveFocus(FocusDirection.Left) ?: false
            Key.DirectionRight -> focusManager?.moveFocus(FocusDirection.Right) ?: false
            Key.DirectionUp -> focusManager?.moveFocus(FocusDirection.Up) ?: false
            Key.DirectionDown -> focusManager?.moveFocus(FocusDirection.Down) ?: false
            else -> false
        }
    }
}

@Composable
@Suppress("unused")
private fun keepImports() = Unit
