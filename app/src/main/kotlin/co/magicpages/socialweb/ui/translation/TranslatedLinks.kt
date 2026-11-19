package co.magicpages.socialweb.ui.translation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [translated], the translation of [original]'s text, with [original]'s links (links, mentions,
 * hashtags) put back on their text wherever it came through the translation unchanged, in the
 * order they appear. The translation keeps links, mentions and hashtags at the edges of a line
 * verbatim; one the translator changed stays plain text.
 */
internal fun relinkTranslation(original: AnnotatedString, translated: String): AnnotatedString {
    val links = original.getLinkAnnotations(0, original.length).sortedBy { it.start }
    if (links.isEmpty()) return AnnotatedString(translated)
    return buildAnnotatedString {
        append(translated)
        var from = 0
        for (link in links) {
            val label = original.text.substring(link.start, link.end)
            if (label.isBlank()) continue
            val at = translated.indexOfWhole(label, from)
            if (at < 0) continue
            when (val item = link.item) {
                is LinkAnnotation.Url -> addLink(item, at, at + label.length)
                is LinkAnnotation.Clickable -> addLink(item, at, at + label.length)
            }
            from = at + label.length
        }
    }
}

/** Where [label] next stands on its own in this text from [from]: not part of a longer word, mention or tag. */
private fun String.indexOfWhole(label: String, from: Int): Int {
    var at = indexOf(label, from)
    while (at >= 0) {
        val before = getOrNull(at - 1)
        val after = getOrNull(at + label.length)
        if (!before.isWordPart() && !after.isWordPart()) return at
        at = indexOf(label, at + 1)
    }
    return -1
}

private fun Char?.isWordPart() = this != null && (isLetterOrDigit() || this == '_')
