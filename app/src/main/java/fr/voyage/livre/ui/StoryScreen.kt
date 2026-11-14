package fr.voyage.livre.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.BookPage
import fr.voyage.livre.data.CaptionFormat
import fr.voyage.livre.data.CaptionSource
import java.io.File

/** Une histoire : les étapes et la liste de ses photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(state: UiState, vm: BookViewModel) {
    val book = state.book
    val selecting = state.selectedPages.isNotEmpty()
    var showShare by remember { mutableStateOf(false) }
    var askSource by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Au retour d'une photo, la liste se replace sur elle.
    val startIndex = state.lastPageId?.let { id -> book.pages.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${state.selectedPages.size} sélectionnée(s)") },
                    navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Annuler la sélection") } },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Default.SelectAll, "Tout sélectionner") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Retirer du livre") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Mes histoires") } },
                    actions = {
                        IconButton(onClick = { vm.open(Screen.BookInfo) }) { Icon(Icons.Default.EditNote, "Infos de l'histoire") }
                    },
                )
            }
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BusyBanner(state.busyMessage)
            val idle = state.busyMessage == null
            val hasPages = book.pages.isNotEmpty()

            if (!selecting) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.open(Screen.Picker) }, modifier = Modifier.fillMaxWidth(), enabled = idle) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("1. Choisir les photos")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { askSource = true }, enabled = hasPages && idle, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(6.dp))
                            Text("2. Légendes")
                        }
                        FilledTonalButton(onClick = { vm.recognizeFaces() }, enabled = hasPages && idle, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Face, null)
                            Spacer(Modifier.width(6.dp))
                            Text("3. Visages")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.open(Screen.Viewer(0)) }, enabled = hasPages, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                            Spacer(Modifier.width(6.dp))
                            Text("4. Feuilleter")
                        }
                        OutlinedButton(onClick = { showShare = true }, enabled = hasPages && idle, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null)
                            Spacer(Modifier.width(6.dp))
                            Text("5. Partager")
                        }
                    }
                    val missing = book.pages.count { it.needsPlace }
                    if (missing > 0) {
                        Text(
                            "📍 $missing photo(s) sans GPS : ouvrez-les pour indiquer le lieu ou le nom du cliché.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (hasPages) {
                        Text(
                            "Appui long sur une photo pour en sélectionner plusieurs.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            if (!hasPages) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Ce livre est vide.\nCommencez par choisir les plus belles photos.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(book.pages, key = { _, p -> p.id }) { index, page ->
                        PageRow(
                            index = index, page = page, state = state,
                            selected = page.id in state.selectedPages, selecting = selecting,
                            highlighted = page.id == state.lastPageId,
                            onClick = {
                                if (selecting) vm.toggleSelected(page.id)
                                else { vm.rememberPage(page.id); vm.open(Screen.Editor(page.id)) }
                            },
                            onLongClick = { vm.toggleSelected(page.id) },
                        )
                    }
                }
            }
        }
    }

    if (showShare) {
        ShareDialog(
            onDismiss = { showShare = false },
            onPdf = { showShare = false; vm.exportPdf() },
        ) { target ->
            showShare = false
            vm.share(target)
        }
    }
    if (askSource) {
        CaptionSourceDialog(state, onDismiss = { askSource = false }) { source, all ->
            askSource = false
            vm.annotateAll(source, onlyMissing = !all)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Retirer ${state.selectedPages.size} photo(s) du livre ?") },
            text = { Text("Les photos restent dans la galerie du téléphone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

/** Avant de rédiger : d'où doivent venir les légendes ? */
@Composable
private fun CaptionSourceDialog(state: UiState, onDismiss: () -> Unit, onStart: (CaptionSource, Boolean) -> Unit) {
    var source by remember { mutableStateOf(state.captionSource) }
    val done = state.book.pages.count { it.status == AnnotationStatus.DONE }
    var all by remember { mutableStateOf(done == state.book.pages.size) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("D'où viennent les légendes ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Dans les deux cas, la légende part de la position GPS de la photo " +
                        "(ou du lieu que vous avez indiqué).",
                    style = MaterialTheme.typography.bodySmall,
                )
                SourceOption(
                    selected = source == CaptionSource.WIKIPEDIA,
                    title = "Sources libres (Wikipédia)",
                    detail = "Gratuit. Le texte est repris de l'article Wikipédia le plus proche, raccourci.",
                ) { source = CaptionSource.WIKIPEDIA }
                SourceOption(
                    selected = source == CaptionSource.CLAUDE,
                    title = "Claude (Anthropic)",
                    detail = "La photo et les articles Wikipédia du lieu sont envoyés à Claude, qui rédige un texte " +
                        "court adapté à l'image. Clé API nécessaire, ~1 centime par photo." +
                        if (!state.apiKeySet) "\n⚠️ Aucune clé enregistrée (réglages ⚙️)." else "",
                ) { source = CaptionSource.CLAUDE }
                if (done > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = all, onCheckedChange = { all = it })
                        Text("Réécrire aussi les $done légende(s) existante(s)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(source, all) }) { Text("Rédiger") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun SourceOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageRow(
    index: Int,
    page: BookPage,
    state: UiState,
    selected: Boolean,
    selecting: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val footer = CaptionFormat.footer(page, state.people)
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected || highlighted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() })
            AsyncImage(
                model = File(page.photoPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${index + 1}. " + page.place.ifBlank { if (page.needsPlace) "Lieu à indiquer" else "Lieu à trouver" },
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    page.summary.ifBlank { statusText(page) },
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (footer.isNotBlank()) {
                    Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                page.status == AnnotationStatus.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                page.status == AnnotationStatus.ERROR -> Icon(Icons.Default.ErrorOutline, "Erreur", tint = MaterialTheme.colorScheme.error)
                page.needsPlace -> Icon(Icons.Default.LocationOff, "Pas de GPS", tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private fun statusText(page: BookPage) = when (page.status) {
    AnnotationStatus.TODO -> if (page.needsPlace) "Sans GPS : indiquez le lieu ou le nom du cliché" else "Légende pas encore rédigée"
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
