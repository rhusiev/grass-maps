package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.PlaceCard
import nl.r1a.grassmap.plugin.api.provider.PlaceHit
import nl.r1a.grassmap.plugins.share.ShareCard

/**
 * The card for an online search hit (T-0212).
 *
 * A Photon id has no `places.details` record, so the card carries only what the
 * hit itself holds: its name, category, composed address and point. The
 * `online` mark keeps every tag-derived row and action off the card: call,
 * website, email and social all need the region record's tags. Copy, share and
 * open-in need only the point, which the hit already carries. The function is
 * pure: opening the card makes no request and reads no cache.
 */
internal fun onlinePlaceCard(hit: PlaceHit): PlaceCard =
    ShareCard.decoratePoint(
        card =
            PlaceCard(
                name = hit.name.trim().takeIf { it.isNotEmpty() },
                kind =
                    hit.kind
                        .substringAfterLast('=')
                        .trim()
                        .takeIf { it.isNotEmpty() },
                address = hit.address?.trim()?.takeIf { it.isNotEmpty() },
                online = true,
            ),
        name = hit.name,
        point = hit.position,
    )
