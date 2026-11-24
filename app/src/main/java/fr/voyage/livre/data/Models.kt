package fr.voyage.livre.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Participant(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
)

@Serializable
enum class AnnotationStatus { TODO, RUNNING, DONE, ERROR }

/**
 * Source choisie pour rédiger une légende :
 * - AUTO : Claude si une clé API est enregistrée, sinon Wikipédia (comportement par défaut) ;
 * - CLAUDE : forcer la rédaction par Claude ;
 * - WIKIPEDIA : reprendre le début de l'article Wikipédia, sans Claude.
 */
@Serializable
enum class CaptionSource { AUTO, CLAUDE, WIKIPEDIA }

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
    /** Qui a rédigé le texte actuel : "claude", "wikipedia" ou "" (pas encore). */
    val textSource: String = "",
    val participantIds: List<String> = emptyList(),
    val status: AnnotationStatus = AnnotationStatus.TODO,
    val error: String? = null,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
}

@Serializable
data class Book(
    val title: String = "",
    /** Région du voyage : aide à retrouver le lieu quand une photo n'a pas de GPS. */
    val destination: String = "",
    /** Dates au format jj/mm/aaaa (facultatives). Vides = toutes les photos sont accessibles. */
    val startDate: String = "",
    val endDate: String = "",
    val participants: List<Participant> = DEFAULT_PARTICIPANTS,
    val pages: List<BookPage> = emptyList(),
)

/** Les voyageurs de Guérande, Toussaint 2026 (modifiables dans les réglages). */
val DEFAULT_PARTICIPANTS = listOf(
    "Emmanuel", "Gaëlle", "Albéric", "Maylis", "Victor", "Juliette", "Charlotte",
).map { Participant(id = "p-" + it.lowercase(), name = it) }
