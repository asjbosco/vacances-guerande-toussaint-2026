package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.voyage.livre.data.Book
import java.io.File

/** Écran d'accueil : toutes vos histoires, gardées en mémoire. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: UiState, vm: BookViewModel) {
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes histoires") },
                actions = {
                    IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Réglages") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nouvelle histoire") },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BusyBanner(state.busyMessage)
            when {
                !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.books.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucune histoire pour l'instant.\nAppuyez sur « Nouvelle histoire » pour commencer un livre.",
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            onOpen = { vm.openBook(book.id) },
                            onDuplicate = { vm.duplicateBook(book.id) },
                            onDelete = { toDelete = book },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NewBookDialog(state, onDismiss = { creating = false }) { title, region, travelers ->
            creating = false
            vm.createBook(title, region, travelers)
        }
    }
    toDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${book.title} » ?") },
            text = { Text("Le livre et ses légendes sont effacés. Les photos restent dans la galerie du téléphone.") },
            confirmButton = { TextButton(onClick = { toDelete = null; vm.deleteBook(book.id) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun BookCard(book: Book, onOpen: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val cover = book.pages.firstOrNull()
            if (cover != null) {
                AsyncImage(
                    model = File(cover.photoPath), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val info = listOf(
                    "${book.pages.size} photo${if (book.pages.size > 1) "s" else ""}",
                    book.dateRange,
                ).filter { it.isNotBlank() }.joinToString(" · ")
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Dupliquer") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewBookDialog(state: UiState, onDismiss: () -> Unit, onCreate: (String, String, List<String>) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var travelers by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle histoire") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Titre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    region, { region = it }, label = { Text("Région (facultatif)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.people.isNotEmpty()) {
                    Text("Qui était là ?", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.people.forEach { p ->
                            FilterChip(
                                selected = p.id in travelers,
                                onClick = { travelers = if (p.id in travelers) travelers - p.id else travelers + p.id },
                                label = { Text(p.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, region, state.people.map { it.id }.filter { it in travelers }) }) {
                Text("Créer et choisir les photos")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
