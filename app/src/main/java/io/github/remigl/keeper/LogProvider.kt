package io.github.remigl.keeper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.remigl.keeper.log.LogStore
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access to the two log files, for "Ouvrir le log".
 * Not exported: another app can only read the single file it was handed, through a temporary
 * URI grant created by an explicit user action. Only `yyyy-MM-dd.log` names are served (no path traversal).
 * Uri: content://<applicationId>.logs/<yyyy-MM-dd>.log
 */
class LogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = if (resolve(uri) != null) MIME else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val file = resolve(uri)?.takeIf { it.isFile } ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri)?.takeIf { it.isFile } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun resolve(uri: Uri): File? {
        val name = uri.pathSegments.singleOrNull() ?: return null
        if (!LogStore.isLogName(name)) return null
        val ctx = context ?: return null
        return File(Keeper.store(ctx).directory, name)
    }

    companion object {
        private const val MIME = "text/plain"

        fun uriFor(packageName: String, file: File): Uri =
            Uri.Builder().scheme("content").authority("$packageName.logs").appendPath(file.name).build()
    }
}
