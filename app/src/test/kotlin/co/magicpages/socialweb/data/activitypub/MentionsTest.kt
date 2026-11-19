package co.magicpages.socialweb.data.activitypub

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import co.magicpages.socialweb.ui.components.MentionToken
import co.magicpages.socialweb.ui.components.insertMention
import co.magicpages.socialweb.ui.components.mentionAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    private fun author(handle: String, name: String = "", followedByMe: Boolean = false) =
        Author(id = "https://example.com/$handle", handle = handle, name = name, followedByMe = followedByMe)

    private val murat = author("@murat@muratcorlu.com", "Murat Çorlu")
    private val otherMurat = author("@murat@mastodon.social", "Murat Stranger")
    private val muriel = author("@muriel@example.org", "Muriel")

    @Test
    fun `people the writer knows come before strangers, even ones who fit better`() {
        val murphy = author("@imm@isemurphy.com", "Íse Murphy-Morris")
        val ranked = rankMentions(
            query = "mu",
            known = listOf(Acquaintance(murphy, following = true, follower = true)),
            found = listOf(otherMurat, murat),
            ownHandle = "@hey@magicpages.co",
        )
        assertEquals(listOf(murphy.handle, otherMurat.handle, murat.handle), ranked.map { it.handle })
    }

    @Test
    fun `someone the writer follows comes before a stranger who fits as well`() {
        val ranked = rankMentions(
            query = "murat",
            known = listOf(Acquaintance(murat, following = true)),
            found = listOf(otherMurat, murat),
            ownHandle = "@hey@magicpages.co",
        )
        assertEquals(listOf(murat.handle, otherMurat.handle), ranked.map { it.handle })
    }

    @Test
    fun `a full handle beats every relation`() {
        val ranked = rankMentions(
            query = "murat@mastodon.social",
            known = listOf(Acquaintance(murat, following = true, follower = true, talkedRecently = true)),
            found = listOf(otherMurat),
            ownHandle = null,
        )
        assertEquals(listOf(otherMurat.handle), ranked.map { it.handle })
    }

    @Test
    fun `names match without accents and by any word`() {
        val ranked = rankMentions("corlu", listOf(Acquaintance(murat), Acquaintance(muriel)), emptyList(), null)
        assertEquals(listOf(murat.handle), ranked.map { it.handle })
    }

    @Test
    fun `the writer is never offered, and search results merge into known people`() {
        val me = author("@hey@magicpages.co", "Magic Pages")
        val ranked = rankMentions(
            query = "",
            known = listOf(Acquaintance(me, follower = true), Acquaintance(muriel, follower = true)),
            found = listOf(author(muriel.handle, "Muriel", followedByMe = true)),
            ownHandle = "@hey@magicpages.co",
        )
        assertEquals(listOf(muriel), ranked)
    }

    @Test
    fun `a mention is found only in a word that starts with the at sign`() {
        assertEquals(MentionToken(6, 12, "murat"), mentionAt(TextFieldValue("Hello @murat", TextRange(12))))
        assertEquals(MentionToken(0, 14, "mu"), mentionAt(TextFieldValue("@murat@example", TextRange(3))))
        assertNull(mentionAt(TextFieldValue("mail me@murat", TextRange(13))))
        assertNull(mentionAt(TextFieldValue("Hello @murat ", TextRange(13))))
    }

    @Test
    fun `picking replaces the whole word and leaves the cursor after the mention`() {
        val value = TextFieldValue("Hi @mur, how are you", TextRange(7))
        val picked = insertMention(value, mentionAt(value)!!, murat.handle)
        assertEquals("Hi @murat@muratcorlu.com, how are you", picked.text)
        assertEquals(TextRange(24), picked.selection)

        val atEnd = TextFieldValue("Hi @mu", TextRange(6))
        assertEquals(TextFieldValue("Hi @murat@muratcorlu.com ", TextRange(25)), insertMention(atEnd, mentionAt(atEnd)!!, murat.handle))

        val beforeSpace = TextFieldValue("Hi @mur and you", TextRange(7))
        val spaced = insertMention(beforeSpace, mentionAt(beforeSpace)!!, murat.handle)
        assertEquals("Hi @murat@muratcorlu.com and you", spaced.text)
        assertEquals(TextRange(25), spaced.selection)
    }
}
