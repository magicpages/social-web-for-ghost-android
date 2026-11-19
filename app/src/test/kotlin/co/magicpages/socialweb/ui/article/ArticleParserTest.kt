package co.magicpages.socialweb.ui.article

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleParserTest {
    private val base = "https://blog.example/posts/tide-pools/"

    private fun parse(html: String, baseUrl: String? = base) = ArticleParser.parse(html, baseUrl).blocks

    private inline fun <reified T : ArticleBlock> single(html: String, baseUrl: String? = base): T {
        val blocks = parse(html, baseUrl)
        assertEquals("expected exactly one block, got $blocks", 1, blocks.size)
        return blocks.single() as? T ?: error("expected ${T::class.simpleName}, got ${blocks.single()}")
    }

    private fun AnnotatedString.links() = getLinkAnnotations(0, length).map { it.item as LinkAnnotation.Url }

    @Test
    fun `dek is dropped when the body opens with it, kept when written separately`() {
        val document = ArticleParser.parse("<p>The White Balloon (1995)</p><p>Several people try to take advantage of a girl.</p>", base)

        assertNull(document.dek("The White Balloon (1995)Several people try to take…"))
        assertEquals("A film about a goldfish", document.dek("  A film about a goldfish "))
        assertNull(document.dek(" "))
    }

    @Test
    fun `image card keeps alt, size, width class and caption, and resolves the URL`() {
        val image = single<ArticleBlock.Image>(
            """<figure class="kg-card kg-image-card kg-width-wide">
                <img src="/content/images/lighthouse.png" class="kg-image" alt="A lighthouse at dusk" loading="lazy" width="1568" height="1264">
                <figcaption>Dusk, <em>before</em> the storm</figcaption>
            </figure>""",
        )

        assertEquals(ArticleImage("https://blog.example/content/images/lighthouse.png", "A lighthouse at dusk", 1568, 1264), image.image)
        assertEquals(ImageWidth.Wide, image.width)
        assertEquals("Dusk, before the storm", image.caption?.text)
        assertNull(image.link)
    }

    @Test
    fun `linked image with empty alt and no caption`() {
        val image = single<ArticleBlock.Image>(
            """<figure class="kg-card kg-image-card kg-width-full"><a href="https://example.com/full"><img src="https://cdn.example/a.jpg" alt=""></a></figure>""",
        )

        assertEquals("https://example.com/full", image.link)
        assertNull(image.image.alt)
        assertNull(image.image.aspectRatio)
        assertNull(image.caption)
        assertEquals(ImageWidth.Full, image.width)
    }

    @Test
    fun `a bare img and an img alone in a paragraph are images`() {
        val blocks = parse("""<img src="a.jpg" alt="First"><p><img src="b.jpg" alt="Second"></p>""")

        assertEquals(listOf("First", "Second"), blocks.map { (it as ArticleBlock.Image).image.alt })
        assertEquals("https://blog.example/posts/tide-pools/a.jpg", (blocks[0] as ArticleBlock.Image).image.url)
    }

    @Test
    fun `gallery rows follow the row containers`() {
        val gallery = single<ArticleBlock.Gallery>(
            """<figure class="kg-card kg-gallery-card kg-width-wide kg-card-hascaption"><div class="kg-gallery-container">
                <div class="kg-gallery-row">
                    <div class="kg-gallery-image"><img src="https://x.example/1.jpg" width="600" height="400" alt="one"></div>
                    <div class="kg-gallery-image"><img src="https://x.example/2.jpg" width="300" height="400" alt=""></div>
                </div>
                <div class="kg-gallery-row">
                    <div class="kg-gallery-image"><img src="https://x.example/3.jpg" width="900" height="600" alt="three"></div>
                </div>
            </div><figcaption>Three shots</figcaption></figure>""",
        )

        assertEquals(listOf(2, 1), gallery.rows.map { it.size })
        assertEquals(listOf("one", null), gallery.rows[0].map { it.alt })
        assertEquals(1.5f, gallery.rows[1].single().aspectRatio)
        assertEquals("Three shots", gallery.caption?.text)
    }

    @Test
    fun `bookmark card reads Koenig's author and publisher spans as publisher and author`() {
        val bookmark = single<ArticleBlock.Bookmark>(
            """<figure class="kg-card kg-bookmark-card"><a class="kg-bookmark-container" href="https://github.com/TryGhost/Ghost/releases/tag/v6.78.0">
                <div class="kg-bookmark-content">
                    <div class="kg-bookmark-title">Release 6.78.0 · TryGhost/Ghost</div>
                    <div class="kg-bookmark-description">See the full changelog</div>
                    <div class="kg-bookmark-metadata">
                        <img class="kg-bookmark-icon" src="https://github.githubassets.com/favicons/favicon.svg" alt="">
                        <span class="kg-bookmark-author">GitHub</span><span class="kg-bookmark-publisher">TryGhost</span>
                    </div>
                </div>
                <div class="kg-bookmark-thumbnail"><img src="https://opengraph.example/ghost.png" alt=""></div>
            </a></figure>""",
        )

        assertEquals(
            ArticleBlock.Bookmark(
                url = "https://github.com/TryGhost/Ghost/releases/tag/v6.78.0",
                title = "Release 6.78.0 · TryGhost/Ghost",
                description = "See the full changelog",
                publisher = "GitHub",
                author = "TryGhost",
                iconUrl = "https://github.githubassets.com/favicons/favicon.svg",
                thumbnailUrl = "https://opengraph.example/ghost.png",
            ),
            bookmark,
        )
    }

    @Test
    fun `bookmark without metadata falls back to its URL as title`() {
        val bookmark = single<ArticleBlock.Bookmark>(
            """<figure class="kg-card kg-bookmark-card"><a class="kg-bookmark-container" href="https://example.com/page"><div class="kg-bookmark-content"></div></a></figure>""",
        )

        assertEquals("https://example.com/page", bookmark.title)
        assertNull(bookmark.description)
        assertNull(bookmark.thumbnailUrl)
    }

    @Test
    fun `callout takes emoji, colour and formatted text`() {
        val callout = single<ArticleBlock.Callout>(
            """<div class="kg-card kg-callout-card kg-callout-card-grey"><div class="kg-callout-emoji">🍸</div>
                <div class="kg-callout-text"><b><strong>A message from Jan:</strong></b><br />Sign up at <a href="https://casualfriday.club/">CasualFriday.Club</a></div></div>""",
        )

        assertEquals("🍸", callout.emoji)
        assertEquals("grey", callout.color)
        assertEquals("A message from Jan:\nSign up at CasualFriday.Club", callout.text.text)
        assertEquals(listOf("https://casualfriday.club/"), callout.text.links().map { it.url })
    }

    @Test
    fun `toggle card parses its content as blocks`() {
        val toggle = single<ArticleBlock.Toggle>(
            """<div class="kg-card kg-toggle-card" data-kg-toggle-state="close">
                <div class="kg-toggle-heading"><h4 class="kg-toggle-heading-text">Why <em>now</em>?</h4><button class="kg-toggle-card-icon"><svg><path d="M1"></path></svg></button></div>
                <div class="kg-toggle-content"><p>Because.</p><ul><li>One</li><li>Two</li></ul></div>
            </div>""",
        )

        assertEquals("Why now?", toggle.heading.text)
        assertEquals(2, toggle.content.size)
        assertEquals("Because.", (toggle.content[0] as ArticleBlock.Paragraph).text.text)
        assertEquals(listOf("One", "Two"), (toggle.content[1] as ArticleBlock.BulletList).entries.map { it.text.text })
    }

    @Test
    fun `details with a summary becomes a toggle, without one it is just its content`() {
        val toggle = single<ArticleBlock.Toggle>("<details><summary>More</summary><p>Hidden text</p></details>")
        assertEquals("More", toggle.heading.text)
        assertEquals("Hidden text", (toggle.content.single() as ArticleBlock.Paragraph).text.text)

        val plain = single<ArticleBlock.Paragraph>("<details><p>Always shown</p></details>")
        assertEquals("Always shown", plain.text.text)
    }

    @Test
    fun `audio card and bare audio element`() {
        val card = single<ArticleBlock.Audio>(
            """<div class="kg-card kg-audio-card"><img src="" alt="audio-thumbnail" class="kg-audio-thumbnail kg-audio-hide">
                <div class="kg-audio-player-container"><audio src="/content/media/episode.mp3" preload="metadata"></audio>
                <div class="kg-audio-title">Episode 12</div><div class="kg-audio-player"><button class="kg-audio-play-icon"></button><span class="kg-audio-current-time">0:00</span></div></div></div>""",
        )
        assertEquals(ArticleBlock.Audio("https://blog.example/content/media/episode.mp3", "Episode 12"), card)

        val bare = single<ArticleBlock.Audio>("""<audio controls><source src="https://cdn.example/a.ogg" type="audio/ogg"></audio>""")
        assertEquals(ArticleBlock.Audio("https://cdn.example/a.ogg", null), bare)
    }

    @Test
    fun `video card keeps source, background thumbnail and caption but not the player chrome`() {
        val video = single<ArticleBlock.Video>(
            """<figure class="kg-card kg-video-card kg-width-regular kg-card-hascaption">
                <div class="kg-video-container">
                    <video src="https://cdn.example/clip.mp4" width="1920" height="1080" preload="metadata" style="aspect-ratio:1920 / 1080;background:transparent url('https://cdn.example/clip_thumb.jpg') 50% 50% / cover no-repeat"></video>
                    <div class="kg-video-overlay"><button class="kg-video-large-play-icon"><svg viewbox="0 0 24 24"><path d="M0 0"></path></svg></button></div>
                    <div class="kg-video-player-container"><div class="kg-video-player"><button class="kg-video-play-icon"></button><span class="kg-video-current-time">0:00</span><div class="kg-video-time">/<span class="kg-video-duration">5:33</span></div><input type="range" class="kg-video-seek-slider" max="100" value="0"></div></div>
                </div>
                <figcaption><p><span style="white-space:pre-wrap">Edited for length</span></p></figcaption>
            </figure>""",
        )

        assertEquals("https://cdn.example/clip.mp4", video.url)
        assertEquals("https://cdn.example/clip_thumb.jpg", video.posterUrl)
        assertEquals("Edited for length", video.caption?.text)
    }

    @Test
    fun `YouTube embeds map to the watch page with a thumbnail`() {
        val card = single<ArticleBlock.Embed>(
            """<figure class="kg-card kg-embed-card kg-card-hascaption"><iframe width="200" height="113" src="https://www.youtube.com/embed/dQw4w9WgXcQ?feature=oembed" frameborder="0" allowfullscreen></iframe><figcaption>Classic</figcaption></figure>""",
        )

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", card.url)
        assertEquals("YouTube", card.provider)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", card.thumbnailUrl)
        assertEquals("Classic", card.caption?.text)

        val nocookie = single<ArticleBlock.Embed>("""<iframe src="//www.youtube-nocookie.com/embed/dQw4w9WgXcQ"></iframe>""")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", nocookie.url)
    }

    @Test
    fun `Vimeo and other iframes become embeds with their host as provider`() {
        val vimeo = single<ArticleBlock.Embed>("""<iframe src="https://player.vimeo.com/video/76979871?h=8272103f6e"></iframe>""")
        assertEquals(ArticleBlock.Embed("https://vimeo.com/76979871", "Vimeo", null, null), vimeo)

        val other = single<ArticleBlock.Embed>("""<iframe frameborder="no" height="200" src="https://playlist.megaphone.fm?p=TBIEA2761282490&amp;episodes=1" width="100%"></iframe>""")
        assertEquals("https://playlist.megaphone.fm?p=TBIEA2761282490&episodes=1", other.url)
        assertEquals("playlist.megaphone.fm", other.provider)
        assertNull(other.thumbnailUrl)
    }

    @Test
    fun `an embed card without an iframe is read as the markup inside`() {
        val quote = single<ArticleBlock.Quote>(
            """<figure class="kg-card kg-embed-card"><blockquote class="twitter-tweet"><p>Shipping today.</p>&mdash; Ghost (@Ghost) <a href="https://twitter.com/Ghost/status/1">May 1</a></blockquote><script async src="https://platform.twitter.com/widgets.js"></script></figure>""",
        )

        assertEquals(false, quote.alternative)
        assertEquals("Shipping today.\n\n— Ghost (@Ghost) May 1", quote.text.text)
    }

    @Test
    fun `nested lists are flattened with depth and markers, ordered lists honour start`() {
        val list = single<ArticleBlock.BulletList>(
            """<ol start="3"><li>Third<ul><li>Sub a</li><li>Sub b<ol><li>Deep</li></ol></li></ul></li><li>Fourth</li></ol>""",
        )

        assertEquals(
            listOf(
                Triple("Third", 0, "3."),
                Triple("Sub a", 1, "•"),
                Triple("Sub b", 1, "•"),
                Triple("Deep", 2, "1."),
                Triple("Fourth", 0, "4."),
            ),
            list.entries.map { Triple(it.text.text, it.depth, it.marker) },
        )
    }

    @Test
    fun `inline links become URL annotations with absolute addresses`() {
        val text = single<ArticleBlock.Paragraph>(
            """<p>Read <a href="/roadmap/pagemoji/">Pagemoji</a>, <a href="https://example.com/x?a=1&amp;b=2">this</a>, <a href="#notes">a note</a> and <a href="javascript:alert(1)">nothing</a>.</p>""",
        ).text

        assertEquals("Read Pagemoji, this, a note and nothing.", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://blog.example/roadmap/pagemoji/", "https://example.com/x?a=1&b=2"), links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals(listOf("Pagemoji", "this"), links.map { text.text.substring(it.start, it.end) })
    }

    @Test
    fun `relative URLs stay as written without a base URL`() {
        val image = single<ArticleBlock.Image>("""<img src="/content/a.png">""", baseUrl = null)

        assertEquals("/content/a.png", image.image.url)
    }

    @Test
    fun `bold and italic spans cover the formatted text`() {
        val text = single<ArticleBlock.Paragraph>("<p>Make it <strong>very <em>bold</em></strong> now</p>").text

        assertEquals("Make it very bold now", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(8 to 17, bold.start to bold.end)
        assertEquals(13 to 17, italic.start to italic.end)
    }

    @Test
    fun `whitespace collapses like in a browser and no-break spaces survive`() {
        val text = single<ArticleBlock.Paragraph>("<p>  Hello\n   <b>wide   world</b> \n  again\u00a0\u00a0there  </p>").text

        assertEquals("Hello wide world again\u00a0\u00a0there", text.text)
    }

    @Test
    fun `loose inline nodes between blocks form paragraphs`() {
        val blocks = parse("""Intro <b>text</b> here<br>next line<p>Block</p>tail <a href="https://example.com/">link</a>""")

        assertEquals(listOf("Intro text here\nnext line", "Block", "tail link"), blocks.map { (it as ArticleBlock.Paragraph).text.text })
    }

    @Test
    fun `plain wrappers are walked, scripts and forms are not`() {
        val blocks = parse(
            """<!-- a comment --><div class="outpost-pub-container"></div>
            <section><div><p>Inside</p></div></section>
            <p>&nbsp;</p><p> </p><p><br></p>
            <script>alert(1)</script><style>p {}</style><noscript>enable JS</noscript>
            <div class="kg-card kg-signup-card"><form><h2>Subscribe</h2><input type="email"><button>Go</button></form></div>
            <p>After</p>""",
        )

        assertEquals(listOf("Inside", "After"), blocks.map { (it as ArticleBlock.Paragraph).text.text })
    }

    @Test
    fun `headings, quotes, code and dividers`() {
        val blocks = parse(
            """<h2 id="a">Under <em>every</em> post</h2>
            <blockquote>Plain</blockquote>
            <blockquote class="kg-blockquote-alt">Pulled out</blockquote>
            <pre><code class="language-kotlin">fun main() {
    println("hi")
}
</code></pre>
            <hr>""",
        )

        assertEquals(5, blocks.size)
        assertEquals(2, (blocks[0] as ArticleBlock.Heading).level)
        assertEquals("Under every post", (blocks[0] as ArticleBlock.Heading).text.text)
        assertEquals(false, (blocks[1] as ArticleBlock.Quote).alternative)
        assertEquals(true, (blocks[2] as ArticleBlock.Quote).alternative)
        assertEquals(ArticleBlock.Code("fun main() {\n    println(\"hi\")\n}", "kotlin"), blocks[3])
        assertEquals(ArticleBlock.Divider, blocks[4])
    }

    @Test
    fun `table with a thead has header rows, otherwise a th-only first row is the header`() {
        val withHead = single<ArticleBlock.Table>(
            "<table><thead><tr><th>Plan</th><th>Price</th></tr></thead><tbody><tr><td>Free</td><td>$0</td></tr></tbody></table>",
        )
        assertEquals(1, withHead.headerRows)
        assertEquals(listOf(listOf("Plan", "Price"), listOf("Free", "$0")), withHead.rows.map { row -> row.map { it.text } })

        val thRow = single<ArticleBlock.Table>("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>")
        assertEquals(1, thRow.headerRows)

        val none = single<ArticleBlock.Table>("<table><tr><td>1</td><td>2</td></tr></table>")
        assertEquals(0, none.headerRows)
    }

    @Test
    fun `button, file and header cards`() {
        val button = single<ArticleBlock.Button>(
            """<div class="kg-card kg-button-card kg-align-center"><a href="/subscribe" class="kg-btn kg-btn-accent">Subscribe now</a></div>""",
        )
        assertEquals(ArticleBlock.Button("Subscribe now", "https://blog.example/subscribe"), button)

        val file = single<ArticleBlock.File>(
            """<div class="kg-card kg-file-card"><a class="kg-file-card-container" href="https://cdn.example/files/report.pdf" title="Download" download>
                <div class="kg-file-card-contents"><div class="kg-file-card-title">Annual report</div><div class="kg-file-card-caption">Numbers</div>
                <div class="kg-file-card-metadata"><div class="kg-file-card-filename">report.pdf</div><div class="kg-file-card-filesize">1.2 MB</div></div></div></a></div>""",
        )
        assertEquals(ArticleBlock.File("https://cdn.example/files/report.pdf", "Annual report", "Numbers", "1.2 MB"), file)

        val header = parse(
            """<div class="kg-card kg-header-card kg-width-full kg-size-small kg-style-dark"><h2 class="kg-header-card-header">Join us</h2><h3 class="kg-header-card-subheader">It is <b>free</b></h3><a class="kg-header-card-button" href="https://example.com/join">Join</a></div>""",
        )
        assertEquals(
            listOf(ArticleBlock.Heading::class, ArticleBlock.Paragraph::class, ArticleBlock.Button::class),
            header.map { it::class },
        )
        assertEquals("Join us", (header[0] as ArticleBlock.Heading).text.text)
        assertEquals("It is free", (header[1] as ArticleBlock.Paragraph).text.text)
    }

    @Test
    fun `product card becomes image, heading, description and button`() {
        val blocks = parse(
            """<div class="kg-card kg-product-card"><div class="kg-product-card-container">
                <img src="https://cdn.example/p.jpg" width="800" height="600" class="kg-product-card-image">
                <div class="kg-product-card-title-container"><h4 class="kg-product-card-title">Mug</h4></div>
                <div class="kg-product-card-description"><p>Holds coffee.</p></div>
                <a href="https://shop.example/mug" class="kg-product-card-button kg-product-card-btn-accent"><span>Buy</span></a>
            </div></div>""",
        )

        assertEquals(listOf(ArticleBlock.Image::class, ArticleBlock.Heading::class, ArticleBlock.Paragraph::class, ArticleBlock.Button::class), blocks.map { it::class })
        assertEquals(ArticleBlock.Button("Buy", "https://shop.example/mug"), blocks[3])
    }

    @Test
    fun `unknown cards contribute their text, links and images`() {
        val blocks = parse(
            """<div class="kg-card kg-cta-card kg-cta-bg-grey"><div class="kg-cta-content"><div class="kg-cta-image-container"><img src="https://cdn.example/c.png" alt="CTA"></div>
                <div class="kg-cta-text"><p>Try the new thing</p></div><a href="https://example.com/try" class="kg-cta-button">Try it</a></div></div>""",
        )

        assertEquals(
            listOf(ArticleBlock.Image::class, ArticleBlock.Paragraph::class, ArticleBlock.Button::class),
            blocks.map { it::class },
        )
        assertEquals("Try the new thing", (blocks[1] as ArticleBlock.Paragraph).text.text)
    }

    @Test
    fun `the paid content notice ends the public part as a members-only gate`() {
        val document = ArticleParser.parse(
            """<p>The public beginning of the post, free for everyone to read.</p>
<div class="gh-paid-content-notice"><h3>This post is for subscribers only</h3><p>Become a member to get access to all content.</p>
<a class="gh-paid-content-cta" href="https://blog.example/#/portal/signup">Subscribe now</a></div>""",
            base,
        )

        assertEquals(
            listOf(
                ArticleBlock.MembersOnly("This post is for subscribers only", "Become a member to get access to all content."),
            ),
            document.blocks.filterIsInstance<ArticleBlock.MembersOnly>(),
        )
        assertEquals(2, document.blocks.size)
        assertEquals(true, document.isLocked)
    }
}
