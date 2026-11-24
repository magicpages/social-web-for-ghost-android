package co.magicpages.socialweb.ui.article

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import co.magicpages.socialweb.R
import co.magicpages.socialweb.data.settings.AppSettings
import co.magicpages.socialweb.data.settings.ReaderBackground
import co.magicpages.socialweb.data.settings.ReaderFont
import co.magicpages.socialweb.data.settings.ReaderSettings
import co.magicpages.socialweb.ui.components.AppIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ReaderSettingsViewModel @Inject constructor(private val appSettings: AppSettings) : ViewModel() {
    val settings: StateFlow<ReaderSettings> = appSettings.reader

    fun update(settings: ReaderSettings) = appSettings.setReader(settings)
}

/** The reader's settings for the article items below; [ReaderText] reads the text size and typeface from it. */
val LocalReaderSettings = compositionLocalOf { ReaderSettings() }

/** The typeface the reader chose for body text, or null for the theme's. Set by [ReaderText]. */
internal val LocalArticleFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * Article text at the reader's size and in its typeface. The size scales the system's font scale,
 * so every `sp` in [content] grows together and the system setting still counts; `dp` stays as is.
 */
@Composable
internal fun ReaderText(content: @Composable () -> Unit) {
    val settings = LocalReaderSettings.current
    val density = LocalDensity.current
    val scale = settings.textScale
    val scaled = remember(density, scale) { Density(density.density, density.fontScale * scale) }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalArticleFontFamily provides if (settings.font == ReaderFont.Serif) FontFamily.Serif else null,
        content = content,
    )
}

/** The top bar's text settings button and its panel: page colour, typeface, text size. Changes apply at once. */
@Composable
fun ReaderSettingsAction(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(AppIcons.FormatSize, contentDescription = stringResource(R.string.reader_settings))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.extraLarge) {
            ReaderSettingsPanel(settings, onChange, Modifier.width(296.dp).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun ReaderSettingsPanel(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderBackground.entries.forEach { background ->
                BackgroundSwatch(background, selected = settings.background == background, Modifier.weight(1f)) {
                    onChange(settings.copy(background = background))
                }
            }
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { font ->
                FontTile(font, selected = settings.font == font, Modifier.weight(1f)) { onChange(settings.copy(font = font)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChange(settings.smaller()) }, enabled = settings.canShrink, modifier = Modifier.weight(1f)) {
                Icon(AppIcons.Remove, contentDescription = stringResource(R.string.reader_text_smaller))
            }
            val reset = stringResource(R.string.reader_text_reset)
            TextButton(
                onClick = { onChange(settings.copy(textSize = ReaderSettings.DefaultTextSize)) },
                modifier = Modifier.weight(1f).semantics { contentDescription = reset },
            ) {
                Text("Aa", style = MaterialTheme.typography.titleMedium)
            }
            FilledTonalIconButton(onClick = { onChange(settings.larger()) }, enabled = settings.canGrow, modifier = Modifier.weight(1f)) {
                Icon(AppIcons.Add, contentDescription = stringResource(R.string.reader_text_larger))
            }
        }
    }
}

@Composable
private fun BackgroundSwatch(background: ReaderBackground, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(
        when (background) {
            ReaderBackground.Auto -> R.string.reader_background_auto
            ReaderBackground.Sepia -> R.string.reader_background_sepia
            ReaderBackground.Light -> R.string.reader_background_light
            ReaderBackground.Dark -> R.string.reader_background_dark
        },
    )
    val colors = MaterialTheme.colorScheme
    // Auto is half light, half dark: it follows the app's theme.
    val fill = when (background) {
        ReaderBackground.Auto -> Brush.horizontalGradient(0f to Color.White, 0.5f to Color.White, 0.5f to DarkPage, 1f to DarkPage)
        ReaderBackground.Sepia -> SolidColor(Color(0xFFECE6D9))
        ReaderBackground.Light -> SolidColor(Color.White)
        ReaderBackground.Dark -> SolidColor(DarkPage)
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier
            // A full touch target: the swatch is the button.
            .height(48.dp)
            .clip(shape)
            .background(fill, shape)
            .border(if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

private val DarkPage = Color(0xFF15171A)

@Composable
private fun FontTile(font: ReaderFont, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val family = if (font == ReaderFont.Serif) FontFamily.Serif else null
    val label = stringResource(if (font == ReaderFont.Serif) R.string.reader_font_serif else R.string.reader_font_sans)
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHighest,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aa", fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text(label, fontFamily = family, style = MaterialTheme.typography.labelLarge)
        }
    }
}
