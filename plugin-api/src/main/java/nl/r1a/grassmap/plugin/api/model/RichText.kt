package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.Serializable

/** The inline styles a safe description may keep (T-0414). */
@Serializable
enum class RichTextStyle {
    BOLD,
    ITALIC,
    LINK,
}

/**
 * One styled run inside [RichText.text]. [start] and [end] are UTF-16 offsets
 * into that string. [href] is set only for [RichTextStyle.LINK] and holds an
 * `http` or `https` address the host may open in the system browser.
 */
@Serializable
data class RichTextSpan(
    val start: Int,
    val end: Int,
    val style: RichTextStyle,
    val href: String? = null,
)

/**
 * A sanitised description that keeps safe inline formatting (T-0414).
 *
 * [text] is plain native text with line breaks between paragraphs and list
 * items; [spans] carries the bold, italic and link runs. Nothing in this model
 * is markup, so a screen can render it without a WebView. The renderer drops
 * every other tag, every attribute but a link's `href`, and any link that is
 * not `http` or `https`, so an import cannot load a remote image, run a script
 * or follow a `javascript:` address.
 */
@Serializable
data class RichText(
    val text: String,
    val spans: List<RichTextSpan> = emptyList(),
)
