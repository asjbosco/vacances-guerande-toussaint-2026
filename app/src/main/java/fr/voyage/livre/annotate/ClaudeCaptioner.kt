package fr.voyage.livre.annotate

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Caption(val place: String, val summary: String)

/** Erreur à afficher telle quelle à l'utilisateur. */
class CaptionException(message: String) : Exception(message)

/**
 * Demande à Claude le lieu et un court texte pour une photo, à partir de sa localisation,
 * des articles Wikipédia proches et de l'image.
 *
 * Appel direct de l'API (simple requête HTTPS, comme pour Wikipédia) : la bibliothèque
 * Java d'Anthropic, pensée pour les serveurs, faisait fermer l'application sur Android.
 */
class ClaudeCaptioner(private val apiKey: String) : AutoCloseable {

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
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 4000)
            put("system", SYSTEM_PROMPT)
            put("output_config", JSONObject().put("effort", "low"))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                        })
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", context(destination, dateText, address, userPlace, gps, articles, neighbourCaptions))
                    })
                )
            }))
        }.toString()

        val response = send(body)
        // Claude peut décliner certaines demandes : on se rabat alors sur Wikipédia.
        if (response.optString("stop_reason") == "refusal") return@withContext null
        val blocks = response.optJSONArray("content") ?: JSONArray()
        val text = (0 until blocks.length())
            .map { blocks.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("text") }
        parse(text)
    }

    /** Envoie la requête ; réessaie une fois si le service est surchargé. */
    private suspend fun send(body: String): JSONObject {
        repeat(2) { attempt ->
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", API_VERSION)
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) return JSONObject(text)

                val apiMessage = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                when {
                    code == 401 -> throw CaptionException("Clé API refusée : vérifiez-la dans les réglages.")
                    code == 403 -> throw CaptionException("Cette clé API n'a pas accès à Claude.")
                    code == 400 && apiMessage?.contains("credit", ignoreCase = true) == true ->
                        throw CaptionException("Crédit Anthropic épuisé : ajoutez du crédit sur console.anthropic.com.")
                    code == 400 -> throw CaptionException("Demande refusée par Claude : ${apiMessage ?: code}")
                    (code == 429 || code >= 500) && attempt == 0 -> delay(5_000)
                    code == 429 -> throw CaptionException("Trop de demandes d'un coup : réessayez dans une minute.")
                    else -> throw CaptionException("Claude est momentanément indisponible (erreur $code).")
                }
            } catch (e: IOException) {
                throw CaptionException("Pas de connexion Internet, ou Claude injoignable.")
            } finally {
                conn.disconnect()
            }
        }
        throw CaptionException("Claude est momentanément indisponible.")
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

    override fun close() = Unit

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2026-10-21"

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
