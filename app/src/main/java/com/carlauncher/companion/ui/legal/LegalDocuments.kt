package com.carlauncher.companion.ui.legal

import android.content.Context
import androidx.annotation.StringRes
import com.carlauncher.companion.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two documents a user must be able to read before creating an account. */
enum class LegalDocument(val asset: String, @param:StringRes val titleRes: Int) {
    TERMS("terms_of_use.md", R.string.auth_terms_of_use),
    PRIVACY("privacy_policy.md", R.string.auth_privacy_policy),
}

/**
 * Renders one of the bundled legal documents.
 *
 * Deliberately a tiny hand-rolled Markdown subset rather than a dependency: these two files
 * are the only Markdown the app will ever display, and they are written to stay inside what
 * this handles (headings, bullets, bold, paragraphs). If a document ever needs tables or
 * links, change the document — not this.
 */
@Composable
fun LegalDocumentScreen(document: LegalDocument, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var blocks by remember(document) { mutableStateOf<List<MarkdownBlock>>(emptyList()) }

    LaunchedEffect(document) {
        blocks = withContext(Dispatchers.IO) { parseMarkdown(readAsset(context, document.asset)) }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
    ) {
        items(blocks) { block -> MarkdownBlockView(block) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> Column {
            Spacer(Modifier.height(if (block.level <= 1) 8.dp else 20.dp))
            if (block.level <= 1) {
                Text(block.text, style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(block.text, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
        }

        is MarkdownBlock.Bullet -> Text(
            inlineStyled("•  ${block.text}"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        is MarkdownBlock.Paragraph -> Text(
            inlineStyled(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        is MarkdownBlock.Rule -> Spacer(Modifier.height(4.dp))
    }
}

/** A "**bold**"-aware AnnotatedString. Nothing else is interpreted. */
@Composable
private fun inlineStyled(raw: String) = buildAnnotatedString {
    var rest = raw
    while (true) {
        val open = rest.indexOf("**")
        if (open < 0) { append(rest); return@buildAnnotatedString }
        val close = rest.indexOf("**", open + 2)
        if (close < 0) { append(rest); return@buildAnnotatedString }

        append(rest.substring(0, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
            append(rest.substring(open + 2, close))
        }
        rest = rest.substring(close + 2)
    }
}

internal sealed interface MarkdownBlock {
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

internal fun readAsset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader().use { it.readText() }

/**
 * Consecutive non-blank, non-special lines are joined into one paragraph, so the source
 * files can stay hard-wrapped at a readable width without the app rendering every wrap as
 * a line break.
 */
internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    // Authoring notes for whoever maintains the documents; never shown to users.
    val withoutComments = source.replace(Regex("(?s)<!--.*?-->"), "")

    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
        paragraph.setLength(0)
    }

    withoutComments.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()

            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(trimmed.drop(level).trim(), level)
            }

            trimmed == "---" -> {
                flushParagraph()
                blocks += MarkdownBlock.Rule
            }

            trimmed.startsWith("- ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(trimmed.removePrefix("- ").trim())
            }

            // Continuation of the bullet above, from a hard-wrapped source line.
            blocks.lastOrNull() is MarkdownBlock.Bullet && paragraph.isEmpty() -> {
                val last = blocks.removeAt(blocks.lastIndex) as MarkdownBlock.Bullet
                blocks += MarkdownBlock.Bullet("${last.text} $trimmed")
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    flushParagraph()
    return blocks
}
