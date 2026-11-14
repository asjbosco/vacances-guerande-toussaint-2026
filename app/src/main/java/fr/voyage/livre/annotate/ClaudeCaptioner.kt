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
        address: String?,
        userPlace: String?,
        gps: String?,
        articles: List<WikiArticle>,
        neighbourCaptions: List<String> = emptyList(),
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
                    ContentBlockParam.ofText(context(destination, dateText, address, userPlace, gps, articles, neighbourCaptions)),
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
        address: String?,
        userPlace: String?,
        gps: String?,
        articles: List<WikiArticle>,
        neighbourCaptions: List<String>,
    ): String = buildString {
        if (destination.isNotBlank()) appendLine("Voyage : $destination")
        dateText?.let { appendLine("Date de la photo : $it") }
        gps?.let { appendLine("Coordonnées GPS de la photo : $it") }
        address?.let { appendLine("Adresse correspondant au GPS : $it") }
        userPlace?.let { appendLine("Lieu indiqué par le photographe (la photo n'a pas de GPS) : $it") }
        if (articles.isEmpty()) {
            appendLine("Aucun article Wikipédia trouvé pour ce lieu.")
        } else {
            appendLine()
            appendLine("Articles Wikipédia sur ce lieu ou à proximité :")
            articles.forEach { a ->
                val dist = a.distanceMeters?.let { " (à ${it.toInt()} m)" }.orEmpty()
                appendLine("<article titre=\"${a.title}\"$dist>")
                appendLine(a.extract.take(2500))
                appendLine("</article>")
            }
        }
        if (neighbourCaptions.isNotEmpty()) {
            appendLine()
            appendLine("Légendes déjà écrites pour des photos prises au même endroit (ne pas les répéter) :")
            neighbourCaptions.forEach { appendLine("- $it") }
        }
        appendLine()
        append("Rédige la légende de cette photo.")
    }

    private fun parse(text: String): Caption? {
        val place = Regex("""LIEU\s*:\s*(.+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val summary = Regex("""(?:TEXTE|R[ÉE]SUM[ÉE])\s*:\s*([\s\S]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()
        if (summary.isNullOrBlank()) return text.trim().takeIf { it.isNotEmpty() }?.let { Caption(place.orEmpty(), it) }
        return Caption(place.orEmpty(), summary)
    }

    override fun close() = client.close()

    companion object {
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM_PROMPT = """
            Tu rédiges les légendes d'un livre-souvenir de voyage, en français.

            La légende repose sur la localisation de la photo : les coordonnées GPS et l'adresse
            fournies, ou le lieu indiqué par le photographe quand la photo n'a pas de GPS. Ne
            déduis jamais le lieu de l'image seule. L'image sert seulement à choisir, parmi les
            articles Wikipédia fournis, celui qui correspond à ce qu'on voit (monument, plage,
            marais, rue…) ; ignore ceux qui n'ont pas de rapport.

            Format, très court :
            - LIEU : le nom du lieu précis s'il est certain (monument, site, quartier),
              sinon la commune (ville ou village). Quelques mots, sans la région.
            - TEXTE : style télégraphique, 2 lignes au plus (110 caractères maximum), sans
              phrase complète ni formule creuse. Un fait historique ou géographique sûr, tiré
              des extraits (date, particularité). Exemple : « Remparts XIVe-XVe s., 1,4 km,
              parmi les mieux conservés d'Europe. »
            - N'invente ni nom ni date. Si les extraits ne suffisent pas, décris ce qui se voit
              en quelques mots.
            - Ne parle pas des personnes présentes : elles sont ajoutées à part.
            - Si des photos voisines ont déjà une légende, garde le même LIEU et écris un TEXTE
              sur un autre aspect (un détail visible, un autre point de vue), sans répéter.

            Réponds exactement sous cette forme, sans rien d'autre :
            LIEU : <lieu>
            TEXTE : <texte>
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
