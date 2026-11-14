package fr.voyage.livre.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.voyage.livre.annotate.GalleryPhoto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Toute la galerie, regroupée par jour ; la sélection repart toujours de zéro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(state: UiState, vm: BookViewModel) {
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val already = remember(state.book.pages) { state.book.pages.map { it.sourceUri }.toSet() }
    val dayFormat = remember { SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE) }
    val days = remember(state.galleryPhotos) {
        state.galleryPhotos.groupBy { dayFormat.format(Date(it.takenAt)).replaceFirstChar(Char::uppercase) }.toList()
    }

    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES)
        else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionAsked = true
        vm.loadGallery()
    }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Choisir les photos" else "${selected.size} photo(s) choisie(s)") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Tout décocher") }
                },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = {
                    vm.addPhotos(state.galleryPhotos.filter { it.uri.toString() in selected })
                }) { Text("Ajouter ${selected.size} photo(s) au livre") }
            }
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.galleryLoading || !permissionAsked ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.galleryPhotos.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Aucune photo trouvée. Vérifiez que l'application a bien accès à vos photos.", textAlign = TextAlign.Center)
                    Button(onClick = { launcher.launch(permissions) }) { Text("Autoriser l'accès aux photos") }
                }

                else -> LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = Modifier.fillMaxSize()) {
                    days.forEach { (day, photos) ->
                        item(key = "jour-$day", span = { GridItemSpan(maxLineSpan) }) {
                            DayHeader(day, photos, selected, already) { keys, select ->
                                selected = if (select) selected + keys else selected - keys
                            }
                        }
                        items(photos, key = { it.uri.toString() }) { photo ->
                            val key = photo.uri.toString()
                            PhotoCell(photo, isIn = key in already, isSelected = key in selected) {
                                selected = if (key in selected) selected - key else selected + key
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: String,
    photos: List<GalleryPhoto>,
    selected: Set<String>,
    already: Set<String>,
    onSelect: (Set<String>, Boolean) -> Unit,
) {
    val keys = photos.map { it.uri.toString() }.filter { it !in already }.toSet()
    val allSelected = keys.isNotEmpty() && keys.all { it in selected }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (keys.isNotEmpty()) {
            TextButton(onClick = { onSelect(keys, !allSelected) }) { Text(if (allSelected) "Décocher le jour" else "Tout le jour") }
        }
    }
}

@Composable
private fun PhotoCell(photo: GalleryPhoto, isIn: Boolean, isSelected: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .clickable(enabled = !isIn, onClick = onToggle),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = if (isIn) 0.35f else 1f,
        )
        if (isSelected || isIn) {
            Icon(
                Icons.Default.CheckCircle, if (isIn) "Déjà dans le livre" else "Choisie",
                tint = if (isIn) Color.Gray else MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
            )
        }
    }
}
