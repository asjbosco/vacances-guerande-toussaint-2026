package fr.voyage.livre.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import fr.voyage.livre.annotate.Annotator
import fr.voyage.livre.annotate.PhotoImporter
import fr.voyage.livre.data.Book
import fr.voyage.livre.data.BookPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Fabrique un PDF imprimable du livre, au format A4 portrait :
 * une couverture, puis une page par photo avec le lieu, la date,
 * le texte et la légende « Avec … ».
 */
object PdfExporter {

    // A4 en points PDF (1/72 de pouce).
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    /** Résolution des photos dans le PDF : ~200 dpi sur la largeur imprimable. */
    private const val PHOTO_MAX_SIDE = 1400

    private val INK = Color.rgb(40, 40, 40)
    private val ACCENT = Color.rgb(31, 95, 122)
    private val MUTED = Color.rgb(110, 110, 110)
    private val PAPER = Color.rgb(251, 247, 240)

    suspend fun export(context: Context, book: Book, pages: List<BookPage>): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "partage").apply { mkdirs() }
        val file = File(dir, fileName(book))
        val doc = PdfDocument()
        try {
            drawCover(doc, book, pages)
            pages.forEachIndexed { i, page -> drawPhotoPage(doc, book, page, i + 2) }
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    /** Ouvre le choix d'application (Drive, Fichiers, impression, WhatsApp…) pour le PDF. */
    fun share(context: Context, file: File, book: Book) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            clipData = ClipData.newUri(context.contentResolver, "livre", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Enregistrer ou envoyer le PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun fileName(book: Book): String {
        val base = book.title
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Livre de voyage" }
        return "$base.pdf"
    }

    // ---------- Couverture ----------

    private fun drawCover(doc: PdfDocument, book: Book, pages: List<BookPage>) {
        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val canvas = pdfPage.canvas
        canvas.drawColor(PAPER)
        val width = (PAGE_W - 2 * MARGIN).toInt()

        // Photo de couverture : la première du livre, en haut, recadrée.
        val coverHeight = PAGE_H * 0.48f
        pages.firstOrNull()?.let { first ->
            PhotoImporter.decodeFileScaled(first.photoPath, PHOTO_MAX_SIDE)?.let { photo ->
                drawCropped(canvas, photo, RectF(0f, 0f, PAGE_W.toFloat(), coverHeight))
                photo.recycle()
            }
        }

        var y = if (pages.isEmpty()) PAGE_H * 0.3f else coverHeight + 48f
        y = drawText(canvas, book.title, serif(30f, bold = true, color = ACCENT), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
        y += 14f
        if (book.destination.isNotBlank()) {
            y = drawText(canvas, book.destination, serif(15f, color = INK), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
            y += 6f
        }
        val dates = listOf(book.startDate, book.endDate).filter { it.isNotBlank() }.joinToString(" – ")
        if (dates.isNotBlank()) {
            y = drawText(canvas, dates, sans(12f, italic = true, color = MUTED), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
        }

        // Filet décoratif
        y += 22f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = 1f }
        canvas.drawLine(PAGE_W / 2f - 60f, y, PAGE_W / 2f + 60f, y, line)
        y += 22f

        if (book.participants.isNotEmpty()) {
            val names = book.participants.joinToString(", ") { it.name }
            drawText(canvas, "Avec $names", serif(13f, color = INK), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
        }

        drawText(
            canvas, "${pages.size} photo${if (pages.size > 1) "s" else ""}",
            sans(10f, color = MUTED), MARGIN, PAGE_H - MARGIN - 12f, width, Layout.Alignment.ALIGN_CENTER,
        )
        doc.finishPage(pdfPage)
    }

    // ---------- Pages photo ----------

    private fun drawPhotoPage(doc: PdfDocument, book: Book, page: BookPage, number: Int) {
        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
        val canvas = pdfPage.canvas
        canvas.drawColor(Color.WHITE)
        val width = (PAGE_W - 2 * MARGIN).toInt()

        // Texte d'abord mesuré, pour laisser à la photo toute la place restante.
        val names = book.participants.filter { it.id in page.participantIds }.map { it.name }
        val blocks = listOfNotNull(
            layout(page.place, serif(18f, bold = true, color = ACCENT), width),
            page.takenAt?.let { layout(Annotator.formatDate(it).replaceFirstChar(Char::uppercase), sans(10f, italic = true, color = MUTED), width) },
            layout(page.summary, serif(12f, color = INK), width),
            names.takeIf { it.isNotEmpty() }?.let { layout("Avec ${it.joinToString(", ")}", serif(12f, italic = true, color = INK), width) },
        )
        val gap = 8f
        val footerSpace = 24f
        val textHeight = blocks.sumOf { it.height } + gap * (blocks.size - 1).coerceAtLeast(0)
        val photoMaxH = (PAGE_H - 2 * MARGIN - footerSpace - textHeight - 20f).coerceAtLeast(PAGE_H * 0.35f)

        var y = MARGIN
        PhotoImporter.decodeFileScaled(page.photoPath, PHOTO_MAX_SIDE)?.let { photo ->
            val scale = minOf(width / photo.width.toFloat(), photoMaxH / photo.height.toFloat())
            val w = photo.width * scale
            val h = photo.height * scale
            val left = (PAGE_W - w) / 2f
            canvas.drawBitmap(photo, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            photo.recycle()
            y += h + 20f
        }

        blocks.forEach { l ->
            canvas.save()
            canvas.translate(MARGIN, y)
            l.draw(canvas)
            canvas.restore()
            y += l.height + gap
        }

        // Numéro de page
        drawText(canvas, "$number", sans(9f, color = MUTED), MARGIN, PAGE_H - MARGIN + 8f, width, Layout.Alignment.ALIGN_CENTER)
        doc.finishPage(pdfPage)
    }

    // ---------- Outils de dessin ----------

    private fun drawCropped(canvas: Canvas, photo: Bitmap, dst: RectF) {
        val dstRatio = dst.width() / dst.height()
        val srcRatio = photo.width / photo.height.toFloat()
        val src = if (srcRatio > dstRatio) {
            val w = (photo.height * dstRatio).toInt()
            val x = (photo.width - w) / 2
            Rect(x, 0, x + w, photo.height)
        } else {
            val h = (photo.width / dstRatio).toInt()
            val yy = (photo.height - h) / 2
            Rect(0, yy, photo.width, yy + h)
        }
        canvas.drawBitmap(photo, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout? =
        text.takeIf { it.isNotBlank() }?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, paint, width)
                .setAlignment(align)
                .setLineSpacing(0f, 1.2f)
                .build()
        }

    /** Dessine un texte et renvoie la position verticale juste en dessous. */
    private fun drawText(canvas: Canvas, text: String, paint: TextPaint, x: Float, y: Float, width: Int, align: Layout.Alignment): Float {
        val l = layout(text, paint, width, align) ?: return y
        canvas.save()
        canvas.translate(x, y)
        l.draw(canvas)
        canvas.restore()
        return y + l.height
    }

    private fun serif(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int) =
        paint(Typeface.SERIF, size, bold, italic, color)

    private fun sans(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int) =
        paint(Typeface.SANS_SERIF, size, bold, italic, color)

    private fun paint(family: Typeface, size: Float, bold: Boolean, italic: Boolean, color: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            typeface = Typeface.create(family, style)
            textSize = size
            this.color = color
        }
}
