package fr.voyage.livre.annotate

import android.content.Context
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.Book
import fr.voyage.livre.data.BookPage
import fr.voyage.livre.data.isNear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Annote une page à partir de sa localisation : le GPS de la photo, ou à défaut le lieu
 * que vous avez indiqué. Texte rédigé par Claude, ou repris de Wikipédia (sources libres).
 */
class Annotator(private val context: Context) {

    suspend fun annotate(page: BookPage, book: Book, captioner: ClaudeCaptioner?): BookPage {
        val lat = page.latitude
        val lon = page.longitude
        // Lieu saisi par vous (ou, sans GPS, le lieu déjà noté) : il prime sur tout le reste.
        val userPlace = page.place.trim().takeIf { (page.placeByUser || !page.hasGps) && it.isNotEmpty() }

        // Sans GPS ni lieu indiqué, on ne devine pas : c'est à vous de le préciser.
        if ((lat == null || lon == null) && userPlace == null) {
            return page.copy(
                status = AnnotationStatus.ERROR,
                error = "Pas de position GPS : indiquez le lieu ou le nom de la photo.",
            )
        }

        val address = if (lat != null && lon != null) PlaceFinder.find(context, lat, lon) else null

        val articles = runCatching {
            when {
                lat != null && lon != null -> WikipediaClient.nearby(lat, lon)
                else -> WikipediaClient.search(listOfNotNull(userPlace, book.destination.ifBlank { null }).joinToString(" "))
            }
        }.getOrDefault(emptyList())

        // Photos voisines déjà légendées : on ne répète pas ce qui y est déjà dit.
        val neighbours = book.pages.filter {
            it.id != page.id && it.status == AnnotationStatus.DONE &&
                it.summary.isNotBlank() && page.isNear(it, book.groupRadiusMeters)
        }
        val usedArticles = neighbours.map { it.wikiTitle }.filter { it.isNotBlank() }.toSet()

        val caption = captioner?.let { claude ->
            val bitmap = withContext(Dispatchers.Default) { PhotoImporter.decodeFileScaled(page.photoPath, 1568)?.forVision() }
                ?: error("Photo introuvable")
            claude.caption(
                photo = bitmap,
                destination = book.destination,
                dateText = page.takenAt?.let { formatDate(it) },
                address = address?.label,
                userPlace = userPlace,
                gps = if (lat != null && lon != null) "%.5f, %.5f".format(Locale.US, lat, lon) else null,
                articles = articles,
                neighbourCaptions = neighbours.map { "${it.place} : ${it.summary}" },
            )
        }

        // Sans Claude : article le plus proche (pas déjà cité juste à côté).
        val best = articles.firstOrNull { it.title !in usedArticles }
        val closeArticle = best?.takeIf { (it.distanceMeters ?: 0.0) <= 200.0 }

        val place = userPlace
            ?: caption?.place?.takeIf { it.isNotBlank() }
            ?: closeArticle?.title
            ?: neighbours.firstOrNull()?.place
            ?: address?.town
            ?: address?.label
            ?: page.place
        val summary = caption?.summary?.let { telegraphic(it) }
            ?: best?.let { telegraphic(WikipediaClient.firstSentences(it.extract, 1)) }
            ?: ""

        return page.copy(
            place = place,
            summary = summary,
            wikiTitle = (best ?: articles.firstOrNull())?.title.orEmpty(),
            status = AnnotationStatus.DONE,
            error = if (caption == null && captioner != null) "Claude n'a pas répondu : texte repris de Wikipédia." else null,
        )
    }

    companion object {
        /** Longueur maximale du texte : environ deux lignes. */
        const val MAX_SUMMARY = 120

        fun formatDate(millis: Long): String =
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date(millis))

        fun shortDate(millis: Long): String =
            SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date(millis))

        /** Coupe le texte à ~2 lignes, sur un mot entier. */
        fun telegraphic(text: String): String {
            val t = text.trim().replace(Regex("\\s+"), " ")
            if (t.length <= MAX_SUMMARY) return t
            val cut = t.take(MAX_SUMMARY).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ')
            return "$cut…"
        }
    }
}
