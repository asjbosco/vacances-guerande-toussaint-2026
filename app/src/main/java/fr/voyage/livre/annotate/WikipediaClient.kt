package fr.voyage.livre.annotate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class WikiArticle(val title: String, val extract: String, val distanceMeters: Double?)

/** Interroge Wikipédia en français (gratuit, sans clé). */
object WikipediaClient {

    private const val API = "https://fr.wikipedia.org/w/api.php"
    private const val USER_AGENT = "LivreDeVoyage/1.0 (application Android personnelle)"

    /** Articles Wikipédia géolocalisés les plus proches de la photo. */
    suspend fun nearby(lat: Double, lon: Double, limit: Int = 3): List<WikiArticle> = withContext(Dispatchers.IO) {
        for (radius in listOf(1000, 5000, 10000)) {
            val json = get(
                mapOf(
                    "action" to "query", "list" to "geosearch",
                    "gscoord" to "$lat|$lon", "gsradius" to "$radius", "gslimit" to "$limit",
                    "format" to "json",
                )
            )
            val items = json.optJSONObject("query")?.optJSONArray("geosearch") ?: continue
            if (items.length() == 0) continue
            val found = (0 until items.length()).map { items.getJSONObject(it) }
                .map { it.getString("title") to it.optDouble("dist") }
            val extracts = extracts(found.map { it.first })
            return@withContext found.map { (title, dist) ->
                WikiArticle(title, extracts[title].orEmpty(), dist)
            }.filter { it.extract.isNotBlank() }
        }
        emptyList()
    }

    /** Recherche par texte, quand la photo n'a pas de position GPS. */
    suspend fun search(query: String, limit: Int = 2): List<WikiArticle> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val json = get(
            mapOf(
                "action" to "query", "list" to "search", "srsearch" to query,
                "srlimit" to "$limit", "format" to "json",
            )
        )
        val items = json.optJSONObject("query")?.optJSONArray("search") ?: return@withContext emptyList()
        val titles = (0 until items.length()).map { items.getJSONObject(it).getString("title") }
        val extracts = extracts(titles)
        titles.map { WikiArticle(it, extracts[it].orEmpty(), null) }.filter { it.extract.isNotBlank() }
    }

    /** Introduction (en texte brut) des articles demandés. */
    private fun extracts(titles: List<String>): Map<String, String> {
        if (titles.isEmpty()) return emptyMap()
        val json = get(
            mapOf(
                "action" to "query", "prop" to "extracts", "exintro" to "1", "explaintext" to "1",
                "exlimit" to "${titles.size}", "redirects" to "1",
                "titles" to titles.joinToString("|"), "format" to "json",
            )
        )
        val query = json.optJSONObject("query") ?: return emptyMap()
        // Les redirections changent parfois le titre : on garde la correspondance.
        val renamed = mutableMapOf<String, String>()
        for (key in listOf("normalized", "redirects")) {
            val arr = query.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                renamed[o.getString("to")] = renamed[o.getString("from")] ?: o.getString("from")
            }
        }
        val pages = query.optJSONObject("pages") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        pages.keys().forEach { k ->
            val p = pages.getJSONObject(k)
            val title = p.optString("title")
            val extract = p.optString("extract").trim()
            result[renamed[title] ?: title] = extract
        }
        return result
    }

    private fun get(params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("$API?$query").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        return try {
            if (conn.responseCode != 200) error("Wikipédia a répondu ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Les premières phrases d'un texte, en restant sous une longueur donnée (≈ 3 lignes)
     * mais toujours sur des phrases ENTIÈRES : on n'ajoute une phrase que si elle tient
     * en entier, et on en garde au moins une même si elle dépasse. Jamais de phrase coupée.
     */
    fun firstSentences(text: String, maxChars: Int = 220): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return ""
        val sentences = Regex("(?<=[.!?])\\s+").split(clean).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return clean
        val sb = StringBuilder(sentences.first())
        for (s in sentences.drop(1)) {
            if (sb.length + 1 + s.length > maxChars) break
            sb.append(' ').append(s)
        }
        return sb.toString().trim()
    }
}
