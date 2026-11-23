package fr.voyage.livre.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Enregistre le livre dans un fichier JSON privé de l'application. */
class BookRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val file = File(context.filesDir, "livre.json")
    private val mutex = Mutex()

    val photosDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    suspend fun load(): Book = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock Book()
            runCatching { json.decodeFromString(Book.serializer(), file.readText()) }
                .getOrElse { Book() }
                // Une annotation interrompue (application fermée) est à refaire.
                .let { book ->
                    book.copy(pages = book.pages.map {
                        if (it.status == AnnotationStatus.RUNNING) it.copy(status = AnnotationStatus.TODO) else it
                    })
                }
        }
    }

    suspend fun save(book: Book) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tmp = File(file.parentFile, "livre.json.tmp")
            tmp.writeText(json.encodeToString(Book.serializer(), book))
            tmp.renameTo(file)
        }
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
