package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.PlatformActions
import nl.r1a.grassmap.plugin.api.model.PlaceCardAction
import nl.r1a.grassmap.plugin.api.model.PlaceCardLink

/**
 * Maps a card link onto the platform seam (T-0210).
 *
 * The T-0208 contact links already carry `tel:` and `mailto:` targets; a
 * website or social value may omit its scheme, so only that case gets `https:`.
 * The target is never logged.
 */
internal fun PlatformActions.launch(link: PlaceCardLink): Outcome<Unit> =
    when (link.action) {
        PlaceCardAction.CALL,
        PlaceCardAction.SEND_EMAIL,
        PlaceCardAction.OPEN_IN,
        -> openUri(link.target)

        PlaceCardAction.OPEN_WEBSITE,
        PlaceCardAction.OPEN_SOCIAL,
        -> openUri(webUri(link.target))

        PlaceCardAction.COPY_COORDINATES -> copyToClipboard(link.target)

        PlaceCardAction.SHARE -> shareText(link.target)
    }

/** A web target with a scheme, or the same value with `https://` in front. */
internal fun webUri(target: String): String = if ("://" in target) target else "https://$target"
