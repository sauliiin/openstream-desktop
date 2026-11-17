package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.mdblisthub.desktop.res.AndroidResources
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.Image
import org.xml.sax.InputSource

@Composable
fun stringResource(id: Int): String {
    val locale = LocalConfiguration.current.locale
    return remember(id, locale) { AndroidResources.string(id, locale) }
}

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String {
    val locale = LocalConfiguration.current.locale
    return AndroidResources.string(id, locale, *formatArgs)
}

@Composable
fun pluralStringResource(id: Int, count: Int): String {
    val locale = LocalConfiguration.current.locale
    return AndroidResources.plural(id, count, locale)
}

@Composable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String {
    val locale = LocalConfiguration.current.locale
    return AndroidResources.plural(id, count, locale, *formatArgs)
}

@Composable
fun stringArrayResource(id: Int): Array<String> {
    val locale = LocalConfiguration.current.locale
    return remember(id, locale) { AndroidResources.stringArray(id, locale) }
}

private val bitmaps = ConcurrentHashMap<Int, ImageBitmap>()

/** Decoded once per process: theme previews and logos are small and reused. */
fun imageBitmapResource(id: Int): ImageBitmap = bitmaps.getOrPut(id) {
    AndroidResources.open(id).use { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

@Composable
fun painterResource(id: Int): Painter {
    return if (AndroidResources.extension(id) == "xml") {
        val density = LocalDensity.current
        val vector = remember(id, density) {
            AndroidResources.open(id).use { loadXmlImageVector(InputSource(it), density) }
        }
        rememberVectorPainter(vector)
    } else {
        remember(id) { BitmapPainter(imageBitmapResource(id)) }
    }
}
