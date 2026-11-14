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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(state: UiState, vm: BookViewModel) {
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val already = remember(state.book.pages) { state.book.pages.map { it.sourceUri }.toSet() }

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
                title = { Text("Photos du ${state.book.startDate} au ${state.book.endDate}") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
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
                    Text(
                        "Aucune photo trouvée entre ces dates.\nVérifiez les dates du voyage dans les réglages, " +
                            "et que l'application a bien accès à vos photos.",
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { launcher.launch(permissions) }) { Text("Autoriser l'accès aux photos") }
                    Button(onClick = { vm.open(Screen.Settings) }) { Text("Modifier les dates") }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.galleryPhotos, key = { it.uri.toString() }) { photo ->
                        val key = photo.uri.toString()
                        val isIn = key in already
                        val isSelected = key in selected
                        Box(
                            Modifier
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .then(
                                    if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                                    else Modifier
                                )
                                .clickable(enabled = !isIn) {
                                    selected = if (isSelected) selected - key else selected + key
                                },
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
                                    Icons.Default.CheckCircle, null,
                                    tint = if (isIn) Color.Gray else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
