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
import fr.voyage.livre.data.Settings
import fr.voyage.livre.share.PdfExporter
import fr.voyage.livre.share.ShareHelper
import fr.voyage.livre.share.ShareTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data object Picker : Screen
    data class Editor(val pageId: String) : Screen
    data class Viewer(val startIndex: Int = 0) : Screen
    data object Settings : Screen
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
)

class BookViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BookRepository(app)
    private val settings = Settings(app)
    private val annotator = Annotator(app)

    private val _state = MutableStateFlow(UiState(apiKeySet = settings.apiKey.isNotBlank()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var annotateJob: Job? = null

    init {
        viewModelScope.launch {
            val book = repo.load()
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
    fun toggleParticipant(pageId: String, participantId: String) = editPage(pageId) { p ->
        val ids = if (participantId in p.participantIds) p.participantIds - participantId else p.participantIds + participantId
        p.copy(participantIds = ids)
    }
    fun setAllParticipants(pageId: String) = editPage(pageId) { p ->
        p.copy(participantIds = _state.value.book.participants.map { it.id })
    }

    fun deletePage(id: String) {
        val page = _state.value.book.pages.firstOrNull { it.id == id } ?: return
        editBook { b -> b.copy(pages = b.pages.filterNot { it.id == id }) }
        File(page.photoPath).delete()
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

    fun saveBookInfo(title: String, destination: String, start: String, end: String) {
        if (parseDate(start) == null || parseDate(end) == null) {
            say("Dates attendues au format jj/mm/aaaa")
            return
        }
        editBook { it.copy(title = title.trim(), destination = destination.trim(), startDate = start.trim(), endDate = end.trim()) }
        say("Enregistré")
    }

    fun saveApiKey(key: String) {
        settings.apiKey = key
        _state.update { it.copy(apiKeySet = key.isNotBlank()) }
        say(if (key.isBlank()) "Clé API effacée" else "Clé API enregistrée")
    }

    fun addParticipant(name: String, description: String) {
        if (name.isBlank()) return
        editBook { it.copy(participants = it.participants + Participant(name = name.trim(), description = description.trim())) }
    }

    fun removeParticipant(id: String) = editBook { b ->
        b.copy(
            participants = b.participants.filterNot { it.id == id },
            pages = b.pages.map { p -> p.copy(participantIds = p.participantIds - id) },
        )
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
