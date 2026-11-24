package co.magicpages.socialweb.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import co.magicpages.socialweb.data.settings.ReaderBackground
import com.materialkolor.PaletteStyle
import com.materialkolor.blend.Blend
import com.materialkolor.dynamicColorScheme
import com.materialkolor.hct.Hct

/** The pink-red every like starts from, whatever the theme. */
private const val HeartColor = 0xFFE91E63.toInt()

/**
 * A liked heart's colour. Not a scheme role: the tertiary colour can be any hue (blue for a cyan
 * site), and a heart should read as one. Comes from [SocialWebTheme].
 */
val LocalLikeColor = staticCompositionLocalOf { Color(HeartColor) }

/** [HeartColor], its hue nudged towards [primary] (by 15° at most) and at the tone of a primary colour. */
private fun likeColor(primary: Color, dark: Boolean): Color {
    val harmonized = Blend.harmonize(Hct.fromInt(HeartColor), Hct.fromInt(primary.toArgb()))
    return Color(harmonized.withTone(if (dark) 80.0 else 40.0).toInt())
}

/**
 * Material 3 Expressive. With a [seed] (ARGB; the site's accent colour or one picked in Settings) the
 * colours are built from it the way Android builds them from the wallpaper (tonal spot): the seed's
 * hue at Material's own tones and colourfulness, so a muddy or neon brand colour still gives a calm
 * theme. The 2021 colour spec, not 2025: in the dark theme 2025 keeps a bright seed (a cyan, say) as
 * the container colour itself, with black text on it. Without a seed the colours come from the
 * wallpaper on Android 12+.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SocialWebTheme(seed: Int? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialExpressiveTheme(
        colorScheme = appColorScheme(seed, dark),
        motionScheme = MotionScheme.expressive(),
        typography = ContentDirectionTypography,
    ) {
        CompositionLocalProvider(LocalThemeSeed provides seed) {
            LikeColor(dark, content)
        }
    }
}

/** The seed [SocialWebTheme] built its colours from, so [ReaderTheme] can build the other brightness. */
private val LocalThemeSeed = staticCompositionLocalOf<Int?> { null }

/**
 * Material's type scale, each text's direction from its own first letters rather than the app's
 * language. Posts come in any language: an English title in the Arabic app is laid out left to
 * right (its full stop at its end), and an Arabic note in the English app right to left.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ContentDirectionTypography: Typography = Typography().run {
    fun TextStyle.byContent() = copy(textDirection = TextDirection.Content)
    copy(
        displayLarge = displayLarge.byContent(),
        displayMedium = displayMedium.byContent(),
        displaySmall = displaySmall.byContent(),
        headlineLarge = headlineLarge.byContent(),
        headlineMedium = headlineMedium.byContent(),
        headlineSmall = headlineSmall.byContent(),
        titleLarge = titleLarge.byContent(),
        titleMedium = titleMedium.byContent(),
        titleSmall = titleSmall.byContent(),
        bodyLarge = bodyLarge.byContent(),
        bodyMedium = bodyMedium.byContent(),
        bodySmall = bodySmall.byContent(),
        labelLarge = labelLarge.byContent(),
        labelMedium = labelMedium.byContent(),
        labelSmall = labelSmall.byContent(),
        displayLargeEmphasized = displayLargeEmphasized.byContent(),
        displayMediumEmphasized = displayMediumEmphasized.byContent(),
        displaySmallEmphasized = displaySmallEmphasized.byContent(),
        headlineLargeEmphasized = headlineLargeEmphasized.byContent(),
        headlineMediumEmphasized = headlineMediumEmphasized.byContent(),
        headlineSmallEmphasized = headlineSmallEmphasized.byContent(),
        titleLargeEmphasized = titleLargeEmphasized.byContent(),
        titleMediumEmphasized = titleMediumEmphasized.byContent(),
        titleSmallEmphasized = titleSmallEmphasized.byContent(),
        bodyLargeEmphasized = bodyLargeEmphasized.byContent(),
        bodyMediumEmphasized = bodyMediumEmphasized.byContent(),
        bodySmallEmphasized = bodySmallEmphasized.byContent(),
        labelLargeEmphasized = labelLargeEmphasized.byContent(),
        labelMediumEmphasized = labelMediumEmphasized.byContent(),
        labelSmallEmphasized = labelSmallEmphasized.byContent(),
    )
}

@Composable
private fun appColorScheme(seed: Int?, dark: Boolean): ColorScheme = when {
    seed != null -> remember(seed, dark) { dynamicColorScheme(Color(seed), dark, style = PaletteStyle.TonalSpot) }
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    dark -> darkColorScheme()
    else -> expressiveLightColorScheme()
}

@Composable
private fun LikeColor(dark: Boolean, content: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val like = remember(primary, dark) { likeColor(primary, dark) }
    CompositionLocalProvider(LocalLikeColor provides like, content = content)
}

/**
 * The article reader's page colour around [content]: [ReaderBackground.Auto] keeps the app theme;
 * Light and Dark build the app's colours (same seed) at that brightness whatever the system's, Light
 * on a white page as in Ghost; Sepia is the light colours on Ghost's sepia paper (`#FCF8F1`). The
 * system bars' icons follow the page while it shows. The tree under it stays the same for every
 * choice, so switching keeps state.
 */
