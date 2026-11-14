package fr.voyage.livre.data

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Un proche (famille, amis), commun à toutes les histoires. */
@Serializable
data class Participant(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
)

@Serializable
enum class AnnotationStatus { TODO, RUNNING, DONE, ERROR }

/** D'où viennent les légendes. */
@Serializable
enum class CaptionSource { WIKIPEDIA, CLAUDE }

@Serializable
data class BookPage(
    val id: String = UUID.randomUUID().toString(),
    /** Copie de la photo dans le stockage privé de l'application. */
    val photoPath: String,
    /** Adresse de la photo d'origine dans la galerie (pour éviter les doublons). */
    val sourceUri: String,
    val takenAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Ligne en gras : le lieu précis s'il est connu, sinon la ville ou le village. */
    val place: String = "",
    /** Le lieu a été saisi par vous (photo sans GPS) : il sert de base à la légende. */
    val placeByUser: Boolean = false,
    /** Texte court (2 lignes, style télégraphique). */
    val summary: String = "",
    val wikiTitle: String = "",
    val participantIds: List<String> = emptyList(),
    val status: AnnotationStatus = AnnotationStatus.TODO,
    val error: String? = null,
    /** Les visages de cette photo ont déjà été repérés. */
    val facesScanned: Boolean = false,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null

    /** Sans GPS, il faut que vous indiquiez le lieu ou le nom de la photo. */
    val needsPlace: Boolean get() = !hasGps && place.isBlank()
}

/** Une histoire (un voyage, une fête…) : son propre livre et ses propres photos. */
@Serializable
data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Nouvelle histoire",
    /** Région du voyage : aide à retrouver les articles Wikipédia. */
    val destination: String = "",
    /** Les proches qui ont participé (affichés sur la couverture). */
    val travelerIds: List<String> = emptyList(),
    /** Deux photos plus proches que cette distance partagent le même contexte historique. */
    val groupRadiusMeters: Int = 300,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pages: List<BookPage> = emptyList(),
) {
    fun travelers(people: List<Participant>): List<Participant> = people.filter { it.id in travelerIds }

    /** « 18 – 29 octobre 2026 », d'après les dates des photos. */
    val dateRange: String
        get() {
            val dates = pages.mapNotNull { it.takenAt }
            if (dates.isEmpty()) return ""
            val first = Date(dates.min())
            val last = Date(dates.max())
            val day = SimpleDateFormat("d MMMM yyyy", Locale.FRANCE)
            if (day.format(first) == day.format(last)) return day.format(first)
            val sameMonth = SimpleDateFormat("MM yyyy", Locale.FRANCE).let { it.format(first) == it.format(last) }
            val start = SimpleDateFormat(if (sameMonth) "d" else "d MMMM", Locale.FRANCE).format(first)
            return "$start – ${day.format(last)}"
        }
}

/** Les sept voyageurs de Guérande, proposés au premier lancement. */
val DEFAULT_PARTICIPANTS = listOf(
    "Emmanuel", "Gaëlle", "Albéric", "Maylis", "Victor", "Juliette", "Charlotte",
).map { Participant(id = "p-" + it.lowercase(), name = it) }

/** Distance en mètres entre deux points GPS (formule de haversine). */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/**
 * Deux photos sont « voisines » si elles ont été prises à moins de [radiusMeters] l'une de
 * l'autre ; sans GPS, si vous leur avez donné le même lieu.
 */
fun BookPage.isNear(other: BookPage, radiusMeters: Int): Boolean {
    if (latitude != null && longitude != null && other.latitude != null && other.longitude != null) {
        return distanceMeters(latitude, longitude, other.latitude, other.longitude) <= radiusMeters
    }
    return place.isNotBlank() && place.trim().equals(other.place.trim(), ignoreCase = true)
}

/** Un visage repéré sur une photo. */
@Serializable
data class FaceRecord(
    val id: String = UUID.randomUUID().toString(),
    val pageId: String,
    /** Petite image du visage, montrée dans « Qui est-ce ? ». */
    val thumbPath: String,
    /** Empreinte du visage (128 nombres) calculée sur le téléphone. */
    val embedding: List<Float>,
    /** La personne reconnue, ou null si inconnue. */
    val personId: String? = null,
    /** true si l'application a reconnu la personne seule ; false si c'est vous qui l'avez nommée. */
    val auto: Boolean = false,
    /** Visage à ne plus proposer (inconnu, erreur corrigée, pas un visage…). */
    val ignored: Boolean = false,
)

@Serializable
data class FaceIndex(val faces: List<FaceRecord> = emptyList())
