package fr.voyage.livre.faces

import android.content.Context
import fr.voyage.livre.data.FaceIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Visages repérés et noms donnés, gardés d'un livre à l'autre :
 * une personne nommée une fois est reconnue dans les voyages suivants.
 */
class FaceRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    private val file = File(context.filesDir, "visages.json")
    private val mutex = Mutex()
    val thumbsDir = File(context.filesDir, "visages")

    suspend fun load(): FaceIndex = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) FaceIndex()
            else runCatching { json.decodeFromString(FaceIndex.serializer(), file.readText()) }.getOrElse { FaceIndex() }
        }
    }

    suspend fun save(index: FaceIndex) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tmp = File(file.parentFile, "visages.json.tmp")
            tmp.writeText(json.encodeToString(FaceIndex.serializer(), index))
            tmp.renameTo(file)
        }
    }
}
