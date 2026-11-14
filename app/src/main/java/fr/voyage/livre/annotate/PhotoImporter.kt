package fr.voyage.livre.annotate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import fr.voyage.livre.data.BookPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copie une photo de la galerie dans l'application (taille réduite, bien orientée)
 * et lit sa date et ses coordonnées GPS.
 */
object PhotoImporter {

    private const val MAX_SIDE = 3000

    suspend fun import(context: Context, uri: Uri, fallbackTakenAt: Long?, photosDir: File): BookPage =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver

            // Sans « photo originale », Android efface la position GPS des photos.
            val exifUri = originalUri(context, uri)
            val exif = runCatching { resolver.openInputStream(exifUri)?.use { ExifInterface(it) } }
                .getOrNull()
                ?: runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()

            val latLong = exif?.latLong?.takeIf { it[0] != 0.0 || it[1] != 0.0 }
            val takenAt = exif?.let { parseExifDate(it) } ?: fallbackTakenAt
            val rotation = exif?.rotationDegrees ?: 0

            val bitmap = decodeScaled(context, uri, MAX_SIDE)
                ?: error("Impossible de lire la photo")
            val rotated = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap

            val id = UUID.randomUUID().toString()
            val out = File(photosDir, "$id.jpg")
            FileOutputStream(out).use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }

            BookPage(
                id = id,
                photoPath = out.absolutePath,
                sourceUri = uri.toString(),
                takenAt = takenAt,
                latitude = latLong?.get(0),
                longitude = latLong?.get(1),
            )
        }

    private fun originalUri(context: Context, uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted || uri.authority != MediaStore.AUTHORITY) return uri
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    }

    private fun parseExifDate(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    /** Décode une image en limitant son plus grand côté à [maxSide] pixels. */
    fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val longSide = max(decoded.width, decoded.height)
        if (longSide <= maxSide) return decoded
        val ratio = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            decoded, (decoded.width * ratio).roundToInt(), (decoded.height * ratio).roundToInt(), true
        )
    }

    fun decodeFileScaled(path: String, maxSide: Int): Bitmap? =
        decodeScaledFromFile(File(path), maxSide)

    private fun decodeScaledFromFile(file: File, maxSide: Int): Bitmap? {
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val longSide = max(decoded.width, decoded.height)
        if (longSide <= maxSide) return decoded
        val ratio = maxSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            decoded, (decoded.width * ratio).roundToInt(), (decoded.height * ratio).roundToInt(), true
        )
    }

    /**
     * La meilleure image disponible pour une page : la photo d'origine de la galerie (pleine
     * qualité, bien orientée), ou à défaut la copie gardée par l'application.
     */
    fun decodeBest(context: Context, page: BookPage, maxSide: Int): Bitmap? {
        val original = runCatching {
            val uri = Uri.parse(page.sourceUri)
            val rotation = context.contentResolver.openInputStream(originalUri(context, uri))
                ?.use { ExifInterface(it).rotationDegrees } ?: 0
            decodeScaled(context, uri, maxSide)?.let { bmp ->
                if (rotation == 0) bmp
                else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            }
        }.getOrNull()
        return original ?: decodeFileScaled(page.photoPath, maxSide)
    }

    /** Largeur et hauteur de la copie gardée par l'application (même cadrage que l'original). */
    fun size(path: String): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }
}
