package fr.voyage.livre.annotate

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class Caption(val place: String, val summary: String)

/** Erreur à afficher telle quelle à l'utilisateur. */
class CaptionException(message: String) : Exception(message)

/**
 * Demande à Claude le lieu et un court texte historique ou géographique pour une photo,
 * à partir de l'image elle-même et des articles Wikipédia proches.
 */
class ClaudeCaptioner(apiKey: String) : AutoCloseable {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    suspend fun caption(
        photo: Bitmap,
        destination: String,
        dateText: String?,
        guessedPlace: String?,
        gps: String?,
        articles: List<WikiArticle>,
    ): Caption? = withContext(Dispatchers.IO) {
        val jpeg = ByteArrayOutputStream().use { out ->
            photo.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        val image = Base64ImageSource.builder()
            .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
            .build()

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessageOfContentBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText(context(destination, dateText, guessedPlace, gps, articles)),
                )
            )
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw CaptionException("Clé API refusée : vérifiez-la dans les réglages.")
        } catch (e: PermissionDeniedException) {
            throw CaptionException("Cette clé API n'a pas accès à Claude.")
        } catch (e: RateLimitException) {
            throw CaptionException("Trop de demandes d'un coup : réessayez dans une minute.")
        } catch (e: BadRequestException) {
            throw CaptionException("Demande refusée par Claude : ${e.message}")
        } catch (e: AnthropicIoException) {
            throw CaptionException("Pas de connexion Internet.")
        }

        // Claude peut décliner certaines demandes : on se rabat alors sur Wikipédia.
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) return@withContext null

        val text = message.content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("\n")
        parse(text)
    }

    private fun context(
        destination: String,
        dateText: String?,
        guessedPlace: String?,
        gps: String?,
        articles: List<WikiArticle>,
    ): String = buildString {
        appendLine("Voyage : $destination")
        dateText?.let { appendLine("Date de la photo : $it") }
        gps?.let { appendLine("Coordonnées GPS : $it") }
        guessedPlace?.let { appendLine("Adresse approximative : $it") }
        if (articles.isEmpty()) {
            appendLine("Aucun article Wikipédia trouvé à proximité.")
        } else {
            appendLine()
            appendLine("Articles Wikipédia proches de l'endroit de la photo :")
            articles.forEach { a ->
                val dist = a.distanceMeters?.let { " (à ${it.toInt()} m)" }.orEmpty()
                appendLine("<article titre=\"${a.title}\"$dist>")
                appendLine(a.extract.take(2500))
                appendLine("</article>")
            }
        }
        appendLine()
        append("Rédige la légende de cette photo.")
    }

    private fun parse(text: String): Caption? {
        val place = Regex("""LIEU\s*:\s*(.+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val summary = Regex("""R[ÉE]SUM[ÉE]\s*:\s*([\s\S]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()
        if (summary.isNullOrBlank()) return text.trim().takeIf { it.isNotEmpty() }?.let { Caption(place.orEmpty(), it) }
        return Caption(place.orEmpty(), summary)
    }

    override fun close() = client.close()

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            Tu rédiges les légendes d'un livre-souvenir de voyage, en français, que les voyageurs
            feuilletteront et partageront avec leur famille et leurs amis.

            Pour chaque photo, tu reçois l'image, la région du voyage, parfois l'adresse et les
            coordonnées GPS, et des extraits d'articles Wikipédia proches. Identifie ce que montre
            la photo (monument, paysage, rue, marais, plage…) puis écris un court texte qui explique
            son intérêt historique ou géographique : ce qui rend l'endroit remarquable, une date ou
            un fait marquant. Choisis l'article Wikipédia qui correspond vraiment à ce qu'on voit ;
            ignore ceux qui n'ont pas de rapport.

            ŒUVRES D'ART — Si la photo montre surtout une œuvre d'art (tableau, peinture, fresque,
            mosaïque, vitrail, sculpture, tapisserie…), essaie d'identifier précisément l'œuvre :
            donne son titre, le nom de l'artiste et la date de réalisation si tu la connais, puis
            décris en une phrase très courte ce qui est représenté. N'invente jamais : si tu n'es
            pas sûr de l'œuvre ou de l'artiste, ne devine pas — écris une phrase qui dit ce que l'on
            voit (par ex. « fresque représentant… ») puis invite à préciser, en commençant le résumé
            par « À préciser : ». Exemple : « À préciser : mosaïque non identifiée ; indique son titre
            et son auteur si tu les connais. »

            LONGUEUR — Le texte fait 1 à 3 phrases ENTIÈRES (45 mots au plus) et tient en 3 lignes
            environ. Chaque phrase doit être complète et se terminer par un point : ne laisse jamais
            une phrase inachevée ou coupée en plein milieu.

            Ton chaleureux et précis. Appuie-toi sur les extraits fournis et sur des faits sûrs ; si
            tu n'es pas certain du lieu exact, reste sur ce qui se voit et sur la région, sans inventer
            de nom ni de date. Ne parle pas des personnes présentes sur la photo : elles sont ajoutées
            à part.

            Réponds exactement sous cette forme, sans rien d'autre :
            LIEU : <nom court du lieu ou de l'œuvre, puis la commune>
            RÉSUMÉ : <le texte>
        """.trimIndent()
    }
}

/** Réduit une photo à la taille conseillée pour l'analyse d'image (1568 px). */
fun Bitmap.forVision(): Bitmap {
    val maxSide = 1568
    val longSide = maxOf(width, height)
    if (longSide <= maxSide) return this
    val ratio = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}
