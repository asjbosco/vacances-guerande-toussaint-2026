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
import fr.voyage.livre.data.CaptionFormat
import fr.voyage.livre.data.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Fabrique un PDF imprimable du livre, au format A4 portrait :
 * une couverture, puis une page par photo avec le lieu, la date,
 * le texte et la légende « Avec … ».
 */
/** Qualité des photos dans le PDF. */
enum class PdfQuality(val maxSide: Int, val jpegQuality: Int) {
    /** ~300 dpi sur une page A4 : pour imprimer ou agrandir à l'écran. */
    HIGH(3000, 92),

    /** Plus léger, pour un envoi rapide (toujours net sur un téléphone). */
    LIGHT(1800, 85),
}

object PdfExporter {

    // A4 en points PDF (1/72 de pouce).
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    /** Emplacement réservé à une photo, rempli ensuite en JPEG haute définition. */
    private data class PhotoSlot(val pageIndex: Int, val page: BookPage, val rect: RectF, val crop: Boolean)

    private val INK = Color.rgb(40, 40, 40)
    private val ACCENT = Color.rgb(31, 95, 122)
    private val MUTED = Color.rgb(110, 110, 110)
    private val PAPER = Color.rgb(251, 247, 240)

    /**
     * 1. La mise en page (fond, textes) est dessinée avec l'outil PDF d'Android, en laissant la
     *    place des photos. 2. Les photos sont ensuite posées en JPEG, depuis l'original de la
     *    galerie, à la résolution choisie : aucune perte supplémentaire.
     */
    suspend fun export(
        context: Context,
        book: Book,
        pages: List<BookPage>,
        people: List<Participant>,
        quality: PdfQuality,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "partage").apply { mkdirs() }
        val file = File(dir, fileName(book))
        val base = File(dir, "mise-en-page.pdf")
        val slots = mutableListOf<PhotoSlot>()

        val layoutDoc = PdfDocument()
        try {
            drawCover(layoutDoc, book, pages, book.travelers(people), slots)
            pages.forEachIndexed { i, page -> drawPhotoPage(layoutDoc, page, people, i + 2, slots) }
            FileOutputStream(base).use { layoutDoc.writeTo(it) }
        } finally {
            layoutDoc.close()
        }

        PDFBoxResourceLoader.init(context)
        PDDocument.load(base, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (slot in slots) {
                val jpeg = jpegFor(context, slot.page, quality) ?: continue
                val image = JPEGFactory.createFromByteArray(doc, jpeg)
                PDPageContentStream(doc, doc.getPage(slot.pageIndex), PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    // Le PDF compte les hauteurs depuis le bas de la page.
                    val r = slot.rect
                    val x = r.left
                    val y = PAGE_H - r.bottom
                    if (slot.crop) {
                        val scale = maxOf(r.width() / image.width, r.height() / image.height)
                        val w = image.width * scale
                        val h = image.height * scale
                        cs.saveGraphicsState()
                        cs.addRect(x, y, r.width(), r.height())
                        cs.clip()
                        cs.drawImage(image, x + (r.width() - w) / 2f, y + (r.height() - h) / 2f, w, h)
                        cs.restoreGraphicsState()
                    } else {
                        cs.drawImage(image, x, y, r.width(), r.height())
                    }
                }
            }
            doc.save(file)
        }
        base.delete()
        file
    }

    /** La photo en JPEG à la résolution demandée, depuis l'original si possible. */
    private fun jpegFor(context: Context, page: BookPage, quality: PdfQuality): ByteArray? {
        val bitmap = PhotoImporter.decodeBest(context, page, quality.maxSide) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
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

    private fun drawCover(
        doc: PdfDocument, book: Book, pages: List<BookPage>, travelers: List<Participant>, slots: MutableList<PhotoSlot>,
    ) {
        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val canvas = pdfPage.canvas
        canvas.drawColor(PAPER)
        val width = (PAGE_W - 2 * MARGIN).toInt()

        // Photo de couverture : la première du livre, en haut, recadrée.
        val coverHeight = PAGE_H * 0.48f
        pages.firstOrNull()?.let { first ->
            slots += PhotoSlot(0, first, RectF(0f, 0f, PAGE_W.toFloat(), coverHeight), crop = true)
        }

        var y = if (pages.isEmpty()) PAGE_H * 0.3f else coverHeight + 48f
        y = drawText(canvas, book.title, serif(30f, bold = true, color = ACCENT), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
        y += 14f
        if (book.destination.isNotBlank()) {
            y = drawText(canvas, book.destination, serif(15f, color = INK), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
            y += 6f
        }
        val dates = book.dateRange
        if (dates.isNotBlank()) {
            y = drawText(canvas, dates, sans(12f, italic = true, color = MUTED), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
        }

        // Filet décoratif
        y += 22f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = 1f }
        canvas.drawLine(PAGE_W / 2f - 60f, y, PAGE_W / 2f + 60f, y, line)
        y += 22f

        if (travelers.isNotEmpty()) {
            val names = travelers.joinToString(", ") { it.name }
            drawText(canvas, "Avec $names", serif(13f, color = INK), MARGIN, y, width, Layout.Alignment.ALIGN_CENTER)
        }

        drawText(
            canvas, "${pages.size} photo${if (pages.size > 1) "s" else ""}",
            sans(10f, color = MUTED), MARGIN, PAGE_H - MARGIN - 12f, width, Layout.Alignment.ALIGN_CENTER,
        )
        doc.finishPage(pdfPage)
    }

    // ---------- Pages photo ----------

    private fun drawPhotoPage(
        doc: PdfDocument, page: BookPage, people: List<Participant>, number: Int, slots: MutableList<PhotoSlot>,
    ) {
        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
        val canvas = pdfPage.canvas
        canvas.drawColor(Color.WHITE)
        val width = (PAGE_W - 2 * MARGIN).toInt()

        // Texte d'abord mesuré, pour laisser à la photo toute la place restante.
        // Lieu en gras, texte (2 lignes au plus), puis la date et les personnes.
        val blocks = listOfNotNull(
            layout(page.place, serif(18f, bold = true, color = ACCENT), width, maxLines = 2),
            layout(page.summary, serif(12f, color = INK), width, maxLines = 2),
            layout(CaptionFormat.footer(page, people), sans(10f, italic = true, color = MUTED), width),
        )
        val gap = 8f
        val footerSpace = 24f
        val textHeight = blocks.sumOf { it.height } + gap * (blocks.size - 1).coerceAtLeast(0)
        val photoMaxH = (PAGE_H - 2 * MARGIN - footerSpace - textHeight - 20f).coerceAtLeast(PAGE_H * 0.35f)

        var y = MARGIN
        // Place de la photo (même cadrage que la copie de l'application), remplie à la fin.
        PhotoImporter.size(page.photoPath)?.let { (pw, ph) ->
            val scale = minOf(width / pw.toFloat(), photoMaxH / ph.toFloat())
            val w = pw * scale
            val h = ph * scale
            val left = (PAGE_W - w) / 2f
            slots += PhotoSlot(number - 1, page, RectF(left, y, left + w, y + h), crop = false)
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

    private fun layout(
        text: String, paint: TextPaint, width: Int,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, maxLines: Int = Int.MAX_VALUE,
    ): StaticLayout? =
        text.takeIf { it.isNotBlank() }?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, paint, width)
                .setAlignment(align)
                .setLineSpacing(0f, 1.2f)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
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
