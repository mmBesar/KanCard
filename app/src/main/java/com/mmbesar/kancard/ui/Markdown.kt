package com.mmbesar.kancard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// A small Markdown reader (no extra library). Kanboard descriptions are Markdown, so KanCard shows:
// headings, **bold**, *italic*, ~~strike~~, `code`, code blocks, lists (also [ ] / [x] checkboxes),
// > quotes, --- lines and links. Every paragraph follows ITS OWN text direction, so Arabic/Hebrew
// and English can be mixed freely.

/** Colours the reader needs (it has no access to the theme itself, which keeps it easy to test). */
data class MdTheme(val link: Color, val codeBackground: Color)

/** One block of a Markdown text. */
sealed interface MdBlock {
    data class Para(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Item(val marker: String, val depth: Int, val text: AnnotatedString) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Direction of the first "strong" letter (Arabic/Hebrew = right to left); null if there is none. */
fun contentDirection(text: CharSequence): LayoutDirection? {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return LayoutDirection.Ltr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> return LayoutDirection.Rtl
            else -> Unit
        }
    }
    return null
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!~>|"
private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val ruleRegex = Regex("^([-*_])(\\s*\\1){2,}$")
private val bulletRegex = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")

/** Only ordinary web and mail links are made clickable (never javascript:, intent: and the like). */
private fun isSafeUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:")
}

/** Reads one line (or paragraph) of Markdown into styled text. */
fun inlineMarkdown(source: String, theme: MdTheme): AnnotatedString =
    buildAnnotatedString { parseInline(source, theme, this) }

/** Finds the closing single [mark] (a lone * or _), or -1. Doubled marks are skipped. */
private fun findSingleClose(s: String, mark: Char, from: Int): Int {
    var j = from
    while (j < s.length) {
        val c = s[j]
        if (c == '\\') {
            j += 2
            continue
        }
        if (c == mark) {
            if (j + 1 < s.length && s[j + 1] == mark) {
                j += 2
                continue
            }
            val spaceBefore = s[j - 1].isWhitespace()
            val wordAfter = mark == '_' && j + 1 < s.length && s[j + 1].isLetterOrDigit()
            if (!spaceBefore && !wordAfter) return j
        }
        j++
    }
    return -1
}

private fun parseInline(s: String, theme: MdTheme, out: AnnotatedString.Builder) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            out.append(plain.toString())
            plain.clear()
        }
    }

    var i = 0
    while (i < s.length) {
        val c = s[i]

        // \* and friends: the next character is shown as it is.
        if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
            plain.append(s[i + 1])
            i += 2
            continue
        }

        // `code`
        if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                flush()
                out.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = theme.codeBackground)) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // **bold** and __bold__
        if (s.startsWith("**", i) || s.startsWith("__", i)) {
            val mark = s.substring(i, i + 2)
            val end = s.indexOf(mark, i + 2)
            if (end > i + 2) {
                flush()
                val inner = s.substring(i + 2, end)
                out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { parseInline(inner, theme, this) }
                i = end + 2
                continue
            }
        }

        // ~~strike~~
        if (s.startsWith("~~", i)) {
            val end = s.indexOf("~~", i + 2)
            if (end > i + 2) {
                flush()
                val inner = s.substring(i + 2, end)
                out.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { parseInline(inner, theme, this) }
                i = end + 2
                continue
            }
        }

        // *italic* and _italic_ (an underscore inside a word, like snake_case, stays as it is)
        if (c == '*' || c == '_') {
            val boundaryOk = c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()
            if (boundaryOk && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                val end = findSingleClose(s, c, i + 1)
                if (end > i + 1) {
                    flush()
                    val inner = s.substring(i + 1, end)
                    out.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { parseInline(inner, theme, this) }
                    i = end + 1
                    continue
                }
            }
        }

        // [label](https://...)
        if (c == '[') {
            val mid = s.indexOf("](", i + 1)
            val close = if (mid > 0) s.indexOf(')', mid + 2) else -1
            if (mid > 0 && close > 0) {
                val url = s.substring(mid + 2, close).trim()
                if (isSafeUrl(url)) {
                    flush()
                    val label = s.substring(i + 1, mid)
                    val style = TextLinkStyles(SpanStyle(color = theme.link, textDecoration = TextDecoration.Underline))
                    out.withLink(LinkAnnotation.Url(url, style)) { parseInline(label, theme, this) }
                    i = close + 1
                    continue
                }
            }
        }

        // A bare https://... address
        if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i)) &&
            (i == 0 || !s[i - 1].isLetterOrDigit())
        ) {
            var end = i
            while (end < s.length && !s[end].isWhitespace()) end++
            while (end > i && s[end - 1] in ".,;:!?)\"'") end--
            val url = s.substring(i, end)
            flush()
            val style = TextLinkStyles(SpanStyle(color = theme.link, textDecoration = TextDecoration.Underline))
            out.withLink(LinkAnnotation.Url(url, style)) { append(url) }
            i = end
            continue
        }

        plain.append(c)
        i++
    }
    flush()
}

