package fr.voyage.livre.data

import fr.voyage.livre.annotate.Annotator

/**
 * Présentation commune des légendes (livre, images partagées, PDF) :
 * 1. le lieu en gras ; 2. le texte, 2 lignes au plus ; 3. la date et les personnes.
 */
object CaptionFormat {

    fun people(page: BookPage, people: List<Participant>): List<String> =
        people.filter { it.id in page.participantIds }.map { it.name }

    /** « 25 octobre 2026 · Avec Maylis, Victor » */
    fun footer(page: BookPage, people: List<Participant>): String = listOfNotNull(
        page.takenAt?.let { Annotator.shortDate(it) },
        people(page, people).takeIf { it.isNotEmpty() }?.let { "Avec ${it.joinToString(", ")}" },
    ).joinToString(" · ")

    /** Texte brut pour les messages (le lieu entre astérisques s'affiche en gras dans WhatsApp). */
    fun plain(page: BookPage, people: List<Participant>): String = listOf(
        page.place.takeIf { it.isNotBlank() }?.let { "*${it.trim()}*" },
        page.summary.takeIf { it.isNotBlank() },
        footer(page, people).takeIf { it.isNotBlank() },
    ).filterNotNull().joinToString("\n")
}
