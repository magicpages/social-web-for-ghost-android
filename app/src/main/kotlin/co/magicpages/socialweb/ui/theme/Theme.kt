package co.magicpages.socialweb.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
    val colorScheme = when {
        seed != null -> remember(seed, dark) { dynamicColorScheme(Color(seed), dark, style = PaletteStyle.TonalSpot) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    val like = remember(colorScheme.primary, dark) { likeColor(colorScheme.primary, dark) }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(LocalLikeColor provides like, content = content)
    }
}
