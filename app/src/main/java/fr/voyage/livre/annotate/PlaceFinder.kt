package fr.voyage.livre.annotate

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Traduit des coordonnées GPS en nom de lieu lisible (« Rue de la Psalette, Guérande »). */
object PlaceFinder {

    suspend fun placeName(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val address: Address? = Geocoder(context, Locale.FRANCE).getFromLocation(lat, lon, 1)?.firstOrNull()
            address?.let { format(it) }
        }.getOrNull()
    }

    private fun format(a: Address): String {
        val near = listOfNotNull(a.featureName, a.thoroughfare)
            .firstOrNull { it.isNotBlank() && it.any(Char::isLetter) }
        val town = a.locality ?: a.subAdminArea
        return listOfNotNull(near, town).distinct().joinToString(", ")
    }
}
