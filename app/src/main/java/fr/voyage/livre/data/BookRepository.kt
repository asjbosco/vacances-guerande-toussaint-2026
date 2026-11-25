package fr.voyage.livre.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Enregistre la bibliothèque (plusieurs livres) dans un fichier JSON privé de l'application. */
class BookRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val libraryFile = File(context.filesDir, "bibliotheque.json")
    private val legacyFile = File(context.filesDir, "livre.json") // ancien format mono-livre
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(Book.serializer())

    val photosDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    /** Charge tous les livres (en migrant l'ancien livre unique si besoin), triés du plus récent. */
    suspend fun loadLibrary(): List<Book> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val raw: List<Book> = when {
                libraryFile.exists() ->
                    runCatching { json.decodeFromString(listSerializer, libraryFile.readText()) }.getOrElse { emptyList() }
                legacyFile.exists() -> {
                    // Migration : l'ancien fichier contenait un seul livre.
                    val book = runCatching { json.decodeFromString(Book.serializer(), legacyFile.readText()) }.getOrNull()
                    val migrated = if (book != null) listOf(book) else emptyList()
                    if (migrated.isNotEmpty()) writeLibrary(migrated)
                    migrated
                }
                else -> emptyList()
            }
            // Une annotation interrompue (application fermée) est à refaire ; tri par date.
            raw.map { book ->
                book.copy(pages = book.pages.map {
                    if (it.status == AnnotationStatus.RUNNING) it.copy(status = AnnotationStatus.TODO) else it
                })
            }.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun saveLibrary(books: List<Book>) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLibrary(books) }
    }

    private fun writeLibrary(books: List<Book>) {
        val tmp = File(libraryFile.parentFile, "bibliotheque.json.tmp")
        tmp.writeText(json.encodeToString(listSerializer, books))
        tmp.renameTo(libraryFile)
    }
}

/** Réglages personnels (clé API), séparés du livre. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("cle_api", "") ?: ""
        set(value) = prefs.edit().putString("cle_api", value.trim()).apply()

    /** Clé Google Cloud Vision (recherche par l'image), facultative. */
    var visionKey: String
        get() = prefs.getString("cle_vision", "") ?: ""
        set(value) = prefs.edit().putString("cle_vision", value.trim()).apply()
}
