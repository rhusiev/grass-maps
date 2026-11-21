package nl.r1a.grassmaps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T-0629: the navigation notification's small icon is the app's own asset.
 *
 * A JVM test cannot build a [android.app.Notification] or resolve a drawable,
 * so it asserts the id the service passes through
 * [navigationNotificationSmallIcon] and the vector file that id names. The
 * status bar itself is device evidence.
 */
class NavigationNotificationIconTest {
    @Test
    fun theServiceSetsTheAppDrawableAsItsSmallIcon() {
        assertEquals(R.drawable.ic_nav_notification, navigationNotificationSmallIcon())
        assertNotEquals(android.R.drawable.ic_menu_mylocation, navigationNotificationSmallIcon())
        assertEquals(
            "the small icon must come from the app package, not the platform",
            APP_RESOURCE_PACKAGE_ID,
            R.drawable.ic_nav_notification ushr BYTE_BITS,
        )

        val source = File(SERVICE).readText()
        assertTrue("the service does not use the small-icon seam", SMALL_ICON_CALL in source)
        assertFalse("the service still passes a platform small icon", PLATFORM_SMALL_ICON in source)
    }

    @Test
    fun theIconIsOneMonochromeVectorPath() {
        val vector = document(DRAWABLE).documentElement

        assertEquals("vector", vector.tagName)
        assertEquals("24dp", vector.getAttributeNS(ANDROID_NAMESPACE, "width"))
        assertEquals("24dp", vector.getAttributeNS(ANDROID_NAMESPACE, "height"))
        assertEquals("the icon must not depend on a theme tint", "", vector.getAttributeNS(ANDROID_NAMESPACE, "tint"))

        val paths = vector.getElementsByTagName("path")
        assertEquals("the icon is one filled path", 1, paths.length)
        val path = paths.item(0) as Element
        assertEquals("#FFFFFFFF", path.getAttributeNS(ANDROID_NAMESPACE, "fillColor"))
        assertEquals("", path.getAttributeNS(ANDROID_NAMESPACE, "strokeColor"))
        assertEquals("", path.getAttributeNS(ANDROID_NAMESPACE, "fillAlpha"))
        assertTrue(
            "the path has no outline",
            path.getAttributeNS(ANDROID_NAMESPACE, "pathData").startsWith("M"),
        )
    }

    private fun document(path: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(File(path).readBytes()))

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val DRAWABLE = "src/main/res/drawable/ic_nav_notification.xml"
        const val SERVICE = "src/main/java/nl/r1a/grassmap/app/NavigationService.kt"
        const val SMALL_ICON_CALL = "setSmallIcon(navigationNotificationSmallIcon())"
        const val PLATFORM_SMALL_ICON = "setSmallIcon(android.R.drawable"

        /** Android's package id for an application's own resources is `0x7f`. */
        const val APP_RESOURCE_PACKAGE_ID = 0x7f
        const val BYTE_BITS = 24
    }
}
