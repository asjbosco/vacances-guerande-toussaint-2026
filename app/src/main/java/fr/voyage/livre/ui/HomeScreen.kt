package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.BookPage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, vm: BookViewModel) {
    val book = state.book
    var showShare by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { vm.open(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Réglages")
                    }
                },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            BusyBanner(state.busyMessage)

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.open(Screen.Picker) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("1. Choisir les photos du voyage")
                }
                FilledTonalButton(
                    onClick = { vm.annotateAll() },
                    enabled = book.pages.isNotEmpty() && state.busyMessage == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("2. Rédiger les légendes")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.open(Screen.Viewer(0)) },
                        enabled = book.pages.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                        Spacer(Modifier.width(6.dp))
                        Text("3. Feuilleter")
                    }
                    OutlinedButton(
                        onClick = { showShare = true },
                        enabled = book.pages.isNotEmpty() && state.busyMessage == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(6.dp))
                        Text("4. Partager")
                    }
                }
                if (book.participants.isEmpty()) {
                    Text(
                        "Astuce : ajoutez les participants du voyage dans les réglages ⚙️",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            if (book.pages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Votre livre est vide.\nCommencez par choisir les plus belles photos du voyage.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(book.pages, key = { _, p -> p.id }) { index, page ->
                        PageRow(index, page, state) { vm.open(Screen.Editor(page.id)) }
                    }
                }
            }
        }
    }

    if (showShare) {
        ShareDialog(onDismiss = { showShare = false }) { target ->
            showShare = false
            vm.share(target)
        }
    }
}

@Composable
private fun PageRow(index: Int, page: BookPage, state: UiState, onClick: () -> Unit) {
    val names = state.book.participants.filter { it.id in page.participantIds }.joinToString(", ") { it.name }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = File(page.photoPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${index + 1}. " + page.place.ifBlank { "Lieu à trouver" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    page.summary.ifBlank { statusText(page) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (names.isNotEmpty()) {
                    Text("👥 $names", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                page.status == AnnotationStatus.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                page.status == AnnotationStatus.ERROR -> Icon(Icons.Default.ErrorOutline, "Erreur", tint = MaterialTheme.colorScheme.error)
                !page.hasGps -> Icon(Icons.Default.LocationOff, "Pas de GPS", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun statusText(page: BookPage) = when (page.status) {
    AnnotationStatus.TODO -> "Légende pas encore rédigée"
    AnnotationStatus.RUNNING -> "Rédaction en cours…"
    AnnotationStatus.ERROR -> page.error ?: "Erreur"
    AnnotationStatus.DONE -> ""
}

@Composable
fun BusyBanner(message: String?) {
    if (message == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
fun MessageHost(state: UiState, vm: BookViewModel) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            host.showSnackbar(it)
            vm.clearMessage()
        }
    }
    SnackbarHost(host)
}
