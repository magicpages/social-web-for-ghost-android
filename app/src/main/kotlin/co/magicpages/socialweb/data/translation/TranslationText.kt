package co.magicpages.socialweb.data.translation

/** Why a text can't be translated; the user is told, in the app's language. */
enum class TranslationProblem {
    /** There's no text to translate. */
    Empty,

    /** The language of the text couldn't be told. */
    UnknownLanguage,

    /** The text is in the language it would be translated into. */
    AlreadyInTargetLanguage,

    /** The text's language can't be translated on device. */
    UnsupportedLanguage,

    /** Anything else: the language files couldn't be downloaded, the translator failed. */
    Failed,
}

/** [language] is the language code the problem is about, for [TranslationProblem.AlreadyInTargetLanguage] and [TranslationProblem.UnsupportedLanguage]. */
class TranslationException(
    val problem: TranslationProblem,
    val language: String? = null,
    cause: Throwable? = null,
) : Exception(problem.name, cause)

/** A text cut into the pieces that are translated one by one, and what stays as it is around them. */
class TranslationParts internal constructor(
    /** The pieces to translate, trimmed, none empty. */
    val parts: List<String>,
    /** The text in order: what stays as it is, and `null` where the next of [parts] goes. */
    private val layout: List<String?>,
) {
    /** The translated [pieces] (one per part, in order) put back in place of the parts. */
    fun join(pieces: List<String>): String {
        require(pieces.size == parts.size) { "Expected ${parts.size} translated parts, got ${pieces.size}" }
        var next = 0
        return buildString {
            for (kept in layout) append(kept ?: pieces[next++].trim())
        }
    }
}

private val lineBreaks = Regex("""[^\S\n]*\n\s*""")

/** A link ("https://…", or "example.com/…" as notes show them), a mention or a hashtag. */
private const val Token = """(?:https?://\S+|[\w-]+(?:\.[\w-]+)+/\S*|[@#][\p{L}\p{N}_][\p{L}\p{N}_.@-]*)"""
private val leadingTokens = Regex("""^(?:\s*$Token)+\s*""")
private val trailingTokens = Regex("""(?:\s*$Token)+\s*$""")

/**
 * Cuts [text] at its line breaks so every line is translated on its own and the breaks survive
 * the translation: the translator tends to merge lines it is given in one piece. Blank lines
 * (any number) between lines stay one paragraph break. Links, mentions and hashtags at the start
 * or end of a line stay as they are, which the translator would otherwise change ("@sundog" can
 * come back as "@undog"), and so does a line with nothing else to translate.
 */
fun splitForTranslation(text: String): TranslationParts {
    val trimmed = text.trim()
    val parts = ArrayList<String>()
    val layout = ArrayList<String?>()
    fun line(line: String) {
        val lead = leadingTokens.find(line)?.value.orEmpty()
        val rest = line.substring(lead.length)
        val trail = trailingTokens.find(rest)?.value.orEmpty()
        val core = rest.substring(0, rest.length - trail.length)
        if (core.none { it.isLetter() }) {
            layout += line
            return
        }
        if (lead.isNotEmpty()) layout += lead
        parts += core.trim()
        layout += null
        if (trail.isNotEmpty()) layout += trail
    }
    if (trimmed.isEmpty()) return TranslationParts(parts, layout)
    var start = 0
    for (match in lineBreaks.findAll(trimmed)) {
        line(trimmed.substring(start, match.range.first))
        layout += if (match.value.count { it == '\n' } > 1) "\n\n" else "\n"
        start = match.range.last + 1
    }
    line(trimmed.substring(start))
    return TranslationParts(parts, layout)
}

/**
 * The language a [tag] (BCP 47, as the language identifier or a locale gives it) is about, as
 * the translator names it: the primary subtag, in lower case, with the legacy and Android codes
 * mapped to the ones ML Kit uses ("iw" is Hebrew "he", "fil" Filipino "tl", "nb" Norwegian "no").
 */
fun translationLanguage(tag: String): String {
    val primary = tag.trim().substringBefore('-').substringBefore('_').lowercase()
    return when (primary) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "fil" -> "tl"
        "nb", "nn" -> "no"
        else -> primary
    }
}

/**
 * The language to translate from: the [detected] tag of the language identifier (`und` when it
 * couldn't tell), checked against the [target] language and the [supported] ones.
 *
 * @throws TranslationException when the language is unknown, is the target, or isn't supported
 */
fun sourceLanguage(detected: String, target: String, supported: Set<String>): String {
    val tag = detected.trim()
    if (tag.isEmpty() || tag.equals("und", ignoreCase = true)) {
        throw TranslationException(TranslationProblem.UnknownLanguage)
    }
    val source = translationLanguage(tag)
    // Romanised text such as "ja-Latn" is the language written in Latin letters; translating it as the language itself would garble it.
    if (tag.endsWith("-Latn", ignoreCase = true)) throw TranslationException(TranslationProblem.UnsupportedLanguage, source)
    if (source == target) throw TranslationException(TranslationProblem.AlreadyInTargetLanguage, target)
    if (source !in supported) throw TranslationException(TranslationProblem.UnsupportedLanguage, source)
    return source
}
