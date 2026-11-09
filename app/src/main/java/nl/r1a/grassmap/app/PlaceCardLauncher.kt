package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink
import java.util.Locale

/**
 * Maps a card link onto the platform seam (T-0210).
 *
 * The T-0208 contact links already carry `tel:` and `mailto:` targets; a
 * website or social value may omit its scheme, so only that case gets `https:`
 * in front. A website or social tag is untrusted region data: a value with any
 * other scheme is refused with [AppError.Unsupported] instead of being opened.
 * The target is never logged.
 */
internal fun PlatformActions.launch(link: PlaceCardLink): Outcome<Unit> =
    when (link.action) {
        PlaceCardAction.CALL,
        PlaceCardAction.SEND_EMAIL,
        PlaceCardAction.OPEN_IN,
        -> {
            openUri(link.target)
        }

        PlaceCardAction.OPEN_WEBSITE,
        PlaceCardAction.OPEN_SOCIAL,
        -> {
            when (val web = webUri(link.target)) {
                is Outcome.Failure -> web
                is Outcome.Success -> openUri(web.value)
            }
        }

        PlaceCardAction.COPY_COORDINATES -> {
            copyToClipboard(link.target)
        }

        PlaceCardAction.SHARE -> {
            shareText(link.target)
        }
    }

/**
 * A web target the browser may open: `http` or `https` in any letter case, or a
 * bare host/domain (with an optional port and path) that gets `https://`.
 *
 * Any other scheme is [AppError.Unsupported]. That covers `intent:`, `file:`,
 * `content:`, `javascript:` and `data:` values that an OSM `website` or
 * `contact:*` tag could carry. `javascript:` has no `://`, so a check for that
 * text alone would turn it into an `https:` host and open it; the scheme is
 * therefore parsed first.
 *
 * The function is pure and free of Android types, so a JVM test pins the
 * decision.
 */
internal fun webUri(target: String): Outcome<String> {
    val trimmed = target.trim()
    if (trimmed.isEmpty()) {
        return unsupported()
    }
    val schemeMatch = SCHEME_PREFIX.find(trimmed)
    if (schemeMatch != null) {
        val scheme = schemeMatch.groupValues[1]
        val looksLikeHost = '.' in scheme || scheme.equals("localhost", ignoreCase = true)
        if (!looksLikeHost) {
            val normalized = scheme.lowercase(Locale.ROOT)
            if (normalized !in ALLOWED_WEB_SCHEMES) {
                return unsupported()
            }
            return Outcome.Success(normalized + trimmed.substring(scheme.length))
        }
    }
    if ("://" in trimmed) {
        return unsupported()
    }
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
        return unsupported()
    }
    if (trimmed.startsWith("//")) {
        return Outcome.Success("https:$trimmed")
    }
    return Outcome.Success("https://$trimmed")
}

private fun unsupported(): Outcome.Failure = Outcome.Failure(AppError.Unsupported)

/** `scheme:` at the start of a value; RFC 3986 letters, digits and `+.-`. */
private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

private val ALLOWED_WEB_SCHEMES = setOf("http", "https")
