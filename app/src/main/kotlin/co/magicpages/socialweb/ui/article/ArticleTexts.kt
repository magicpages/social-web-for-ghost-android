package co.magicpages.socialweb.ui.article

import androidx.compose.ui.text.AnnotatedString

/**
 * The text a translation covers, in reading order: paragraphs, headings, quotes, list items,
 * captions, callouts, toggle headings, link card titles and descriptions, button labels, table
 * cells. Code, names of files and the like stay as they are.
 */
fun ArticleDocument.translatableTexts(): List<String> {
    val texts = ArrayList<String>()
    mapTexts {
        texts += it
        it
    }
    return texts
}

/**
 * This document with the text [translatableTexts] found replaced by [translated], one for one.
 * Inline formatting and links give way to plain text in what was translated.
 */
fun ArticleDocument.withTranslatedTexts(translated: List<String>): ArticleDocument {
    val next = translated.iterator()
    val result = mapTexts { next.next() }
    check(!next.hasNext()) { "More translations than texts" }
    return result
}

/** Applies [transform] to every non-blank translatable text, in order. */
private fun ArticleDocument.mapTexts(transform: (String) -> String): ArticleDocument {
    fun rich(value: AnnotatedString): AnnotatedString =
        if (value.text.isBlank()) value else AnnotatedString(transform(value.text))

    fun richOrNull(value: AnnotatedString?): AnnotatedString? = value?.let { rich(it) }

    fun plain(value: String): String = if (value.isBlank()) value else transform(value)

    fun plainOrNull(value: String?): String? = value?.let { plain(it) }

    fun blocks(blocks: List<ArticleBlock>): List<ArticleBlock> = blocks.map { block ->
        when (block) {
            is ArticleBlock.Paragraph -> block.copy(text = rich(block.text))
            is ArticleBlock.Heading -> block.copy(text = rich(block.text))
            is ArticleBlock.Quote -> block.copy(text = rich(block.text))
            is ArticleBlock.BulletList -> block.copy(entries = block.entries.map { it.copy(text = rich(it.text)) })
            is ArticleBlock.Image -> block.copy(caption = richOrNull(block.caption))
            is ArticleBlock.Gallery -> block.copy(caption = richOrNull(block.caption))
            is ArticleBlock.Bookmark -> block.copy(title = plain(block.title), description = plainOrNull(block.description))
            is ArticleBlock.Callout -> block.copy(text = rich(block.text))
            is ArticleBlock.Button -> block.copy(text = plain(block.text))
            is ArticleBlock.Toggle -> block.copy(heading = rich(block.heading), content = blocks(block.content))
            is ArticleBlock.Video -> block.copy(caption = richOrNull(block.caption))
            is ArticleBlock.Embed -> block.copy(caption = richOrNull(block.caption))
            is ArticleBlock.File -> block.copy(caption = plainOrNull(block.caption))
            is ArticleBlock.Table -> block.copy(rows = block.rows.map { row -> row.map { rich(it) } })
            is ArticleBlock.Code,
            ArticleBlock.Divider,
            is ArticleBlock.Audio,
            is ArticleBlock.MembersOnly,
            -> block
        }
    }

    return ArticleDocument(blocks(blocks))
}
