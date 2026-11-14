package fr.voyage.livre.annotate

import android.content.Context
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.Book
import fr.voyage.livre.data.BookPage
import fr.voyage.livre.data.isNear
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Annote une page : lieu (GPS → adresse), articles Wikipédia proches,
 * puis légende rédigée par Claude (ou extrait Wikipédia si pas de clé API).
 */
class Annotator(private val context: Context) {

    suspend fun annotate(page: BookPage, book: Book, captioner: ClaudeCaptioner?): BookPage {
        val lat = page.latitude
        val lon = page.longitude

        val address = if (lat != null && lon != null) PlaceFinder.placeName(context, lat, lon) else null

        val articles = runCatching {
            when {
                lat != null && lon != null -> WikipediaClient.nearby(lat, lon)
                address != null -> WikipediaClient.search(address)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        // Photos voisines déjà légendées : on ne répète pas ce qui y est déjà dit.
        val neighbours = book.pages.filter {
            it.id != page.id && it.status == AnnotationStatus.DONE &&
                it.summary.isNotBlank() && page.isNear(it, book.groupRadiusMeters)
        }
        val usedArticles = neighbours.map { it.wikiTitle }.filter { it.isNotBlank() }.toSet()

        val caption = captioner?.let { claude ->
            val bitmap = PhotoImporter.decodeFileScaled(page.photoPath, 1568)
                ?: error("Photo introuvable")
            claude.caption(
                photo = bitmap.forVision(),
                destination = book.destination,
                dateText = page.takenAt?.let { formatDate(it) },
                guessedPlace = address,
                gps = if (lat != null && lon != null) "%.5f, %.5f".format(Locale.US, lat, lon) else null,
                articles = articles,
                neighbourCaptions = neighbours.map { "${it.place} : ${it.summary}" },
            )
        }

        // Sans Claude : on évite de reprendre un article déjà cité juste à côté.
        val best = articles.firstOrNull { it.title !in usedArticles }
        val repeatsNeighbour = best == null && articles.isNotEmpty()
        val place = caption?.place?.takeIf { it.isNotBlank() }
            ?: address
            ?: best?.title
            ?: neighbours.firstOrNull()?.place
            ?: articles.firstOrNull()?.title
            ?: page.place
        val summary = caption?.summary
            ?: best?.let { WikipediaClient.firstSentences(it.extract) }
            ?: if (repeatsNeighbour) "" else page.summary

        return page.copy(
            place = place,
            summary = summary,
            wikiTitle = (best ?: articles.firstOrNull())?.title.orEmpty(),
            status = AnnotationStatus.DONE,
            error = if (caption == null && captioner != null) "Claude n'a pas répondu : texte repris de Wikipédia." else null,
        )
    }

    companion object {
        fun formatDate(millis: Long): String =
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date(millis))
    }
}
