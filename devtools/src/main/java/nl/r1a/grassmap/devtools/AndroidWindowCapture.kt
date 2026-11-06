package nl.r1a.grassmap.devtools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nl.r1a.grassmap.control.ControlCapture
import nl.r1a.grassmap.control.ControlScreenshot
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Captures the current activity window as PNG for the control channel.
 *
 * The window arrives from the debug application's activity callbacks, so the
 * control module never sees an Android type. Capture tries `PixelCopy` first,
 * which is the only path that sees surface content such as the map, and falls
 * back to drawing the view hierarchy, which is what a window with no surface
 * content (the current skeleton screen) needs. The response names the path that
 * produced the bytes.
 */
class AndroidWindowCapture : ControlScreenshot {
    private val currentWindow = AtomicReference<Window?>(null)

    fun attach(window: Window?) {
        currentWindow.set(window)
    }

    fun detach(window: Window?) {
        currentWindow.compareAndSet(window, null)
    }

    override suspend fun capturePng(): Outcome<ControlCapture> {
        val window = currentWindow.get() ?: return Outcome.Failure(AppError.NotReady)
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) {
            return Outcome.Failure(AppError.NotReady)
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val captured =
            when (val copied = copyWindow(window, bitmap)) {
                is Outcome.Success -> copied
                is Outcome.Failure -> drawWindow(window, bitmap)
            }
        return when (captured) {
            is Outcome.Failure -> {
                bitmap.recycle()
                captured
            }

            is Outcome.Success -> {
                encode(captured.value.bitmap, captured.value.source)
            }
        }
    }

    private suspend fun copyWindow(
        window: Window,
        bitmap: Bitmap,
    ): Outcome<Captured> =
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(Outcome.Success(Captured(PIXEL_COPY, bitmap)))
                        } else {
                            continuation.resume(Outcome.Failure(AppError.Internal("devtools.pixel_copy")))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (exception: IllegalArgumentException) {
                continuation.resume(Outcome.Failure(AppError.Internal("devtools.pixel_copy")))
            } catch (exception: SecurityException) {
                continuation.resume(Outcome.Failure(AppError.Internal("devtools.pixel_copy")))
            }
        }

    private suspend fun drawWindow(
        window: Window,
        bitmap: Bitmap,
    ): Outcome<Captured> =
        withContext(Dispatchers.Main) {
            try {
                window.decorView.draw(Canvas(bitmap))
                Outcome.Success(Captured(VIEW_DRAW, bitmap))
            } catch (exception: RuntimeException) {
                Outcome.Failure(AppError.Internal("devtools.view_draw"))
            }
        }

    private fun encode(
        bitmap: Bitmap,
        source: String,
    ): Outcome<ControlCapture> {
        val bytes = ByteArrayOutputStream()
        return try {
            val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, bytes)
            if (compressed) {
                Outcome.Success(ControlCapture(png = bytes.toByteArray(), source = source))
            } else {
                Outcome.Failure(AppError.Internal("devtools.screenshot.encode"))
            }
        } catch (exception: RuntimeException) {
            Outcome.Failure(AppError.Internal("devtools.screenshot.encode"))
        } finally {
            bitmap.recycle()
        }
    }

    private class Captured(
        val source: String,
        val bitmap: Bitmap,
    )

    private companion object {
        const val PIXEL_COPY = "pixel_copy"
        const val VIEW_DRAW = "view_draw"
        const val PNG_QUALITY = 100
    }
}
