package co.magicpages.socialweb.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class BioHtmlTest {
    @Test
    fun `WriteFreely's Markdown links become HTML links`() {
        // @matt@write.as's actor summary, as write.as sends it.
        assertEquals(
            "Founder, <a href=\"https://musing.studio\">Musing Studio</a> / <a href=\"https://write.as\">Write.as</a>.",
            bioHtml("Founder, [Musing Studio](https://musing.studio) / [Write.as](https://write.as)."),
        )
    }

    @Test
    fun `HTML bios are left alone`() {
        val mastodon = "<p>Writes about *stars* at <a href=\"https://example.com/a__b__c\">[home](https://x.y)</a></p>"
        assertEquals(mastodon, bioHtml(mastodon))
    }

    @Test
    fun `bold, italics and line breaks`() {
        assertEquals("<b>Editor</b> at <i>The Paper</i><br><br>Berlin", bioHtml("**Editor** at *The Paper*\n\nBerlin"))
    }

    @Test
    fun `emphasis stays out of link URLs and out of plain asterisks and underscores`() {
        assertEquals(
            "<a href=\"https://example.com/a__b__c\"><b>Site</b></a> · 2 * 3 = 6 · @first_last_name",
            bioHtml("[**Site**](https://example.com/a__b__c) · 2 * 3 = 6 · @first_last_name"),
        )
    }

    @Test
    fun `angle brackets in plain text don't become tags`() {
        assertEquals("I &lt;3 Ghost", bioHtml("I <3 Ghost"))
    }
}
