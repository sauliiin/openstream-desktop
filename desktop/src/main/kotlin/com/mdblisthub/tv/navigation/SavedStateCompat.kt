package com.mdblisthub.tv.navigation

import androidx.savedstate.SavedState
import androidx.savedstate.read

/*
 * Android's `Bundle` getters on the multiplatform `SavedState` that
 * navigation-compose hands back on desktop, so the routes read the same.
 */
internal fun SavedState.getString(key: String): String? = read { if (contains(key)) getStringOrNull(key) else null }
internal fun SavedState.getInt(key: String): Int = read { if (contains(key)) getInt(key) else 0 }
internal fun SavedState.getBoolean(key: String): Boolean = read { if (contains(key)) getBoolean(key) else false }