@Composable
fun ReaderTheme(background: ReaderBackground, content: @Composable () -> Unit) {
    val dark = when (background) {
        ReaderBackground.Auto -> isSystemInDarkTheme()
        ReaderBackground.Dark -> true
        ReaderBackground.Sepia, ReaderBackground.Light -> false
    }
    val colorScheme = when (background) {
        ReaderBackground.Auto -> MaterialTheme.colorScheme
        ReaderBackground.Sepia -> appColorScheme(LocalThemeSeed.current, false).let { remember(it) { it.onSepia() } }
        ReaderBackground.Light -> appColorScheme(LocalThemeSeed.current, false).let { remember(it) { it.onWhite() } }
        ReaderBackground.Dark -> appColorScheme(LocalThemeSeed.current, true)
    }
    if (background != ReaderBackground.Auto) ReaderSystemBars(lightIcons = dark)
    MaterialTheme(colorScheme = colorScheme, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography) {
        LikeColor(dark, content)
    }
}

/** [ColorScheme] with a white page, as Ghost's light reader has. Containers keep their tint. */
private fun ColorScheme.onWhite(): ColorScheme =
    copy(background = Color.White, surface = Color.White, surfaceBright = Color.White, surfaceContainerLowest = Color.White)

/** [ColorScheme] surfaces on warm paper; text a warm near-black. Accents stay the seed's. */
private fun ColorScheme.onSepia(): ColorScheme = copy(
    background = Color(0xFFFCF8F1),
    onBackground = Color(0xFF231F18),
    surface = Color(0xFFFCF8F1),
    onSurface = Color(0xFF231F18),
    surfaceVariant = Color(0xFFECE6D9),
    onSurfaceVariant = Color(0xFF5C554A),
    surfaceTint = primary,
    surfaceBright = Color(0xFFFCF8F1),
    surfaceDim = Color(0xFFE2DACB),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF7F2E8),
    surfaceContainer = Color(0xFFF2ECE0),
    surfaceContainerHigh = Color(0xFFECE6D9),
    surfaceContainerHighest = Color(0xFFE6DFD1),
    outline = Color(0xFF8A8274),
    outlineVariant = Color(0xFFD9D1C2),
    inverseSurface = Color(0xFF383229),
    inverseOnSurface = Color(0xFFF7F1E6),
)

/**
 * Status and navigation bar icons that show on the reader's page, restored to the app's (dark icons
 * on a light system theme) when the reader leaves.
 */
@Composable
private fun ReaderSystemBars(lightIcons: Boolean) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    DisposableEffect(view, lightIcons, systemDark) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
        onDispose {
            controller?.apply {
                isAppearanceLightStatusBars = !systemDark
                isAppearanceLightNavigationBars = !systemDark
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
