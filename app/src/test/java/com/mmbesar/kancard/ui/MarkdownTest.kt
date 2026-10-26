package com.mmbesar.kancard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the Markdown reader and the text-direction helper (no phone needed). */
class MarkdownTest {

    private val theme = MdTheme(link = Color.Blue, codeBackground = Color.Gray)

    private fun inline(text: String) = inlineMarkdown(text, theme).text

    @Test
    fun markersDisappearFromInlineText() {
        assertEquals("bold italic strike code", inline("**bold** *italic* ~~strike~~ `code`"))
    }

    @Test
    fun lonelyStarsAndSnakeCaseStayAsTyped() {
        assertEquals("2 * 3 * 4", inline("2 * 3 * 4"))
        assertEquals("my_file_name", inline("my_file_name"))
    }

    @Test
    fun escapedMarkersStayAsTyped() {
        assertEquals("*not italic*", inline("\\*not italic\\*"))
    }

    @Test
    fun linksKeepOnlyTheirLabel() {
        assertEquals("site and https://example.com", inline("[site](https://example.com) and https://example.com"))
    }

    @Test
    fun unsafeLinksAreShownAsPlainText() {
        assertEquals("[x](javascript:alert(1))", inline("[x](javascript:alert(1))"))
    }

    @Test
    fun blocksAreRecognised() {
        val blocks = parseMarkdown(
            "# Title\n\nSome text\nsecond line\n\n- one\n- [x] done\n1. first\n\n> quote\n\n---\n```\ncode here\n```",
            theme,
        )
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals("Some text\nsecond line", (blocks[1] as MdBlock.Para).text.text)
        assertEquals("•", (blocks[2] as MdBlock.Item).marker)
        assertEquals("☑", (blocks[3] as MdBlock.Item).marker)
        assertEquals("1.", (blocks[4] as MdBlock.Item).marker)
        assertTrue(blocks[5] is MdBlock.Quote)
        assertTrue(blocks[6] is MdBlock.Rule)
        assertEquals("code here", (blocks[7] as MdBlock.Code).text)
    }

    @Test
    fun emptyTextHasNoBlocks() {
        assertTrue(parseMarkdown("", theme).isEmpty())
    }

    @Test
    fun directionFollowsTheFirstLetter() {
        assertEquals(LayoutDirection.Ltr, contentDirection("Hello مرحبا"))
        assertEquals(LayoutDirection.Rtl, contentDirection("مرحبا hello"))
        assertEquals(LayoutDirection.Rtl, contentDirection("123 - مرحبا"))
        assertNull(contentDirection("123 !?"))
    }
}
