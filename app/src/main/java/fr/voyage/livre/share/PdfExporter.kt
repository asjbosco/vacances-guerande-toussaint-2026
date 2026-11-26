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
            drawPhotoPages(doc, book, pages)
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

    private const val TEXT_GAP = 8f          // entre les lignes de texte d'une légende
    private const val FOOTER_SPACE = 24f     // place réservée au numéro de page
    private const val INTER_CARD = 28f       // entre deux photos empilées
    private const val RULE_OUTER = 1.6f      // filet extérieur (épais)
    private const val RULE_INNER = 0.8f      // filet intérieur (moitié de l'épaisseur)
    private const val RULE_GAP = 3f          // écart entre les deux filets
    private const val CAP_PAD_H = 10f        // marge filet → texte (gauche/droite)
    private const val CAP_PAD_V = 8f         // marge filet → texte (haut/bas)
    private const val PHOTO_CAPTION_GAP = 12f // écart photo → cartouche de légende

    /**
     * Mise en page, toujours sur page verticale (A4 portrait) :
     *  - deux photos PAYSAGE qui se suivent → l'une au-dessus de l'autre ;
     *  - une photo PORTRAIT → seule sur sa page ;
     *  - une photo paysage isolée (ou la dernière) → seule sur sa page.
     * Dans tous les cas, la photo est réduite au besoin pour que la légende tienne EN ENTIER
     * dans son cadre (voir drawCard), sans jamais être tronquée.
     */
    private fun drawPhotoPages(doc: PdfDocument, book: Book, pages: List<BookPage>) {
        val contentW = (PAGE_W - 2 * MARGIN).toInt()
        val availH = PAGE_H - 2 * MARGIN - FOOTER_SPACE
        val stackSlotH = (availH - INTER_CARD) / 2f

        var i = 0
        var number = 2
        while (i < pages.size) {
            val a = pages[i]
            val b = pages.getOrNull(i + 1)
            val twoLandscape = b != null && !isPortrait(a) && !isPortrait(b)

            val page = startPage(doc, number)
            if (twoLandscape) {
                drawCard(page.canvas, book, a, MARGIN, MARGIN, contentW, stackSlotH)
                val sepY = MARGIN + stackSlotH + INTER_CARD / 2f
                page.canvas.drawLine(
                    MARGIN, sepY, PAGE_W - MARGIN, sepY,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; strokeWidth = 0.5f },
                )
                drawCard(page.canvas, book, b!!, MARGIN, MARGIN + stackSlotH + INTER_CARD, contentW, stackSlotH)
                i += 2
            } else {
                drawCard(page.canvas, book, a, MARGIN, MARGIN, contentW, availH)
                i += 1
            }
            footer(page.canvas, number)
            doc.finishPage(page)
            number++
        }
    }

    private fun startPage(doc: PdfDocument, number: Int): PdfDocument.Page {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
        page.canvas.drawColor(Color.WHITE)
        return page
    }

    private fun footer(canvas: Canvas, number: Int) {
        drawText(
            canvas, "$number", sans(9f, color = MUTED),
            MARGIN, PAGE_H - MARGIN + 8f, (PAGE_W - 2 * MARGIN).toInt(), Layout.Alignment.ALIGN_CENTER,
        )
    }

    /** Portrait = plus haute que large (orientation déjà appliquée à l'import). */
    private fun isPortrait(page: BookPage): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(page.photoPath, o)
        return o.outWidth > 0 && o.outHeight > o.outWidth
    }

    /** Les lignes de texte d'une légende (lieu, date, résumé, « Avec … »), à l'échelle de police donnée. */
    private fun captionBlocks(book: Book, page: BookPage, width: Int, scale: Float = 1f): List<StaticLayout> {
        val names = book.participants.filter { it.id in page.participantIds }.map { it.name }
        return listOfNotNull(
            layout(page.place, serif(18f * scale, bold = true, color = ACCENT), width),
            page.takenAt?.let { layout(Annotator.formatDate(it).replaceFirstChar(Char::uppercase), sans(10f * scale, italic = true, color = MUTED), width) },
            layout(page.summary, serif(12f * scale, color = INK), width),
            names.takeIf { it.isNotEmpty() }?.let { layout("Avec ${it.joinToString(", ")}", serif(12f * scale, italic = true, color = INK), width) },
        )
    }

    private fun blocksHeight(blocks: List<StaticLayout>): Float =
        blocks.sumOf { it.height }.toFloat() + TEXT_GAP * (blocks.size - 1).coerceAtLeast(0)

    /**
     * Dessine une photo puis sa légende dans un cartouche à double filet aligné sur la photo.
     * Principe : on dimensionne le cadre de légende sur le TEXTE COMPLET (jamais tronqué),
     * puis on réduit la photo pour que photo + cadre tiennent dans la hauteur [maxCardH],
     * en laissant une marge intérieure haut/bas. Quelques passes suffisent à converger.
     */
    private fun drawCard(canvas: Canvas, book: Book, page: BookPage, x: Float, top: Float, cardWidth: Int, maxCardH: Float) {
        val photo = PhotoImporter.decodeFileScaled(page.photoPath, PHOTO_MAX_SIDE) ?: return
        val pw = photo.width.toFloat()
        val ph = photo.height.toFloat()
        val insetH = RULE_GAP + CAP_PAD_H
        val insetV = RULE_GAP + CAP_PAD_V
        val minPhotoH = maxCardH * 0.30f  // la photo ne descend jamais sous ce seuil

        fun captionWidthFor(photoW: Float) = (photoW - 2 * insetH).toInt().coerceAtLeast(40)
        fun boxHeightFor(photoW: Float): Float {
            val b = captionBlocks(book, page, captionWidthFor(photoW))
            return if (b.isEmpty()) 0f else blocksHeight(b) + 2 * insetV
        }

        // Point de départ : photo limitée par la largeur et par ~72 % de la hauteur.
        var scale = minOf(cardWidth / pw, (maxCardH * 0.72f) / ph)
        repeat(3) {
            val boxH = boxHeightFor(pw * scale)
            val gap = if (boxH > 0f) PHOTO_CAPTION_GAP else 0f
            val budget = (maxCardH - gap - boxH).coerceAtLeast(minPhotoH)
            scale = minOf(cardWidth / pw, budget / ph)
        }

        val photoW = pw * scale
        val photoH = ph * scale
        val left = x + (cardWidth - photoW) / 2f
        val blocks = captionBlocks(book, page, captionWidthFor(photoW))
        val boxH = if (blocks.isEmpty()) 0f else blocksHeight(blocks) + 2 * insetV

        var y = top
        canvas.drawBitmap(photo, null, RectF(left, y, left + photoW, y + photoH), Paint(Paint.FILTER_BITMAP_FLAG))
        photo.recycle()
        if (blocks.isEmpty()) return
        y += photoH + PHOTO_CAPTION_GAP

        // Cartouche dimensionné sur TOUT le texte (aucune troncature), double filet.
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeWidth = RULE_OUTER }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeWidth = RULE_INNER }
        canvas.drawRect(left, y, left + photoW, y + boxH, outer)
        canvas.drawRect(left + RULE_GAP, y + RULE_GAP, left + photoW - RULE_GAP, y + boxH - RULE_GAP, inner)

        var ty = y + insetV
        blocks.forEach { l ->
            canvas.save()
            canvas.translate(left + insetH, ty)
            l.draw(canvas)
            canvas.restore()
            ty += l.height + TEXT_GAP
        }
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
