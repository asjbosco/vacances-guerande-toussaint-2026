package fr.voyage.livre.annotate

import android.content.Context
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.Book
import fr.voyage.livre.data.BookPage
import fr.voyage.livre.data.CaptionSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Annote une page : lieu (GPS → adresse), articles Wikipédia proches,
 * puis légende rédigée par Claude (ou extrait Wikipédia selon la source choisie).
 */
class Annotator(private val context: Context) {

    suspend fun annotate(
        page: BookPage,
        book: Book,
        captioner: ClaudeCaptioner?,
        source: CaptionSource = CaptionSource.AUTO,
    ): BookPage {
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

        // Wikipédia explicitement demandé → on n'appelle pas Claude.
        val useClaude = captioner != null && source != CaptionSource.WIKIPEDIA
        val caption = if (useClaude) {
            val bitmap = PhotoImporter.decodeFileScaled(page.photoPath, 1568)
                ?: error("Photo introuvable")
            captioner!!.caption(
                photo = bitmap.forVision(),
                destination = book.destination,
                dateText = page.takenAt?.let { formatDate(it) },
                guessedPlace = address,
                gps = if (lat != null && lon != null) "%.5f, %.5f".format(Locale.US, lat, lon) else null,
                articles = articles,
            )
        } else null

        val best = articles.firstOrNull()
        val wikiSummary = best?.let { WikipediaClient.firstSentences(it.extract) }

        val place = caption?.place?.takeIf { it.isNotBlank() }
            ?: address
            ?: best?.title
            ?: page.place
        val summary = caption?.summary ?: wikiSummary ?: page.summary
        val textSource = when {
            caption != null -> "claude"
            wikiSummary != null -> "wikipedia"
            else -> page.textSource
        }

        val error = when {
            // Wikipédia demandé mais rien trouvé pour cet endroit.
            source == CaptionSource.WIKIPEDIA && wikiSummary == null ->
                "Aucun article Wikipédia trouvé pour cet endroit."
            // Claude attendu mais pas de réponse → repli Wikipédia.
            useClaude && caption == null && wikiSummary != null ->
                "Claude n'a pas répondu : texte repris de Wikipédia."
            else -> null
        }

        return page.copy(
            place = place,
            summary = summary,
            wikiTitle = best?.title.orEmpty(),
            textSource = textSource,
            status = AnnotationStatus.DONE,
            error = error,
        )
    }

    companion object {
        fun formatDate(millis: Long): String =
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date(millis))
    }
}
