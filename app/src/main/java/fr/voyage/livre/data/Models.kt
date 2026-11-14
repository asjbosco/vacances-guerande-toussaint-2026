package fr.voyage.livre.data

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.UUID

@Serializable
data class Participant(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    /** Faisait partie du voyage (affiché sur la couverture) ; sinon simple proche reconnu sur les photos. */
    val onTrip: Boolean = true,
)

@Serializable
enum class AnnotationStatus { TODO, RUNNING, DONE, ERROR }

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
    val place: String = "",
    val summary: String = "",
    val wikiTitle: String = "",
    val participantIds: List<String> = emptyList(),
    val status: AnnotationStatus = AnnotationStatus.TODO,
    val error: String? = null,
    /** Les visages de cette photo ont déjà été repérés. */
    val facesScanned: Boolean = false,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
}

@Serializable
data class Book(
    val title: String = "Vacances à Guérande – Toussaint 2026",
    /** Région du voyage : aide à retrouver le lieu quand une photo n'a pas de GPS. */
    val destination: String = "Guérande et la presqu'île guérandaise (Loire-Atlantique)",
    /** Dates au format jj/mm/aaaa, utilisées pour filtrer la galerie. */
    val startDate: String = "17/10/2026",
    val endDate: String = "02/11/2026",
    /** Deux photos plus proches que cette distance partagent le même contexte historique. */
    val groupRadiusMeters: Int = 300,
    val participants: List<Participant> = DEFAULT_PARTICIPANTS,
    val pages: List<BookPage> = emptyList(),
) {
    /** Les personnes du voyage (couverture du livre). */
    val travelers: List<Participant> get() = participants.filter { it.onTrip }
}

/** Les voyageurs de Guérande, Toussaint 2026 (modifiables dans les réglages). */
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
 * l'autre ; sans GPS, si elles ont été prises à moins de 20 minutes d'intervalle.
 */
fun BookPage.isNear(other: BookPage, radiusMeters: Int): Boolean {
    if (latitude != null && longitude != null && other.latitude != null && other.longitude != null) {
        return distanceMeters(latitude, longitude, other.latitude, other.longitude) <= radiusMeters
    }
    val t1 = takenAt ?: return false
    val t2 = other.takenAt ?: return false
    return kotlin.math.abs(t1 - t2) <= 20 * 60 * 1000L
}

/** Un visage repéré sur une photo du livre. */
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
