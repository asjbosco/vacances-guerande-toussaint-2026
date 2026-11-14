package fr.voyage.livre.annotate

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Adresse d'un point GPS : un nom précis (rue, lieu-dit) et la commune. */
data class FoundPlace(val precise: String?, val town: String?) {
    val label: String get() = listOfNotNull(precise, town).distinct().joinToString(", ")
}

/** Traduit des coordonnées GPS en adresse lisible. */
object PlaceFinder {

    suspend fun find(context: Context, lat: Double, lon: Double): FoundPlace? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val a: Address? = Geocoder(context, Locale.FRANCE).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.let {
                val precise = listOfNotNull(it.featureName, it.thoroughfare)
                    .firstOrNull { s -> s.isNotBlank() && s.any(Char::isLetter) }
                FoundPlace(precise, it.locality ?: it.subLocality ?: it.subAdminArea)
            }
        }.getOrNull()
    }
}
