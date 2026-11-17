package androidx.compose.ui.text.font

import com.mdblisthub.desktop.res.AndroidResources

/** `Font(R.font.x)` from Android, loading the file the id points at. */
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    androidx.compose.ui.text.platform.Font(
        identity = "res-$resId",
        data = AndroidResources.open(resId).use { it.readBytes() },
        weight = weight,
        style = style,
    )
