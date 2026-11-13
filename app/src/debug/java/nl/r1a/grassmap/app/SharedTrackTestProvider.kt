package nl.r1a.grassmap.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * The share-sheet import's document source for debug builds (T-0416).
 *
 * Another app's share arrives as a `content:` URI, so a device check cannot
 * use a plain file: the URI is what exercises the resolver, the provider's
 * type, its display name and its size. This provider serves the committed
 * `mock-walk.gpx` asset at three addresses:
 *
 * - `content://nl.r1a.grassmap.share-test/walk` - a GPX document with no
 *   extension in its display name, so the type is what names the format;
 * - `content://nl.r1a.grassmap.share-test/notes` - a document that is not a
 *   track;
 * - `content://nl.r1a.grassmap.share-test/unreadable` - a document whose read
 *   throws `SecurityException`, the refusal a revoked grant produces.
 *
 * The provider is debug-only: `src/debug/AndroidManifest.xml` declares it and
 * a release build never carries it. It is not exported, so only this app and
 * its instrumented tests, which run in the app's process, can read it.
 */
class SharedTrackTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        when (uri.lastPathSegment) {
            WALK -> GPX_MIME_TYPE
            NOTES -> TEXT_MIME_TYPE
            UNREADABLE -> GPX_MIME_TYPE
            else -> null
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no document in $uri")
        val bytes = contentOf(name)
        val columns = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> name
                    OpenableColumns.SIZE -> bytes?.size?.toLong()
                    else -> null
                }
            },
        )
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no document in $uri")
        if (name == UNREADABLE) {
            throw SecurityException("the test provider refuses $name")
        }
        val bytes = contentOf(name) ?: throw FileNotFoundException("no document named $name")
        val directory = File(contextOrFail().cacheDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw FileNotFoundException("cannot create $directory")
        }
        val file = File(directory, name)
        file.writeBytes(bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun contentOf(name: String): ByteArray? =
        when (name) {
            WALK -> contextOrFail().assets.open(ASSET).use { stream -> stream.readBytes() }
            NOTES -> NOT_A_TRACK.toByteArray()
            else -> null
        }

    private fun contextOrFail() = context ?: throw FileNotFoundException("the provider has no context")

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "nl.r1a.grassmap.share-test"
        const val WALK = "walk"
        const val NOTES = "notes"
        const val UNREADABLE = "unreadable"

        const val GPX_MIME_TYPE = "application/gpx+xml"
        private const val TEXT_MIME_TYPE = "text/plain"
        private const val ASSET = "mock-walk.gpx"
        private const val NOT_A_TRACK = "This is not a track\n"
        private const val DIRECTORY = "shared-test"
    }
}
