package nl.r1a.grassmaps.app.devcheck

import androidx.core.content.FileProvider

/**
 * Exposes the device-check archive to the share sheet. It is a separate class
 * from the track provider so the two authorities and path sets cannot mix.
 */
class DeviceCheckFileProvider : FileProvider()
