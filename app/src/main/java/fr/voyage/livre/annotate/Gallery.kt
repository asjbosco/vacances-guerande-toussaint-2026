package fr.voyage.livre.annotate

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryPhoto(val uri: Uri, val takenAt: Long)

/** Lit les photos de la galerie du téléphone prises entre deux dates. */
object Gallery {

    suspend fun photosBetween(context: Context, startMillis: Long, endMillis: Long): List<GalleryPhoto> =
        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
            )
            val result = mutableListOf<GalleryPhoto>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val taken = if (c.isNull(takenCol)) c.getLong(addedCol) * 1000 else c.getLong(takenCol)
                    if (taken in startMillis..endMillis) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                        )
                        result += GalleryPhoto(uri, taken)
                    }
                }
            }
            result
        }
}
