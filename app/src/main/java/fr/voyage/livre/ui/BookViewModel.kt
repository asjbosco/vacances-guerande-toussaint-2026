package fr.voyage.livre.ui

import android.app.Application
import android.util.Log
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
import fr.voyage.livre.data.CaptionSource
import fr.voyage.livre.data.Participant
import fr.voyage.livre.data.Settings
import fr.voyage.livre.share.PdfExporter
import fr.voyage.livre.share.ShareHelper
import fr.voyage.livre.share.ShareTarget
import kotlinx.coroutines.CancellationException
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
    data object Library : Screen
    data object Home : Screen
    data object Picker : Screen
    data class Editor(val pageId: String) : Screen
    data class Crop(val pageId: String) : Screen
    data class Viewer(val startIndex: Int = 0) : Screen
    data object PdfPreview : Screen
    data object Settings : Screen
}

data class UiState(
    /** Tous les livres enregistrés (bibliothèque), du plus récent au plus ancien. */
    val library: List<Book> = emptyList(),
    /** Identifiant du livre ouvert, s'il y en a un. */
    val currentId: String? = null,
    val book: Book = Book(),
    val loaded: Boolean = false,
    val screen: Screen = Screen.Library,
    val galleryPhotos: List<GalleryPhoto> = emptyList(),
    val galleryLoading: Boolean = false,
    val busyMessage: String? = null,
    val message: String? = null,
    val apiKeySet: Boolean = false,
    val visionKeySet: Boolean = false,
    /** Chemin du dernier PDF fabriqué (pour l'aperçu avant partage). */
    val pdfFile: String? = null,
)

class BookViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BookRepository(app)
    private val settings = Settings(app)
    private val annotator = Annotator(app)

    private val _state = MutableStateFlow(
        UiState(apiKeySet = settings.apiKey.isNotBlank(), visionKeySet = settings.visionKey.isNotBlank())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var annotateJob: Job? = null

    init {
        viewModelScope.launch {
            val lib = repo.loadLibrary()
            _state.update { it.copy(library = lib, loaded = true, screen = Screen.Library) }
        }
    }

    // ---------- Navigation ----------

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    private fun say(text: String) = _state.update { it.copy(message = text) }

    /** Retour contextuel : de l'accueil on revient à la bibliothèque ; du recadrage à la photo ; sinon à l'accueil. */
    fun back() {
        val target = when (val s = _state.value.screen) {
            Screen.Home -> Screen.Library
            is Screen.Crop -> Screen.Editor(s.pageId)
            else -> Screen.Home
        }
        open(target)
    }

    // ---------- Bibliothèque (plusieurs livres) ----------

    fun backToLibrary() = open(Screen.Library)

    /** Ouvre un livre existant. */
    fun openBook(id: String) {
        val b = _state.value.library.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(currentId = id, book = b, screen = Screen.Home) }
    }

    /** Crée un nouveau livre vierge et ouvre ses réglages. */
    fun newBook() {
        val b = Book(updatedAt = System.currentTimeMillis())
        val newLib = listOf(b) + _state.value.library
        _state.update { it.copy(library = newLib, currentId = b.id, book = b, screen = Screen.Settings) }
        viewModelScope.launch { repo.saveLibrary(newLib) }
    }

    /** Supprime un livre (et les photos qu'il contient). */
    fun deleteBook(id: String) {
        val b = _state.value.library.firstOrNull { it.id == id } ?: return
        val newLib = _state.value.library.filterNot { it.id == id }
        val newCurrent = _state.value.currentId?.takeIf { it != id }
        _state.update {
            it.copy(
                library = newLib,
                currentId = newCurrent,
                book = newLib.firstOrNull { bk -> bk.id == newCurrent } ?: Book(),
            )
        }
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) { b.pages.forEach { File(it.photoPath).delete() } }
            repo.saveLibrary(newLib)
        }
    }

    private fun editBook(transform: (Book) -> Book) {
        val id = _state.value.currentId ?: return
        val updated = transform(_state.value.book).copy(id = id, updatedAt = System.currentTimeMillis())
        val newLib = _state.value.library.map { if (it.id == id) updated else it }
        _state.update { it.copy(book = updated, library = newLib) }
        viewModelScope.launch { repo.saveLibrary(newLib) }
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

    fun annotateOne(pageId: String, source: CaptionSource = CaptionSource.AUTO) = annotate(listOf(pageId), source)

    /** Rédige (ou réécrit) les légendes de plusieurs photos d'un coup, avec la source choisie. */
    fun annotateSelection(ids: List<String>, source: CaptionSource) = annotate(ids, source)

    private fun annotate(ids: List<String>, source: CaptionSource = CaptionSource.AUTO) {
        if (ids.isEmpty()) { say("Toutes les photos sont déjà annotées."); return }
        if (annotateJob?.isActive == true) return
        val key = settings.apiKey
        if (source == CaptionSource.CLAUDE && key.isBlank()) {
            say("Ajoutez d'abord votre clé API Claude dans les réglages (⚙️).")
            return
        }
        annotateJob = viewModelScope.launch {
            // Pour Wikipédia on n'ouvre pas de connexion à Claude.
            val captioner = if (source == CaptionSource.WIKIPEDIA) null
                else key.takeIf { it.isNotBlank() }?.let { ClaudeCaptioner(it) }
            if (source == CaptionSource.AUTO && captioner == null) {
                say("Pas de clé API Claude : les textes viendront seulement de Wikipédia.")
            }
            try {
                var failures = 0
                ids.forEachIndexed { i, id ->
                    val page = _state.value.book.pages.firstOrNull { it.id == id } ?: return@forEachIndexed
                    _state.update { it.copy(busyMessage = "Rédaction des légendes… ${i + 1}/${ids.size}") }
                    editPage(id) { it.copy(status = AnnotationStatus.RUNNING, error = null) }
                    try {
                        val updated = annotator.annotate(page, _state.value.book, captioner, source, settings.visionKey)
                        // Garde les personnes cochées pendant l'annotation.
                        editPage(id) { current -> updated.copy(participantIds = current.participantIds) }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        // Aucune photo ne doit bloquer le lot : on note l'erreur et on continue.
                        failures++
                        Log.e("LivreDeVoyage", "Échec annotation photo $id", t)
                        val code = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(160)
                        editPage(id) { it.copy(status = AnnotationStatus.ERROR, error = "Échec : $code") }
                    }
                }
                say(
                    if (failures == 0) "Légendes prêtes ! Relisez-les et cochez les participants."
                    else "Terminé : $failures photo(s) en échec — le détail s'affiche en rouge sur chacune."
                )
            } finally {
                captioner?.close()
                _state.update { it.copy(busyMessage = null) }
            }
        }
    }

    /** Raccourcit le texte actuel de la légende (via Claude) pour tenir en ~3 lignes. */
    fun shortenCaption(pageId: String) {
        val page = _state.value.book.pages.firstOrNull { it.id == pageId } ?: return
        if (page.summary.isBlank()) { say("Il n'y a pas encore de texte à raccourcir."); return }
        val key = settings.apiKey
        if (key.isBlank()) { say("Ajoutez votre clé API Claude dans les réglages (⚙️) pour raccourcir le texte."); return }
        if (annotateJob?.isActive == true) return
        annotateJob = viewModelScope.launch {
            val captioner = ClaudeCaptioner(key)
            _state.update { it.copy(busyMessage = "Raccourcissement du texte…") }
            try {
                val short = captioner.shorten(page.summary)
                if (short.isNotBlank()) {
                    editPage(pageId) { it.copy(summary = short) }
                    say("Texte raccourci — relisez-le avant de l'utiliser.")
                } else {
                    say("Raccourcissement impossible, réessayez.")
                }
            } catch (e: CaptionException) {
                say(e.message ?: "Erreur")
            } catch (e: Exception) {
                say("Raccourcissement impossible : ${e.message}")
            } finally {
                captioner.close()
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

    /** Recadre une photo selon un rectangle en fractions (0..1) de l'image. */
    fun cropPhoto(pageId: String, left: Float, top: Float, right: Float, bottom: Float) {
        val page = _state.value.book.pages.firstOrNull { it.id == pageId } ?: return
        viewModelScope.launch {
            val newPath = PhotoImporter.cropToNewFile(page.photoPath, left, top, right, bottom, repo.photosDir)
            if (newPath == null) { say("Recadrage impossible."); return@launch }
            val old = page.photoPath
            editPage(pageId) { it.copy(photoPath = newPath) }
            File(old).delete()
            open(Screen.Editor(pageId))
        }
    }

    /** Fait pivoter une photo de ±90° (redresser). */
    fun rotatePhoto(pageId: String, degrees: Int) {
        val page = _state.value.book.pages.firstOrNull { it.id == pageId } ?: return
        viewModelScope.launch {
            val newPath = PhotoImporter.rotateToNewFile(page.photoPath, degrees, repo.photosDir)
            if (newPath == null) { say("Rotation impossible."); return@launch }
            val old = page.photoPath
            editPage(pageId) { it.copy(photoPath = newPath) }
            File(old).delete()
        }
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
        // Les dates sont facultatives : vides = aucune limite (toutes les photos).
        val startOk = start.isBlank() || parseDate(start) != null
        val endOk = end.isBlank() || parseDate(end) != null
        if (!startOk || !endOk) {
            say("Dates attendues au format jj/mm/aaaa (ou laissez vide)")
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

    fun saveVisionKey(key: String) {
        settings.visionKey = key
        _state.update { it.copy(visionKeySet = key.isNotBlank()) }
        say(if (key.isBlank()) "Clé Google Vision effacée" else "Clé Google Vision enregistrée")
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

    /** Fabrique le livre en PDF imprimable (A4) puis ouvre l'aperçu avant partage. */
    fun exportPdf() {
        val book = _state.value.book
        if (book.pages.isEmpty()) { say("Aucune photo dans le livre."); return }
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Fabrication du PDF…") }
            val file = runCatching { PdfExporter.export(getApplication(), book, book.pages) }
                .getOrElse { say("PDF impossible : ${it.message}"); null }
            _state.update { it.copy(busyMessage = null, pdfFile = file?.absolutePath) }
            if (file != null) open(Screen.PdfPreview)
        }
    }

    /** Partage (enregistrer / envoyer / imprimer) le PDF déjà fabriqué et prévisualisé. */
    fun sharePdf() {
        val path = _state.value.pdfFile ?: return
        runCatching { PdfExporter.share(getApplication(), File(path), _state.value.book) }
            .onFailure { say("Partage impossible : ${it.message}") }
    }

    companion object {
        private const val DAY = 24L * 3600 * 1000

        fun parseDate(text: String): Long? = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).apply { isLenient = false }.parse(text.trim())?.time
        }.getOrNull()
    }
}
