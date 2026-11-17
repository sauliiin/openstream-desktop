package com.mdblisthub.desktop.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/**
 * Focused, or under the mouse pointer.
 *
 * The TV screens style a control by its focus alone — the D-pad is the only
 * pointer a television has. On a desktop the mouse is a second one, so a
 * control lights up the same way when hovered. Visual only: what a screen
 * *does* on focus (scrolling, updating the hero) still follows real focus.
 */
@Composable
fun InteractionSource.collectIsFocusedOrHoveredAsState(): State<Boolean> {
    val focused = collectIsFocusedAsState()
    val hovered = collectIsHoveredAsState()
    return remember(focused, hovered) { derivedStateOf { focused.value || hovered.value } }
}
