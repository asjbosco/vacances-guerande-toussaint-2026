package fr.voyage.livre.annotate

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recherche par l'image via Google Cloud Vision (comparable à Google Lens).
 * Sert de secours automatique quand Claude n'identifie pas une œuvre d'art ou un
 * bâtiment : renvoie des pistes de noms, de la plus probable à la moins probable.
 * En cas d'erreur (clé absente/invalide, réseau…), renvoie une liste vide : le
 * secours est silencieux et ne bloque jamais la rédaction de la légende.
 */
object GoogleVision {

    private const val ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"

    suspend fun identify(apiKey: String, photo: Bitmap, max: Int = 5): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()

        val jpeg = ByteArrayOutputStream().use { out ->
            photo.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        val request = JSONObject()
            .put("image", JSONObject().put("content", Base64.encodeToString(jpeg, Base64.NO_WRAP)))
            .put(
                "features",
                JSONArray()
                    .put(JSONObject().put("type", "WEB_DETECTION").put("maxResults", 5))
                    .put(JSONObject().put("type", "LANDMARK_DETECTION").put("maxResults", 3)),
            )
        val body = JSONObject().put("requests", JSONArray().put(request))

        val json = runCatching { post("$ENDPOINT?key=$apiKey", body) }.getOrNull() ?: return@withContext emptyList()
        parse(json, max)
    }

    private fun parse(json: JSONObject, max: Int): List<String> {
        val resp = json.optJSONArray("responses")?.optJSONObject(0) ?: return emptyList()
        val names = LinkedHashSet<String>()
        val web = resp.optJSONObject("webDetection")

        // 1. Meilleure hypothèse (la plus parlante).
        web?.optJSONArray("bestGuessLabels")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("label")?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
            }
        }
        // 2. Monuments / points de repère reconnus.
        resp.optJSONArray("landmarkAnnotations")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
            }
        }
        // 3. Entités web les mieux notées.
        web?.optJSONArray("webEntities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val d = e.optString("description").trim()
                if (d.isNotEmpty() && e.optDouble("score", 0.0) >= 0.5) names.add(d)
            }
        }
        return names.toList().take(max)
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.doOutput = true
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Vision a répondu $code")
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
