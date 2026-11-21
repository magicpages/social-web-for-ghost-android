package co.magicpages.socialweb.data.settings

import androidx.compose.runtime.Immutable

/** The article reader's page colour. [Auto] follows the app's light or dark theme. */
enum class ReaderBackground { Auto, Sepia, Light, Dark }

/** The typeface of an article's body text. [Sans] is the app's own. */
enum class ReaderFont { Sans, Serif }

/**
 * How articles read, as Ghost Admin's reader offers it: page colour, body typeface, and one of
 * [TextScales] steps for the text size, on top of the system's font size. Device-wide, kept until changed.
 */
@Immutable
data class ReaderSettings(
    val background: ReaderBackground = ReaderBackground.Auto,
    val font: ReaderFont = ReaderFont.Sans,
    val textSize: Int = DefaultTextSize,
) {
    val textScale: Float get() = TextScales[textSize.coerceIn(TextScales.indices)]
    val canShrink: Boolean get() = textSize > 0
    val canGrow: Boolean get() = textSize < TextScales.lastIndex

    fun smaller() = copy(textSize = (textSize - 1).coerceAtLeast(0))
    fun larger() = copy(textSize = (textSize + 1).coerceAtMost(TextScales.lastIndex))

    companion object {
        /** Ghost's five sizes (1.5 to 2rem around 1.7rem), as factors of the app's reading text. */
        val TextScales = listOf(0.88f, 0.94f, 1f, 1.06f, 1.18f)
        const val DefaultTextSize = 2
    }
}
