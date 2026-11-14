package fr.voyage.livre.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Bibliothèque des histoires : un fichier JSON par histoire, plus la liste des proches.
 * Les photos de chaque histoire sont des copies privées, jamais les originaux de la galerie.
 */
class LibraryRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }
    private val mutex = Mutex()
    private val booksDir = File(context.filesDir, "histoires").apply { mkdirs() }
    private val peopleFile = File(context.filesDir, "proches.json")
    val photosDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    private fun bookFile(id: String) = File(booksDir, "$id.json")

    suspend fun loadBooks(): List<Book> = withContext(Dispatchers.IO) {
        mutex.withLock {
            migrateLegacyBook()
            booksDir.listFiles { f -> f.extension == "json" }.orEmpty()
                .mapNotNull { f -> runCatching { json.decodeFromString(Book.serializer(), f.readText()) }.getOrNull() }
                .map { book ->
                    // Une annotation interrompue (application fermée) est à refaire.
                    book.copy(pages = book.pages.map {
                        if (it.status == AnnotationStatus.RUNNING) it.copy(status = AnnotationStatus.TODO) else it
                    })
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun saveBook(book: Book) = withContext(Dispatchers.IO) {
        mutex.withLock { writeAtomically(bookFile(book.id), json.encodeToString(Book.serializer(), book)) }
    }

    /** Supprime l'histoire et ses copies de photos (les originaux de la galerie ne sont pas touchés). */
    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        mutex.withLock {
            book.pages.forEach { File(it.photoPath).delete() }
            bookFile(book.id).delete()
        }
    }

    /** Copie complète : nouvelles pages, nouvelles copies des photos. */
    suspend fun duplicate(book: Book, newTitle: String): Book = withContext(Dispatchers.IO) {
        val pages = book.pages.map { page ->
            val id = UUID.randomUUID().toString()
            val copy = File(photosDir, "$id.jpg")
            runCatching { File(page.photoPath).copyTo(copy, overwrite = true) }
            page.copy(id = id, photoPath = copy.absolutePath, facesScanned = false)
        }
        val now = System.currentTimeMillis()
        val dup = book.copy(id = UUID.randomUUID().toString(), title = newTitle, pages = pages, createdAt = now, updatedAt = now)
        mutex.withLock { writeAtomically(bookFile(dup.id), json.encodeToString(Book.serializer(), dup)) }
        dup
    }

    suspend fun loadPeople(): List<Participant> = withContext(Dispatchers.IO) {
        mutex.withLock {
            migrateLegacyBook()
            if (!peopleFile.exists()) return@withLock DEFAULT_PARTICIPANTS
            runCatching { json.decodeFromString(ListSerializer(Participant.serializer()), peopleFile.readText()) }
                .getOrDefault(DEFAULT_PARTICIPANTS)
        }
    }

    suspend fun savePeople(people: List<Participant>) = withContext(Dispatchers.IO) {
        mutex.withLock { writeAtomically(peopleFile, json.encodeToString(ListSerializer(Participant.serializer()), people)) }
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    // ---------- Reprise du livre unique de la première version ----------

    @Serializable
    private data class LegacyParticipant(val id: String, val name: String, val description: String = "", val onTrip: Boolean = true)

    @Serializable
    private data class LegacyBook(
        val title: String = "Vacances à Guérande – Toussaint 2026",
        val destination: String = "",
        val groupRadiusMeters: Int = 300,
        val participants: List<LegacyParticipant> = emptyList(),
        val pages: List<BookPage> = emptyList(),
    )

    /** L'ancien « livre.json » devient la première histoire ; ses participants deviennent vos proches. */
    private fun migrateLegacyBook() {
        val legacy = File(context.filesDir, "livre.json")
        if (!legacy.exists()) return
        runCatching {
            val old = json.decodeFromString(LegacyBook.serializer(), legacy.readText())
            val people = old.participants.ifEmpty { DEFAULT_PARTICIPANTS.map { LegacyParticipant(it.id, it.name) } }
            if (!peopleFile.exists()) {
                writeAtomically(
                    peopleFile,
                    json.encodeToString(
                        ListSerializer(Participant.serializer()),
                        people.map { Participant(it.id, it.name, it.description) },
                    ),
                )
            }
            if (old.pages.isNotEmpty()) {
                val book = Book(
                    title = old.title, destination = old.destination,
                    travelerIds = people.filter { it.onTrip }.map { it.id },
                    groupRadiusMeters = old.groupRadiusMeters, pages = old.pages,
                )
                writeAtomically(bookFile(book.id), json.encodeToString(Book.serializer(), book))
            }
        }
        legacy.renameTo(File(context.filesDir, "livre.json.migre"))
    }
}

/** Réglages personnels, communs à toutes les histoires. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("cle_api", "") ?: ""
        set(value) = prefs.edit().putString("cle_api", value.trim()).apply()

    /** Dernière source de légendes choisie (proposée par défaut la fois suivante). */
    var captionSource: CaptionSource
        get() = runCatching { CaptionSource.valueOf(prefs.getString("source", "") ?: "") }
            .getOrDefault(CaptionSource.WIKIPEDIA)
        set(value) = prefs.edit().putString("source", value.name).apply()
}
