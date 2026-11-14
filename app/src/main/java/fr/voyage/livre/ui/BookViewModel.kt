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
import fr.voyage.livre.data.CaptionSource
import fr.voyage.livre.data.FaceIndex
import fr.voyage.livre.data.FaceRecord
import fr.voyage.livre.data.LibraryRepository
import fr.voyage.livre.data.Participant
import fr.voyage.livre.data.Settings
import fr.voyage.livre.faces.FaceCluster
import fr.voyage.livre.faces.FaceMatcher
import fr.voyage.livre.faces.FaceRecognizer
import fr.voyage.livre.faces.FaceRepository
import fr.voyage.livre.share.PdfExporter
import fr.voyage.livre.share.PdfQuality
import fr.voyage.livre.share.ShareHelper
import fr.voyage.livre.share.ShareTarget
import fr.voyage.livre.CrashReporter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    /** Liste des histoires. */
    data object Library : Screen
    /** Une histoire : ses photos et les étapes. */
    data object Story : Screen
    data object BookInfo : Screen
    data object Picker : Screen
    data class Editor(val pageId: String) : Screen
    data class Viewer(val startIndex: Int = 0) : Screen
    data object Settings : Screen
    data object Faces : Screen
}

data class UiState(
    val books: List<Book> = emptyList(),
    /** L'histoire ouverte (vide tant qu'aucune ne l'est). */
    val book: Book = Book(),
    val people: List<Participant> = emptyList(),
    val loaded: Boolean = false,
    val screen: Screen = Screen.Library,
    val galleryPhotos: List<GalleryPhoto> = emptyList(),
    val galleryLoading: Boolean = false,
    val busyMessage: String? = null,
    val message: String? = null,
    val apiKeySet: Boolean = false,
    val captionSource: CaptionSource = CaptionSource.WIKIPEDIA,
    /** Photos cochées pour une suppression groupée. */
    val selectedPages: Set<String> = emptySet(),
    /** Dernière photo ouverte : la liste y revient au retour. */
    val lastPageId: String? = null,
    /** Groupes de visages inconnus restant à nommer. */
    val faceClusters: List<FaceCluster> = emptyList(),
    /** Nombre de photos où des proches ont été cochés automatiquement. */
    val facesTicked: Int = 0,
    /** Rapport d'un plantage précédent, à montrer une fois. */
    val crashReport: String? = null,
) {
    val travelers: List<Participant> get() = book.travelers(people)
}

class BookViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Toute erreur imprévue dans une tâche de fond est notée dans le rapport et affichée,
     * au lieu de fermer l'application.
     */
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        runCatching { CrashReporter.record(getApplication(), e, "Erreur évitée") }
        _state.update { it.copy(busyMessage = null, message = "Erreur : ${e.message ?: e.javaClass.simpleName}", crashReport = CrashReporter.lastReport(getApplication())) }
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(errorHandler, block = block)

    private val repo = LibraryRepository(app)
    private val settings = Settings(app)
    private val annotator = Annotator(app)
    private val faceRepo = FaceRepository(app)
    private var faceIndex = FaceIndex()

    private val _state = MutableStateFlow(
        UiState(apiKeySet = settings.apiKey.isNotBlank(), captionSource = settings.captionSource)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var annotateJob: Job? = null

    init {
        launchSafe {
            val books = repo.loadBooks()
            val people = repo.loadPeople()
            faceIndex = faceRepo.load()
            _state.update {
                it.copy(books = books, people = people, loaded = true, crashReport = CrashReporter.lastReport(getApplication()))
            }
        }
    }

    // ---------- Navigation ----------

    /** Écran à retrouver en quittant les réglages. */
    private var settingsReturn: Screen = Screen.Library

    fun open(screen: Screen) = _state.update {
        if (screen == Screen.Settings && it.screen != Screen.Settings) settingsReturn = it.screen
        it.copy(screen = screen)
    }

    fun back() = _state.update {
        val target = when (it.screen) {
            Screen.Library, Screen.Story -> Screen.Library
            Screen.Settings -> settingsReturn
            else -> Screen.Story
        }
        it.copy(screen = target, selectedPages = if (target == Screen.Story) it.selectedPages else emptySet())
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun dismissCrashReport() {
        CrashReporter.clear(getApplication())
        _state.update { it.copy(crashReport = null) }
    }
    private fun say(text: String) = _state.update { it.copy(message = text) }

    private fun editBook(transform: (Book) -> Book) {
        _state.update { s ->
            val updated = transform(s.book).copy(updatedAt = System.currentTimeMillis())
            s.copy(book = updated, books = s.books.map { if (it.id == updated.id) updated else it })
        }
        val snapshot = _state.value.book
        launchSafe { repo.saveBook(snapshot) }
    }

    private fun editPage(id: String, transform: (BookPage) -> BookPage) = editBook { b ->
        b.copy(pages = b.pages.map { if (it.id == id) transform(it) else it })
    }

    // ---------- Histoires ----------

    fun openBook(id: String) {
        val book = _state.value.books.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                book = book, screen = Screen.Story, selectedPages = emptySet(), lastPageId = null,
                faceClusters = emptyList(), facesTicked = 0, galleryPhotos = emptyList(),
            )
        }
    }

    /** Nouvelle histoire : livre vide, aucune photo sélectionnée. */
    fun createBook(title: String, destination: String, travelerIds: List<String>) {
        val book = Book(
            title = title.trim().ifBlank { "Nouvelle histoire" },
            destination = destination.trim(),
            travelerIds = travelerIds,
        )
        _state.update { it.copy(books = listOf(book) + it.books) }
        launchSafe { repo.saveBook(book) }
        openBook(book.id)
        open(Screen.Picker)
    }

    fun duplicateBook(id: String) {
        val book = _state.value.books.firstOrNull { it.id == id } ?: return
        launchSafe {
            _state.update { it.copy(busyMessage = "Copie de l'histoire…") }
            val copy = repo.duplicate(book, "${book.title} (copie)")
            _state.update { it.copy(books = listOf(copy) + it.books, busyMessage = null) }
            say("Copie créée : « ${copy.title} »")
        }
    }

    fun deleteBook(id: String) {
        val book = _state.value.books.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(books = it.books.filterNot { b -> b.id == id }) }
        forgetFaces(book.pages.map { it.id }.toSet())
        launchSafe { repo.deleteBook(book) }
        say("Histoire supprimée")
    }

    fun saveBookInfo(title: String, destination: String, travelerIds: List<String>, radius: String) {
        val meters = radius.trim().toIntOrNull()?.takeIf { it in 0..20_000 }
            ?: run { say("Distance attendue en mètres (ex. 300)"); return }
        editBook {
            it.copy(
                title = title.trim().ifBlank { it.title }, destination = destination.trim(),
                travelerIds = travelerIds, groupRadiusMeters = meters,
            )
        }
        say("Enregistré")
        open(Screen.Story)
    }

    // ---------- Galerie ----------

    fun loadGallery() {
        _state.update { it.copy(galleryLoading = true) }
        launchSafe {
            val photos = runCatching { Gallery.allPhotos(getApplication()) }
                .getOrElse { say("Impossible de lire la galerie : ${it.message}"); emptyList() }
            _state.update { it.copy(galleryPhotos = photos, galleryLoading = false) }
        }
    }

    fun addPhotos(selected: List<GalleryPhoto>) {
        val existing = _state.value.book.pages.map { it.sourceUri }.toSet()
        val toImport = selected.filter { it.uri.toString() !in existing }.sortedBy { it.takenAt }
        open(Screen.Story)
        if (toImport.isEmpty()) return
        launchSafe {
            var noGps = 0
            toImport.forEachIndexed { i, photo ->
                _state.update { it.copy(busyMessage = "Import des photos… ${i + 1}/${toImport.size}") }
                runCatching { PhotoImporter.import(getApplication(), photo.uri, photo.takenAt, repo.photosDir) }
                    .onSuccess { page ->
                        if (!page.hasGps) noGps++
                        editBook { b -> b.copy(pages = (b.pages + page).sortedBy { it.takenAt ?: Long.MAX_VALUE }) }
                    }
            }
            _state.update { it.copy(busyMessage = null) }
            if (noGps > 0) say("$noGps photo(s) sans GPS : indiquez leur lieu ou leur nom (icône 📍 barrée).")
        }
    }

    // ---------- Légendes ----------

    fun setCaptionSource(source: CaptionSource) {
        settings.captionSource = source
        _state.update { it.copy(captionSource = source) }
    }

    fun annotateAll(source: CaptionSource, onlyMissing: Boolean) {
        if (annotateJob?.isActive == true) return
        setCaptionSource(source)
        if (!onlyMissing) {
            // On repart de zéro pour que les photos voisines ne se répètent pas.
            editBook { b -> b.copy(pages = b.pages.map { it.copy(status = AnnotationStatus.TODO) }) }
        }
        val pages = _state.value.book.pages.filter { !onlyMissing || it.status != AnnotationStatus.DONE }
        annotate(pages.map { it.id }, source)
    }

    fun annotateOne(pageId: String) = annotate(listOf(pageId), _state.value.captionSource)

    private fun annotate(ids: List<String>, source: CaptionSource) {
        if (ids.isEmpty()) { say("Toutes les photos ont déjà une légende."); return }
        if (annotateJob?.isActive == true) return
        if (source == CaptionSource.CLAUDE && settings.apiKey.isBlank()) {
            say("Pour Claude, enregistrez d'abord votre clé API dans les réglages ⚙️.")
            return
        }
        annotateJob = launchSafe {
            val captioner = if (source == CaptionSource.CLAUDE) {
                runCatching { ClaudeCaptioner(settings.apiKey) }
                    .onFailure { e ->
                        CrashReporter.record(getApplication(), e, "Démarrage de Claude")
                        say("Claude indisponible (${e.javaClass.simpleName}) : textes repris de Wikipédia.")
                    }
                    .getOrNull()
            } else null
            var missingPlace = 0
            try {
                ids.forEachIndexed { i, id ->
                    val page = _state.value.book.pages.firstOrNull { it.id == id } ?: return@forEachIndexed
                    if (page.needsPlace) {
                        missingPlace++
                        editPage(id) { it.copy(status = AnnotationStatus.ERROR, error = "Pas de position GPS : indiquez le lieu ou le nom de la photo.") }
                        return@forEachIndexed
                    }
                    _state.update { it.copy(busyMessage = "Rédaction des légendes… ${i + 1}/${ids.size}") }
                    editPage(id) { it.copy(status = AnnotationStatus.RUNNING, error = null) }
                    runCatching { annotator.annotate(page, _state.value.book, captioner) }
                        .onSuccess { updated ->
                            // Garde les personnes cochées et le lieu saisi pendant l'annotation.
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
                say(
                    if (missingPlace > 0) "Légendes prêtes. $missingPlace photo(s) sans GPS attendent leur lieu."
                    else "Légendes prêtes ! Relisez-les."
                )
            } finally {
                runCatching { captioner?.close() }
                _state.update { it.copy(busyMessage = null) }
            }
        }
    }

    // ---------- Édition ----------

    fun rememberPage(id: String) = _state.update { it.copy(lastPageId = id) }

    /** Lieu saisi à la main : il devient la base de la légende (photos sans GPS). */
    fun updatePlace(id: String, text: String) = editPage(id) {
        it.copy(place = text, placeByUser = text.isNotBlank())
    }

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
        p.copy(participantIds = (p.participantIds + _state.value.book.travelerIds).distinct())
    }

    fun deletePage(id: String) {
        deletePages(setOf(id))
        open(Screen.Story)
    }

    // ---------- Sélection multiple ----------

    fun toggleSelected(id: String) = _state.update {
        it.copy(selectedPages = if (id in it.selectedPages) it.selectedPages - id else it.selectedPages + id)
    }

    fun selectAll() = _state.update { it.copy(selectedPages = it.book.pages.map { p -> p.id }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selectedPages = emptySet()) }

    fun deleteSelected() {
        val ids = _state.value.selectedPages
        deletePages(ids)
        clearSelection()
        say("${ids.size} photo(s) retirée(s) du livre")
    }

    private fun deletePages(ids: Set<String>) {
        val pages = _state.value.book.pages.filter { it.id in ids }
        if (pages.isEmpty()) return
        editBook { b -> b.copy(pages = b.pages.filterNot { it.id in ids }) }
        pages.forEach { File(it.photoPath).delete() }
        forgetFaces(ids)
    }

    fun movePage(id: String, delta: Int) = editBook { b ->
        val list = b.pages.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@editBook b
        list.add(j, list.removeAt(i))
        b.copy(pages = list)
    }

    // ---------- Proches et réglages ----------

    private fun editPeople(transform: (List<Participant>) -> List<Participant>) {
        _state.update { it.copy(people = transform(it.people)) }
        val snapshot = _state.value.people
        launchSafe { repo.savePeople(snapshot) }
    }

    fun addPerson(name: String, description: String): Participant? {
        if (name.isBlank()) return null
        val person = Participant(name = name.trim(), description = description.trim())
        editPeople { it + person }
        return person
    }

    fun removePerson(id: String) {
        editPeople { people -> people.filterNot { it.id == id } }
        if (_state.value.book.pages.isNotEmpty() || id in _state.value.book.travelerIds) {
            editBook { b ->
                b.copy(travelerIds = b.travelerIds - id, pages = b.pages.map { p -> p.copy(participantIds = p.participantIds - id) })
            }
        }
        editFaces { f -> if (f.personId == id) f.copy(personId = null, auto = false) else f }
    }

    fun saveApiKey(key: String) {
        settings.apiKey = key
        _state.update { it.copy(apiKeySet = key.isNotBlank()) }
        say(if (key.isBlank()) "Clé API effacée" else "Clé API enregistrée")
    }

    // ---------- Visages ----------

    private fun saveFaces() {
        val snapshot = faceIndex
        launchSafe { faceRepo.save(snapshot) }
    }

    private fun currentPageIds() = _state.value.book.pages.map { it.id }.toSet()

    private fun currentClusters() = FaceMatcher.clusters(faceIndex.faces.filter { it.pageId in currentPageIds() })

    private fun editFaces(transform: (FaceRecord) -> FaceRecord) {
        faceIndex = faceIndex.copy(faces = faceIndex.faces.map(transform))
        saveFaces()
        _state.update { it.copy(faceClusters = currentClusters()) }
    }

    /** Les visages nommés restent des modèles utiles ; les autres disparaissent avec leurs photos. */
    private fun forgetFaces(pageIds: Set<String>) {
        val gone = faceIndex.faces.filter { it.pageId in pageIds && (it.personId == null || it.auto) }
        if (gone.isEmpty()) return
        gone.forEach { File(it.thumbPath).delete() }
        faceIndex = faceIndex.copy(faces = faceIndex.faces - gone.toSet())
        saveFaces()
    }

    /**
     * Repère les visages des photos pas encore analysées, reconnaît les proches déjà nommés,
     * coche les participants, puis regroupe les visages inconnus pour « Qui est-ce ? ».
     */
    fun recognizeFaces() {
        if (annotateJob?.isActive == true) return
        annotateJob = launchSafe {
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
                _state.update { it.copy(faceClusters = currentClusters(), facesTicked = ticked) }
                open(Screen.Faces)
            } finally {
                _state.update { it.copy(busyMessage = null) }
            }
        }
    }

    /** Reconnaît les visages inconnus de l'histoire ouverte ; renvoie le nombre de photos cochées. */
    private fun matchKnownFaces(): Int {
        val before = faceIndex.faces.associateBy { it.id }
        faceIndex = faceIndex.copy(faces = FaceMatcher.autoAssign(faceIndex.faces, currentPageIds()))
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
            it.copy(faceClusters = currentClusters(), facesTicked = it.facesTicked + named.map { n -> n.pageId }.distinct().size + more)
        }
    }

    fun nameClusterNewPerson(cluster: FaceCluster, name: String) {
        val person = addPerson(name, "") ?: return
        nameCluster(cluster, person.id)
    }

    /** « Je ne sais pas » / « Pas un visage » : on ne le proposera plus. */
    fun ignoreCluster(cluster: FaceCluster) {
        val ids = cluster.faceIds.toSet()
        editFaces { f -> if (f.id in ids) f.copy(ignored = true) else f }
    }

    // ---------- Partage ----------

    fun share(target: ShareTarget, pageIds: List<String>? = null) {
        val s = _state.value
        val pages = pageIds?.let { ids -> s.book.pages.filter { it.id in ids } } ?: s.book.pages
        if (pages.isEmpty()) { say("Aucune photo à partager."); return }
        launchSafe {
            _state.update { it.copy(busyMessage = "Préparation des images…") }
            runCatching { ShareHelper.share(getApplication(), s.book, pages, s.people, target) }
                .onFailure { say("Partage impossible : ${it.message}") }
            _state.update { it.copy(busyMessage = null) }
        }
    }

    /** Fabrique le livre en PDF imprimable (A4) puis propose de l'enregistrer ou de l'envoyer. */
    fun exportPdf(quality: PdfQuality) {
        val s = _state.value
        if (s.book.pages.isEmpty()) { say("Aucune photo dans le livre."); return }
        launchSafe {
            _state.update { it.copy(busyMessage = "Fabrication du PDF (photos en haute définition)…") }
            runCatching {
                val file = PdfExporter.export(getApplication(), s.book, s.book.pages, s.people, quality)
                PdfExporter.share(getApplication(), file, s.book)
            }.onFailure { say("PDF impossible : ${it.message}") }
            _state.update { it.copy(busyMessage = null) }
        }
    }
}
