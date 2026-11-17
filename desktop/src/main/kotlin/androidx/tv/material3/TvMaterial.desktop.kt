package androidx.tv.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/*
 * Compose for TV's Material layer, for a desktop window.
 *
 * The TV app only uses a thin slice of `androidx.tv.material3` — Text, Icon,
 * the theme, and focus-styled Button/IconButton — and draws everything else
 * itself with foundation. This file provides exactly that slice on top of
 * Compose Multiplatform's Material 3, with one desktop addition: the mouse
 * pointer hovering a control styles it the way D-pad focus does on TV, so the
 * same visuals work for keyboard and mouse.
 */

typealias Typography = androidx.compose.material3.Typography

@Immutable
data class ColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val border: Color,
    val borderVariant: Color,
    val scrim: Color,
)

fun darkColorScheme(
    primary: Color = Color(0xFFD0BCFF),
    onPrimary: Color = Color(0xFF381E72),
    primaryContainer: Color = Color(0xFF4F378B),
    onPrimaryContainer: Color = Color(0xFFEADDFF),
    inversePrimary: Color = Color(0xFF6750A4),
    secondary: Color = Color(0xFFCCC2DC),
    onSecondary: Color = Color(0xFF332D41),
    secondaryContainer: Color = Color(0xFF4A4458),
    onSecondaryContainer: Color = Color(0xFFE8DEF8),
    tertiary: Color = Color(0xFFEFB8C8),
    onTertiary: Color = Color(0xFF492532),
    tertiaryContainer: Color = Color(0xFF633B48),
    onTertiaryContainer: Color = Color(0xFFFFD8E4),
    background: Color = Color(0xFF1C1B1F),
    onBackground: Color = Color(0xFFE6E1E5),
    surface: Color = Color(0xFF1C1B1F),
    onSurface: Color = Color(0xFFE6E1E5),
    surfaceVariant: Color = Color(0xFF49454F),
    onSurfaceVariant: Color = Color(0xFFCAC4D0),
    surfaceTint: Color = primary,
    inverseSurface: Color = Color(0xFFE6E1E5),
    inverseOnSurface: Color = Color(0xFF313033),
    error: Color = Color(0xFFF2B8B5),
    onError: Color = Color(0xFF601410),
    errorContainer: Color = Color(0xFF8C1D18),
    onErrorContainer: Color = Color(0xFFF9DEDC),
    border: Color = Color(0xFF938F99),
    borderVariant: Color = Color(0xFF49454F),
    scrim: Color = Color.Black,
) = ColorScheme(
    primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary, secondary, onSecondary,
    secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
    background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
    inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer, border,
    borderVariant, scrim,
)

private val LocalColorScheme = staticCompositionLocalOf { darkColorScheme() }
private val LocalTypography = staticCompositionLocalOf { Typography() }

object MaterialTheme {
    val colorScheme: ColorScheme
        @Composable get() = LocalColorScheme.current
    val typography: Typography
        @Composable get() = LocalTypography.current
    val shapes: androidx.compose.material3.Shapes
        @Composable get() = androidx.compose.material3.MaterialTheme.shapes
}

@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    val m3 = androidx.compose.material3.darkColorScheme(
        primary = colorScheme.primary,
        onPrimary = colorScheme.onPrimary,
        secondary = colorScheme.secondary,
        background = colorScheme.background,
        onBackground = colorScheme.onBackground,
        surface = colorScheme.surface,
        onSurface = colorScheme.onSurface,
        surfaceVariant = colorScheme.surfaceVariant,
        onSurfaceVariant = colorScheme.onSurfaceVariant,
        error = colorScheme.error,
        outline = colorScheme.border,
    )
    androidx.compose.material3.MaterialTheme(colorScheme = m3, typography = typography) {
        CompositionLocalProvider(
            LocalColorScheme provides colorScheme,
            LocalTypography provides typography,
            LocalContentColor provides colorScheme.onSurface,
            content = content,
        )
    }
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
    textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
    softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style,
)

@Composable
fun Text(
    text: androidx.compose.ui.text.AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
    textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
    softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style,
)

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)

@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = androidx.compose.material3.Icon(painter, contentDescription, modifier, tint)

// ---------------------------------------------------------------------------
// Focus-styled buttons
// ---------------------------------------------------------------------------

@Immutable
class ButtonScale internal constructor(
    val scale: Float,
    val focusedScale: Float,
    val pressedScale: Float,
    val disabledScale: Float,
)

@Immutable
class ButtonColors internal constructor(
    val containerColor: Color,
    val contentColor: Color,
    val focusedContainerColor: Color,
    val focusedContentColor: Color,
    val pressedContainerColor: Color,
    val pressedContentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
)

