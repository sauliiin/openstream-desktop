package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.staticCompositionLocalOf

/** The Android composition locals the shared screens read, backed by the desktop [Context]. */
val LocalContext = staticCompositionLocalOf<Context> { Context.app }
val LocalConfiguration = staticCompositionLocalOf { Configuration() }
val LocalResources = staticCompositionLocalOf<Resources> { Context.app.resources }