/** Splits a Markdown text into blocks. Line breaks inside a paragraph are kept (as Kanboard does). */
fun parseMarkdown(source: String, theme: MdTheme): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Para(inlineMarkdown(paragraph.joinToString("\n"), theme))
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // ``` code block ```
        val isFence = (trimmed.startsWith("```") && !trimmed.drop(3).contains("```")) ||
            (trimmed.startsWith("~~~") && trimmed.all { it == '~' || it.isWhitespace() || it.isLetterOrDigit() } && !trimmed.drop(3).contains("~~~"))
        if (isFence) {
            flushParagraph()
            val fence = trimmed.take(3)
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                code += lines[i]
                i++
            }
            i++ // the closing fence
            blocks += MdBlock.Code(code.joinToString("\n"))
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        val heading = headingRegex.find(trimmed)
        if (heading != null) {
            flushParagraph()
            val text = heading.groupValues[2].replace(Regex("\\s+#+\\s*$"), "").trim()
            blocks += MdBlock.Heading(heading.groupValues[1].length, inlineMarkdown(text, theme))
            i++
            continue
        }

        if (ruleRegex.matches(trimmed)) {
            flushParagraph()
            blocks += MdBlock.Rule
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoted = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoted += lines[i].trim().removePrefix(">").removePrefix(" ")
                i++
            }
            blocks += MdBlock.Quote(inlineMarkdown(quoted.joinToString("\n"), theme))
            continue
        }

        val bullet = bulletRegex.find(line)
        val ordered = orderedRegex.find(line)
        if (bullet != null || ordered != null) {
            flushParagraph()
            val match = bullet ?: ordered!!
            val depth = match.groupValues[1].length / 2
            var text = match.groupValues[3]
            var marker = if (bullet != null) "•" else match.groupValues[2] + "."
            if (text.startsWith("[ ] ") || text == "[ ]") {
                marker = "☐"
                text = text.removePrefix("[ ]").trimStart()
            } else if (text.startsWith("[x] ", ignoreCase = true) || text.equals("[x]", ignoreCase = true)) {
                marker = "☑"
                text = text.substring(3).trimStart()
            }
            blocks += MdBlock.Item(marker, depth, inlineMarkdown(text, theme))
            i++
            continue
        }

        paragraph += line.trimEnd()
        i++
    }
    flushParagraph()
    return blocks
}

/** A short, flat version for task cards: markers are gone, lists become "• ", all on a few lines. */
fun markdownPreview(source: String, theme: MdTheme): AnnotatedString = buildAnnotatedString {
    parseMarkdown(source, theme).forEachIndexed { index, block ->
        if (index > 0) append("\n")
        when (block) {
            is MdBlock.Para -> append(block.text)
            is MdBlock.Heading -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(block.text) }
            is MdBlock.Item -> {
                append(block.marker + " ")
                append(block.text)
            }
            is MdBlock.Quote -> append(block.text)
            is MdBlock.Code -> append(block.text)
            MdBlock.Rule -> Unit
        }
    }
}

/** Shows a Markdown text. Each block is laid out in the direction of its own language. */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val theme = MdTheme(
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
    )
    val blocks = remember(source, theme) { parseMarkdown(source, theme) }
    val base = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    // A tapped link must never crash the app, even if no app on the phone can open it.
    val handler = LocalUriHandler.current
    val safeHandler = remember(handler) { SafeUriHandler(handler) }
    CompositionLocalProvider(LocalUriHandler provides safeHandler) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block -> MarkdownBlockView(block, base) }
        }
    }
}

/** Opens links like the normal handler, but swallows "no app can open this". */
private class SafeUriHandler(private val inner: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        try {
            inner.openUri(uri)
        } catch (e: Exception) {
            // Nothing to open it with: ignore.
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock, base: TextStyle) {
    val plain = when (block) {
        is MdBlock.Para -> block.text.text
        is MdBlock.Heading -> block.text.text
        is MdBlock.Item -> block.text.text
        is MdBlock.Quote -> block.text.text
        is MdBlock.Code -> block.text
        MdBlock.Rule -> ""
    }
    // Code is always left to right; everything else follows its first letter.
    val direction = if (block is MdBlock.Code) LayoutDirection.Ltr
    else contentDirection(plain) ?: LocalLayoutDirection.current

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        when (block) {
            is MdBlock.Para -> Text(block.text, style = base, modifier = Modifier.fillMaxWidth())

            is MdBlock.Heading -> {
                val typography = MaterialTheme.typography
                val style = when (block.level) {
                    1 -> typography.headlineSmall
                    2 -> typography.titleLarge
                    3 -> typography.titleMedium
                    else -> typography.titleSmall
                }
                Text(
                    block.text,
                    style = style.copy(textDirection = TextDirection.Content, fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is MdBlock.Item -> Row(modifier = Modifier.fillMaxWidth().padding(start = (block.depth * 16).dp)) {
                Text(block.marker, style = base, modifier = Modifier.widthIn(min = 24.dp))
                Text(block.text, style = base, modifier = Modifier.weight(1f))
            }

            is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    block.text,
                    style = base.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                )
            }

            is MdBlock.Code -> Text(
                block.text,
                style = base.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            )

            MdBlock.Rule -> HorizontalDivider()
        }
    }
}
