package co.magicpages.socialweb.ui.components

/**
 * A profile bio as HTML, for [parsePostHtml]. ActivityPub says an actor's `summary` is HTML, but
 * WriteFreely (write.as and its other instances) sends the Markdown its writers typed. So a bio with
 * no tags at all is read as Markdown: links, bold, italics and line breaks. A bio with tags is HTML
 * already and comes back unchanged. Display only: editing your own bio keeps what the server has.
 */
fun bioHtml(bio: String): String {
    if (htmlTag.containsMatchIn(bio)) return bio
    val text = bio.trim().replace("<", "&lt;")
    // Emphasis between and inside links, never in their URLs (https://host/a__b__c).
    val html = StringBuilder(text.length + 32)
    var last = 0
    for (link in markdownLink.findAll(text)) {
        html.append(emphasis(text.substring(last, link.range.first)))
        html.append("<a href=\"").append(link.groupValues[2].replace("\"", "%22")).append("\">")
        html.append(emphasis(link.groupValues[1])).append("</a>")
        last = link.range.last + 1
    }
    html.append(emphasis(text.substring(last)))
    return html.toString().replace(lineBreak, "<br>")
}

private fun emphasis(text: String): String =
    text.replace(markdownBold, "<b>$2</b>").replace(markdownItalic, "<i>$1</i>")

private val htmlTag = Regex("""<(?:[a-zA-Z][a-zA-Z0-9]*\b[^>]*|/[a-zA-Z][a-zA-Z0-9]*\s*)>""")
private val markdownLink = Regex("""\[([^\[\]\n]+)]\((https?://[^\s()]+)\)""")
private val markdownBold = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")

// Single asterisks only: underscores turn up inside handles and words (@first_last_name).
private val markdownItalic = Regex("""(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])""")
private val lineBreak = Regex("""\r?\n""")
