package fr.voyage.livre.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.voyage.livre.annotate.Annotator
import fr.voyage.livre.annotate.CaptionException
import fr.voyage.livre.annotate.ClaudeCaptioner
import fr.voyage.livre.annotate.Gallery
import fr.voyage.livre.annotate.GalleryPhoto
import fr.voyage.livre.annotate.PhotoImporter
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.Book
import fr.voyage.livre.data.BookPage
import fr.voyage.livre.data.BookRepository
import fr.voyage.livre.data.Participant
import fr.voyage.livre.data.FaceIndex
import fr.voyage.livre.data.Settings
import fr.voyage.livre.faces.FaceCluster
import fr.voyage.livre.faces.FaceMatcher
import fr.voyage.livre.faces.FaceRecognizer
import fr.voyage.livre.faces.FaceRepository
import fr.voyage.livre.data.FaceRecord
import fr.voyage.livre.share.PdfExporter
import fr.voyage.livre.share.ShareHelper
import fr.voyage.livre.share.ShareTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data object Picker : Screen
    data class Editor(val pageId: String) : Screen
    data class Viewer(val startIndex: Int = 0) : Screen
    data object Settings : Screen
    data object Faces : Screen
}

data class UiState(
    val book: Book = Book(),
    val loaded: Boolean = false,
    val screen: Screen = Screen.Home,
    val galleryPhotos: List<GalleryPhoto> = emptyList(),
    val galleryLoading: Boolean = false,
    val busyMessage: String? = null,
    val message: String? = null,
    val apiKeySet: Boolean = false,
    /** Groupes de visages inconnus restant à nommer. */
    val faceClusters: List<FaceCluster> = emptyList(),
    /** Nombre de photos où des participants ont été cochés automatiquement pendant cette session. */
    val facesTicked: Int = 0,
)

class BookViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BookRepository(app)
    private val settings = Settings(app)
    private val annotator = Annotator(app)
    private val faceRepo = FaceRepository(app)
    private var faceIndex = FaceIndex()

    private val _state = MutableStateFlow(UiState(apiKeySet = settings.apiKey.isNotBlank()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var annotateJob: Job? = null

    init {
        viewModelScope.launch {
            val book = repo.load()
            faceIndex = faceRepo.load()
            _state.update { it.copy(book = book, loaded = true) }
        }
    }

    // ---------- Navigation ----------

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }
    fun back() = open(Screen.Home)
    fun clearMessage() = _state.update { it.copy(message = null) }
    private fun say(text: String) = _state.update { it.copy(message = text) }

    private fun editBook(transform: (Book) -> Book) {
        _state.update { it.copy(book = transform(it.book)) }
        val snapshot = _state.value.book
        viewModelScope.launch { repo.save(snapshot) }
    }

    private fun editPage(id: String, transform: (BookPage) -> BookPage) = editBook { b ->
        b.copy(pages = b.pages.map { if (it.id == id) transform(it) else it })
    }

    // ---------- Galerie ----------

    fun loadGallery() {
        val book = _state.value.book
        val start = parseDate(book.startDate) ?: 0L
        val end = (parseDate(book.endDate) ?: System.currentTimeMillis()) + DAY - 1
        _state.update { it.copy(galleryLoading = true) }
        viewModelScope.launch {
            val photos = runCatching { Gallery.photosBetween(getApplication(), start, end) }
                .getOrElse { say("Impossible de lire la galerie : ${it.message}"); emptyList() }
            _state.update { it.copy(galleryPhotos = photos, galleryLoading = false) }
        }
    }

    fun addPhotos(selected: List<GalleryPhoto>) {
        val existing = _state.value.book.pages.map { it.sourceUri }.toSet()
        val toImport = selected.filter { it.uri.toString() !in existing }.sortedBy { it.takenAt }
        open(Screen.Home)
        if (toImport.isEmpty()) return
        viewModelScope.launch {
            var done = 0
            var noGps = 0
            for (photo in toImport) {
                _state.update { it.copy(busyMessage = "Import des photos… ${done + 1}/${toImport.size}") }
                runCatching { PhotoImporter.import(getApplication(), photo.uri, photo.takenAt, repo.photosDir) }
                    .onSuccess { page ->
                        if (!page.hasGps) noGps++
                        editBook { b -> b.copy(pages = (b.pages + page).sortedBy { it.takenAt ?: Long.MAX_VALUE }) }
                    }
                done++
            }
            _state.update { it.copy(busyMessage = null) }
            if (noGps > 0) say("$noGps photo(s) sans position GPS : le lieu sera deviné d'après l'image.")
        }
    }

    // ---------- Annotation ----------

    fun annotateAll(onlyMissing: Boolean = true) {
        if (annotateJob?.isActive == true) return
        if (!onlyMissing) {
            // On repart de zéro pour que les photos voisines ne se répètent pas.
            editBook { b -> b.copy(pages = b.pages.map { it.copy(status = AnnotationStatus.TODO) }) }
        }
        val pages = _state.value.book.pages.filter {
            !onlyMissing || it.status != AnnotationStatus.DONE
        }
        annotate(pages.map { it.id })
    }

    fun annotateOne(pageId: String) = annotate(listOf(pageId))

    private fun annotate(ids: List<String>) {
        if (ids.isEmpty()) { say("Toutes les photos sont déjà annotées."); return }
        if (annotateJob?.isActive == true) return
        annotateJob = viewModelScope.launch {
            val key = settings.apiKey
            val captioner = key.takeIf { it.isNotBlank() }?.let { ClaudeCaptioner(it) }
            if (captioner == null) say("Pas de clé API Claude : les textes viendront seulement de Wikipédia.")
            try {
                ids.forEachIndexed { i, id ->
                    val page = _state.value.book.pages.firstOrNull { it.id == id } ?: return@forEachIndexed
                    _state.update { it.copy(busyMessage = "Rédaction des légendes… ${i + 1}/${ids.size}") }
                    editPage(id) { it.copy(status = AnnotationStatus.RUNNING, error = null) }
                    val result = runCatching { annotator.annotate(page, _state.value.book, captioner) }
                    result.onSuccess { updated ->
                        // Garde les personnes cochées pendant l'annotation.
                        editPage(id) { current -> updated.copy(participantIds = current.participantIds) }
                    }.onFailure { e ->
                        editPage(id) { it.copy(status = AnnotationStatus.ERROR, error = e.message) }
                        if (e is CaptionException) {
                            say(e.message ?: "Erreur")
                            // Une clé invalide ou l'absence de réseau bloquerait toutes les photos.
                            ids.drop(i + 1).forEach { rest -> editPage(rest) { it.copy(status = AnnotationStatus.TODO) } }
                            return@launch
                        }
                    }
                }
                say("Légendes prêtes ! Relisez-les et cochez les participants.")
            } finally {
                captioner?.close()
                _state.update { it.copy(busyMessage = null) }
            }
        }
    }

    // ---------- Édition ----------

    fun updatePlace(id: String, text: String) = editPage(id) { it.copy(place = text) }
    fun updateSummary(id: String, text: String) = editPage(id) { it.copy(summary = text) }
    fun toggleParticipant(pageId: String, participantId: String) {
        val removing = _state.value.book.pages.firstOrNull { it.id == pageId }?.participantIds?.contains(participantId) == true
        editPage(pageId) { p ->
            val ids = if (removing) p.participantIds - participantId else p.participantIds + participantId
            p.copy(participantIds = ids)
        }
        if (removing) {
            // Vous décochez une personne reconnue : ce visage n'était pas elle, on ne s'en sert plus.
            editFaces { f ->
                if (f.pageId == pageId && f.personId == participantId) f.copy(personId = null, auto = false, ignored = true) else f
            }
        }
    }
    fun setAllParticipants(pageId: String) = editPage(pageId) { p ->
        p.copy(participantIds = (p.participantIds + _state.value.book.travelers.map { it.id }).distinct())
    }

    fun deletePage(id: String) {
        val page = _state.value.book.pages.firstOrNull { it.id == id } ?: return
        editBook { b -> b.copy(pages = b.pages.filterNot { it.id == id }) }
        File(page.photoPath).delete()
        // Les visages nommés restent des modèles utiles ; les autres disparaissent avec la photo.
        val gone = faceIndex.faces.filter { it.pageId == id && (it.personId == null || it.auto) }
        gone.forEach { File(it.thumbPath).delete() }
        faceIndex = faceIndex.copy(faces = faceIndex.faces - gone.toSet())
        saveFaces()
        open(Screen.Home)
    }

    fun movePage(id: String, delta: Int) = editBook { b ->
        val list = b.pages.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@editBook b
        list.add(j, list.removeAt(i))
        b.copy(pages = list)
    }

    // ---------- Réglages ----------

    fun saveBookInfo(title: String, destination: String, start: String, end: String, radius: String) {
        if (parseDate(start) == null || parseDate(end) == null) {
            say("Dates attendues au format jj/mm/aaaa")
            return
        }
        val meters = radius.trim().toIntOrNull()?.takeIf { it in 0..20_000 }
            ?: run { say("Distance attendue en mètres (ex. 300)"); return }
        editBook {
            it.copy(
                title = title.trim(), destination = destination.trim(),
                startDate = start.trim(), endDate = end.trim(), groupRadiusMeters = meters,
            )
        }
        say("Enregistré")
    }

    fun saveApiKey(key: String) {
        settings.apiKey = key
        _state.update { it.copy(apiKeySet = key.isNotBlank()) }
        say(if (key.isBlank()) "Clé API effacée" else "Clé API enregistrée")
    }

    fun addParticipant(name: String, description: String, onTrip: Boolean = true) {
        if (name.isBlank()) return
        editBook {
            it.copy(participants = it.participants + Participant(name = name.trim(), description = description.trim(), onTrip = onTrip))
        }
    }

    fun setOnTrip(id: String, onTrip: Boolean) = editBook { b ->
        b.copy(participants = b.participants.map { if (it.id == id) it.copy(onTrip = onTrip) else it })
    }

    fun removeParticipant(id: String) {
        editBook { b ->
            b.copy(
                participants = b.participants.filterNot { it.id == id },
                pages = b.pages.map { p -> p.copy(participantIds = p.participantIds - id) },
            )
        }
        editFaces { f -> if (f.personId == id) f.copy(personId = null, auto = false) else f }
    }

    // ---------- Visages ----------

    private fun saveFaces() {
        val snapshot = faceIndex
        viewModelScope.launch { faceRepo.save(snapshot) }
    }

    private fun editFaces(transform: (FaceRecord) -> FaceRecord) {
        faceIndex = faceIndex.copy(faces = faceIndex.faces.map(transform))
        saveFaces()
        _state.update { it.copy(faceClusters = FaceMatcher.clusters(faceIndex.faces)) }
    }

    /**
     * Repère les visages des photos pas encore analysées, reconnaît les personnes déjà nommées,
     * coche les participants, puis regroupe les visages inconnus pour « Qui est-ce ? ».
     */
    fun recognizeFaces() {
        if (annotateJob?.isActive == true) return
        annotateJob = viewModelScope.launch {
            val todo = _state.value.book.pages.filter { !it.facesScanned }
            try {
                if (todo.isNotEmpty()) {
                    val recognizer = withContext(Dispatchers.IO) {
                        runCatching { FaceRecognizer(getApplication(), faceRepo.thumbsDir) }.getOrNull()
                    }
                    if (recognizer == null) { say("La reconnaissance des visages n'a pas pu démarrer."); return@launch }
                    try {
                        todo.forEachIndexed { i, page ->
                            _state.update { it.copy(busyMessage = "Recherche des visages… ${i + 1}/${todo.size}") }
                            val found = withContext(Dispatchers.Default) {
                                runCatching {
                                    val bmp = PhotoImporter.decodeFileScaled(page.photoPath, 1600) ?: return@runCatching emptyList()
                                    recognizer.findFaces(bmp)
                                }.getOrDefault(emptyList())
                            }
                            faceIndex = faceIndex.copy(
                                faces = faceIndex.faces + found.map {
                                    FaceRecord(pageId = page.id, thumbPath = it.thumbPath, embedding = it.embedding.toList())
                                }
                            )
                            editPage(page.id) { it.copy(facesScanned = true) }
                        }
                    } finally {
                        withContext(Dispatchers.IO) { recognizer.close() }
                    }
                }
                val ticked = matchKnownFaces()
                val clusters = FaceMatcher.clusters(faceIndex.faces)
                _state.update { it.copy(faceClusters = clusters, facesTicked = ticked) }
                open(Screen.Faces)
            } finally {
                _state.update { it.copy(busyMessage = null) }
            }
        }
    }

    /** Reconnaît les visages inconnus d'après les personnes nommées ; renvoie le nombre de photos cochées. */
    private fun matchKnownFaces(): Int {
        val before = faceIndex.faces.associateBy { it.id }
        faceIndex = faceIndex.copy(faces = FaceMatcher.autoAssign(faceIndex.faces))
        val newlyNamed = faceIndex.faces.filter { it.personId != null && before[it.id]?.personId == null }
        tickParticipants(newlyNamed)
        saveFaces()
        return newlyNamed.map { it.pageId }.distinct().size
    }

    /** Coche, sur chaque photo, les personnes dont le visage vient d'être reconnu ou nommé. */
    private fun tickParticipants(faces: List<FaceRecord>) {
        if (faces.isEmpty()) return
        val byPage = faces.groupBy({ it.pageId }, { it.personId!! })
        editBook { b ->
            b.copy(pages = b.pages.map { p ->
                byPage[p.id]?.let { ids -> p.copy(participantIds = (p.participantIds + ids).distinct()) } ?: p
            })
        }
    }

    /** Réponse à « Qui est-ce ? » : tout le groupe reçoit ce nom, puis on relance la reconnaissance. */
    fun nameCluster(cluster: FaceCluster, personId: String) {
        val ids = cluster.faceIds.toSet()
        // Une personne n'apparaît qu'une fois par photo : on ne nomme qu'un visage par photo.
        val alreadyOnPage = faceIndex.faces.filter { it.personId == personId }.map { it.pageId }.toMutableSet()
        val named = mutableListOf<FaceRecord>()
        faceIndex = faceIndex.copy(faces = faceIndex.faces.map { f ->
            if (f.id in ids && alreadyOnPage.add(f.pageId)) {
                f.copy(personId = personId, auto = false, ignored = false).also { named += it }
            } else f
        })
        tickParticipants(named)
        val more = matchKnownFaces()
        _state.update {
            it.copy(faceClusters = FaceMatcher.clusters(faceIndex.faces), facesTicked = it.facesTicked + named.map { n -> n.pageId }.distinct().size + more)
        }
    }

    fun nameClusterNewPerson(cluster: FaceCluster, name: String) {
        if (name.isBlank()) return
        val person = Participant(name = name.trim(), onTrip = false)
        editBook { it.copy(participants = it.participants + person) }
        nameCluster(cluster, person.id)
    }

    /** « Je ne sais pas » / « Pas un visage » : on ne le proposera plus. */
    fun ignoreCluster(cluster: FaceCluster) {
        val ids = cluster.faceIds.toSet()
        editFaces { f -> if (f.id in ids) f.copy(ignored = true) else f }
    }

    // ---------- Partage ----------

    fun share(target: ShareTarget, pageIds: List<String>? = null) {
        val book = _state.value.book
        val pages = pageIds?.let { ids -> book.pages.filter { it.id in ids } } ?: book.pages
        if (pages.isEmpty()) { say("Aucune photo à partager."); return }
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Préparation des images…") }
            runCatching { ShareHelper.share(getApplication(), book, pages, target) }
                .onFailure { say("Partage impossible : ${it.message}") }
            _state.update { it.copy(busyMessage = null) }
        }
    }

    /** Fabrique le livre en PDF imprimable (A4) puis propose de l'enregistrer ou de l'envoyer. */
    fun exportPdf() {
        val book = _state.value.book
        if (book.pages.isEmpty()) { say("Aucune photo dans le livre."); return }
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Fabrication du PDF…") }
            runCatching {
                val file = PdfExporter.export(getApplication(), book, book.pages)
                PdfExporter.share(getApplication(), file, book)
            }.onFailure { say("PDF impossible : ${it.message}") }
            _state.update { it.copy(busyMessage = null) }
        }
    }

    companion object {
        private const val DAY = 24L * 3600 * 1000

        fun parseDate(text: String): Long? = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).apply { isLenient = false }.parse(text.trim())?.time
        }.getOrNull()
    }
}
