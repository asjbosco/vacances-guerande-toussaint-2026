package fr.voyage.livre.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import fr.voyage.livre.annotate.Annotator
import fr.voyage.livre.annotate.PhotoImporter
import fr.voyage.livre.data.Book
import fr.voyage.livre.data.BookPage
import fr.voyage.livre.data.CaptionFormat
import fr.voyage.livre.data.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ShareTarget(val packages: List<String>) {
    WHATSAPP(listOf("com.whatsapp", "com.whatsapp.w4b")),
    FACEBOOK(listOf("com.facebook.katana")),
    OTHER(emptyList()),
}

/**
 * Prépare des images « page de livre » (photo + légende incrustée) pour que les légendes
 * restent visibles sur WhatsApp ou Facebook, puis ouvre l'application choisie.
 */
object ShareHelper {

    fun bookText(book: Book, pages: List<BookPage>, people: List<Participant>): String = buildString {
        appendLine("📖 ${book.title}")
        appendLine()
        pages.forEachIndexed { i, p ->
            appendLine("${i + 1}. ${CaptionFormat.plain(p, people)}")
            appendLine()
        }
    }.trim()

    suspend fun share(context: Context, book: Book, pages: List<BookPage>, people: List<Participant>, target: ShareTarget) {
        val uris = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "partage").apply {
                deleteRecursively(); mkdirs()
            }
            pages.mapIndexedNotNull { i, page ->
                val bitmap = renderPage(page, people) ?: return@mapIndexedNotNull null
                val file = File(dir, "page_%02d.jpg".format(i + 1))
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
        if (uris.isEmpty()) return

        val text = if (pages.size == 1) CaptionFormat.plain(pages[0], people) else bookText(book, pages, people)
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            clipData = ClipData.newUri(context.contentResolver, "photo", uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val installed = target.packages.firstOrNull { isInstalled(context, it) }
        if (installed != null) {
            try {
                context.startActivity(Intent(intent).setPackage(installed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // On retombe sur le choix de l'application ci-dessous.
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Partager le livre").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Dessine la photo avec un bandeau de légende en dessous. */
    fun renderPage(page: BookPage, people: List<Participant>): Bitmap? {
        val photo = PhotoImporter.decodeFileScaled(page.photoPath, 1600) ?: return null
        val w = photo.width
        val margin = (w * 0.045f)
        val textWidth = (w - 2 * margin).toInt()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(31, 95, 122)
            textSize = w * 0.042f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = w * 0.030f
            typeface = Typeface.SERIF
        }
        val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 110, 110)
            textSize = w * 0.026f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
        }

        fun layout(text: String, paint: TextPaint, maxLines: Int = Int.MAX_VALUE): StaticLayout? =
            text.takeIf { it.isNotBlank() }?.let {
                StaticLayout.Builder.obtain(it, 0, it.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.15f)
                    .setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            }

        // Lieu en gras, texte sur 2 lignes au plus, puis la date et les personnes.
        val blocks = listOfNotNull(
            layout(page.place, titlePaint, 2),
            layout(page.summary, bodyPaint, 2),
            layout(CaptionFormat.footer(page, people), smallPaint),
        )
        val gap = w * 0.02f
        val bandHeight = (2 * margin + blocks.sumOf { it.height } + gap * (blocks.size - 1).coerceAtLeast(0)).toInt()

        val result = Bitmap.createBitmap(w, photo.height + bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(251, 247, 240))
        canvas.drawBitmap(photo, 0f, 0f, null)
        var y = photo.height + margin
        blocks.forEach { l ->
            canvas.save()
            canvas.translate(margin, y)
            l.draw(canvas)
            canvas.restore()
            y += l.height + gap
        }
        return result
    }
}
