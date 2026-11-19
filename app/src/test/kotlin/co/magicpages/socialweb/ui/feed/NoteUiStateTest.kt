package co.magicpages.socialweb.ui.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteUiStateTest {
    @Test
    fun `a note that is still its seed can be closed without asking`() {
        val seed = "\n\nhttps://example.com/a"

        assertFalse(NoteUiState(text = seed, seedText = seed).hasDraft)
        assertFalse(NoteUiState().hasDraft)
    }

    @Test
    fun `words added to the seed are a draft`() {
        val seed = "\n\nhttps://example.com/a"

        assertTrue(NoteUiState(text = "Worth reading$seed", seedText = seed).hasDraft)
    }

    @Test
    fun `a note with words can be sent, one without can't`() {
        assertTrue(NoteUiState(text = "Hello").canSend)
        assertFalse(NoteUiState(text = "  \n").canSend)
    }

    @Test
    fun `a note waits for its quote card to be drawn before it is sent`() {
        assertFalse(NoteUiState(text = "Hello", attachment = NoteAttachment.Rendering).canSend)
    }
}
