package nl.r1a.grassmaps.plugins.downloads.ui

/**
 * The on-screen downloads switch and its off-state alert (T-0660).
 *
 * The screen draws its words from this pure derivation, so a JVM test can state
 * what a fresh install sees without a Compose runtime. The alert names what
 * enabling downloads lets the app do; it never promises that the switch itself
 * sends anything.
 */
data class DownloadsSwitchNotice(
    val title: String,
    val stateText: String,
    val privacyNote: String,
    val checked: Boolean,
    val alert: Alert?,
) {
    /** The card that replaces the plain red text while downloads are off. */
    data class Alert(
        val message: String,
        val action: String,
    )
}

/** The words [DownloadsSwitchNotice] draws, all from the screen's bundles. */
data class DownloadsSwitchLabels(
    val title: String,
    val onText: String,
    val offText: String,
    val privacyNote: String,
    val offMessage: String,
    val turnOnAction: String,
)

/** The switch is visible in both states; only its alert comes and goes. */
fun downloadsSwitchNotice(
    labels: DownloadsSwitchLabels,
    enabled: Boolean,
): DownloadsSwitchNotice =
    DownloadsSwitchNotice(
        title = labels.title,
        stateText = if (enabled) labels.onText else labels.offText,
        privacyNote = labels.privacyNote,
        checked = enabled,
        alert =
            if (enabled) {
                null
            } else {
                DownloadsSwitchNotice.Alert(labels.offMessage, labels.turnOnAction)
            },
    )
