package fr.voyage.livre.faces

import fr.voyage.livre.data.FaceRecord

/** Un groupe de visages inconnus qui se ressemblent : on demande « Qui est-ce ? » une seule fois. */
data class FaceCluster(val faceIds: List<String>, val thumbPaths: List<String>)

/**
 * Compare les empreintes de visages. Les seuils sont volontairement prudents :
 * mieux vaut demander que se tromper (vous corrigez ensuite d'un geste).
 */
object FaceMatcher {

    /** Ressemblance minimale pour reconnaître une personne déjà nommée. */
    const val MATCH = 0.55f

    /** Ressemblance minimale pour regrouper deux visages inconnus. */
    const val SAME_GROUP = 0.60f

    private fun similarity(a: List<Float>, b: List<Float>): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * Reconnaît les visages inconnus des photos [candidatePageIds] d'après ceux que vous avez
     * déjà nommés (dans toutes vos histoires).
     * Une même personne n'est retenue qu'une fois par photo (le visage le plus ressemblant).
     */
    fun autoAssign(faces: List<FaceRecord>, candidatePageIds: Set<String>): List<FaceRecord> {
        val references = faces.filter { it.personId != null && !it.auto && !it.ignored }
        if (references.isEmpty()) return faces
        val byPerson = references.groupBy { it.personId!! }

        data class Guess(val faceId: String, val pageId: String, val personId: String, val score: Float)

        val guesses = faces
            .filter { it.personId == null && !it.ignored && it.pageId in candidatePageIds }
            .mapNotNull { face ->
                byPerson.map { (person, refs) ->
                    person to refs.maxOf { similarity(face.embedding, it.embedding) }
                }.maxByOrNull { it.second }
                    ?.takeIf { it.second >= MATCH }
                    ?.let { Guess(face.id, face.pageId, it.first, it.second) }
            }
        // Personne déjà présente sur la photo, ou deux visages pour la même personne : on garde le meilleur.
        val taken = faces.filter { it.personId != null }.map { it.pageId to it.personId }.toSet()
        val kept = guesses
            .filter { (it.pageId to it.personId) !in taken }
            .groupBy { it.pageId to it.personId }
            .map { (_, g) -> g.maxBy { it.score } }
            .associateBy { it.faceId }

        return faces.map { f -> kept[f.id]?.let { f.copy(personId = it.personId, auto = true) } ?: f }
    }

    /** Regroupe les visages encore inconnus, les plus grands groupes d'abord. */
    fun clusters(faces: List<FaceRecord>): List<FaceCluster> {
        val unknown = faces.filter { it.personId == null && !it.ignored }
        val groups = mutableListOf<MutableList<FaceRecord>>()
        for (face in unknown) {
            val best = groups
                .map { g -> g to g.maxOf { similarity(face.embedding, it.embedding) } }
                .maxByOrNull { it.second }
            if (best != null && best.second >= SAME_GROUP) best.first += face else groups += mutableListOf(face)
        }
        return groups
            .sortedByDescending { it.size }
            .map { g -> FaceCluster(g.map { it.id }, g.map { it.thumbPath }) }
    }
}
