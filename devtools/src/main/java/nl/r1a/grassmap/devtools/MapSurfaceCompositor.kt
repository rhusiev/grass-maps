package nl.r1a.grassmap.devtools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Rebuilds a window capture that includes the map surface.
 *
 * A window `PixelCopy` can return the map area as a plain fill on some devices,
 * because the map renders on its own surface. This reads the largest map
 * surface (a `SurfaceView` or a `TextureView`) directly. A `SurfaceView` clears
 * its area to transparent when drawn on a software canvas, so the content is
 * drawn first and the surface bitmap is composited behind it at its window
 * position.
 */
internal object MapSurfaceCompositor {
    private const val NO_SURFACE_AREA = 0

    /** Returns the composed bitmap, or null when the window has no visible surface view or the read failed. */
    suspend fun compose(window: Window): Bitmap? {
        val surface = withContext(Dispatchers.Main) { largestSurface(window.decorView) } ?: return null
        val surfaceBitmap = readSurface(surface) ?: return null
        return withContext(Dispatchers.Main) { overlayContent(window, surface, surfaceBitmap) }
    }

    private fun largestSurface(root: View): View? {
        val found = mutableListOf<View>()
        collect(root, found)
        return found.maxByOrNull { it.width * it.height }?.takeIf { it.width * it.height > NO_SURFACE_AREA }
    }

    private fun collect(
        view: View,
        into: MutableList<View>,
    ) {
        if (view.visibility != View.VISIBLE) return
        if (view is SurfaceView || view is TextureView) into += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index), into)
        }
    }

    private suspend fun readSurface(view: View): Bitmap? =
        when (view) {
            is TextureView -> withContext(Dispatchers.Main) { view.getBitmap(view.width, view.height) }
            is SurfaceView -> copySurface(view)
            else -> null
        }

    private suspend fun copySurface(view: SurfaceView): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(
                    view,
                    bitmap,
                    { result -> continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (exception: IllegalArgumentException) {
                continuation.resume(null)
            }
        }

    private fun overlayContent(
        window: Window,
        surface: View,
        surfaceBitmap: Bitmap,
    ): Bitmap {
        val decor = window.decorView
        val out = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        decor.draw(canvas)
        val origin = IntArray(LOCATION_SIZE)
        surface.getLocationInWindow(origin)
        val behind = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
        canvas.drawBitmap(surfaceBitmap, origin[0].toFloat(), origin[1].toFloat(), behind)
        surfaceBitmap.recycle()
        return out
    }

    private const val LOCATION_SIZE = 2
}