@Immutable
class ButtonShape internal constructor(val shape: Shape, val focusedShape: Shape)

@Immutable
class ButtonBorder internal constructor(val focusedBorderColor: Color?, val width: Dp)

object ButtonDefaults {
    val ContentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 10.dp)

    fun scale(
        scale: Float = 1f,
        focusedScale: Float = 1.1f,
        pressedScale: Float = scale,
        disabledScale: Float = scale,
        focusedDisabledScale: Float = disabledScale,
    ) = ButtonScale(scale, focusedScale, pressedScale, disabledScale)

    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        contentColor: Color = MaterialTheme.colorScheme.surface,
        focusedContainerColor: Color = MaterialTheme.colorScheme.onSurface,
        focusedContentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
        pressedContainerColor: Color = focusedContainerColor,
        pressedContentColor: Color = focusedContentColor,
        disabledContainerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        disabledContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    ) = ButtonColors(
        containerColor, contentColor, focusedContainerColor, focusedContentColor,
        pressedContainerColor, pressedContentColor, disabledContainerColor, disabledContentColor,
    )

    fun shape(shape: Shape = RoundedCornerShape(50), focusedShape: Shape = shape) = ButtonShape(shape, focusedShape)

    fun border(focusedBorderColor: Color? = null, width: Dp = 2.dp) = ButtonBorder(focusedBorderColor, width)
}

object IconButtonDefaults {
    val MediumButtonSize = 40.dp
    val SmallButtonSize = 28.dp

    fun scale(
        scale: Float = 1f,
        focusedScale: Float = 1.1f,
        pressedScale: Float = scale,
        disabledScale: Float = scale,
    ) = ButtonScale(scale, focusedScale, pressedScale, disabledScale)

    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        contentColor: Color = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor: Color = MaterialTheme.colorScheme.onSurface,
        focusedContentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
        pressedContainerColor: Color = focusedContainerColor,
        pressedContentColor: Color = focusedContentColor,
        disabledContainerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        disabledContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    ) = ButtonColors(
        containerColor, contentColor, focusedContainerColor, focusedContentColor,
        pressedContainerColor, pressedContentColor, disabledContainerColor, disabledContentColor,
    )

    fun shape(shape: Shape = CircleShape, focusedShape: Shape = shape) = ButtonShape(shape, focusedShape)
}

@Composable
private fun FocusSurface(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    scale: ButtonScale,
    shape: ButtonShape,
    colors: ButtonColors,
    border: ButtonBorder?,
    interactionSource: MutableInteractionSource?,
    content: @Composable (Color) -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val highlighted = enabled && (focused || hovered)
    val container = when {
        !enabled -> colors.disabledContainerColor
        pressed -> colors.pressedContainerColor
        highlighted -> colors.focusedContainerColor
        else -> colors.containerColor
    }
    val contentColor = when {
        !enabled -> colors.disabledContentColor
        pressed -> colors.pressedContentColor
        highlighted -> colors.focusedContentColor
        else -> colors.contentColor
    }
    val factor = when {
        !enabled -> scale.disabledScale
        pressed -> scale.pressedScale
        highlighted -> scale.focusedScale
        else -> scale.scale
    }
    val currentShape = if (highlighted) shape.focusedShape else shape.shape
    Box(
        modifier = modifier
            .scale(factor)
            .clip(currentShape)
            .background(container, currentShape)
            .then(
                if (highlighted && border?.focusedBorderColor != null) {
                    Modifier.border(border.width, border.focusedBorderColor, currentShape)
                } else Modifier,
            )
            .hoverable(source, enabled)
            .onKeyEvent { event ->
                // The remote's long-press equivalent: Shift+Enter / menu key.
                if (onLongClick != null && enabled && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Menu || (event.key == Key.Enter && event.isShiftPressed))
                ) {
                    onLongClick(); true
                } else false
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content(contentColor) }
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = ButtonDefaults.scale(),
    glow: Any? = null,
    shape: ButtonShape = ButtonDefaults.shape(),
    colors: ButtonColors = ButtonDefaults.colors(),
    tonalElevation: Dp = 0.dp,
    border: ButtonBorder? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val unused = glow to tonalElevation
    FocusSurface(onClick, onLongClick, modifier, enabled, scale, shape, colors, border, interactionSource) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 40.dp).padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = IconButtonDefaults.scale(),
    glow: Any? = null,
    shape: ButtonShape = IconButtonDefaults.shape(),
    colors: ButtonColors = IconButtonDefaults.colors(),
    border: ButtonBorder? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val unused = glow
    FocusSurface(
        onClick, onLongClick, modifier.size(IconButtonDefaults.MediumButtonSize), enabled, scale, shape,
        colors, border, interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

@Suppress("unused")
private fun Modifier.unusedFocusable() = focusable()
