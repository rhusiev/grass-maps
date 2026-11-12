package nl.r1a.grassmap.tracks

import nl.r1a.grassmap.plugin.api.model.ImportWarning
import nl.r1a.grassmap.plugin.api.model.ImportWarningCode

/**
 * A stable English diagnostic for one skipped part of a document, in the form
 * `<locator>: <what happened>` such as `trk[0]/trkseg[1]: empty segment dropped`.
 *
 * This is log and test text, not UI text. The library screen (T-0414) writes
 * its own localized sentence from the warning code and locator; the code is the
 * stable part, and this line never leaves the log.
 */
fun ImportWarning.describe(): String {
    val where = detail ?: DOCUMENT
    return "$where: ${code.describe()}"
}

/** The phrase for [ImportWarningCode], shared by [describe]. */
fun ImportWarningCode.describe(): String =
    when (this) {
        ImportWarningCode.MissingCoordinates -> "missing coordinates"
        ImportWarningCode.OutOfRangeCoordinates -> "coordinates outside WGS84"
        ImportWarningCode.InvalidElevation -> "unreadable elevation, point kept"
        ImportWarningCode.InvalidTime -> "unreadable time, point kept"
        ImportWarningCode.EmptySegment -> "empty segment dropped"
        ImportWarningCode.EmptyTrack -> "empty track dropped"
        ImportWarningCode.EmptyRoute -> "empty route dropped"
    }

private const val DOCUMENT = "document"
