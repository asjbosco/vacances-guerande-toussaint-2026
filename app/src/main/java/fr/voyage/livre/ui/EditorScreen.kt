package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.voyage.livre.annotate.Annotator
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.CaptionSource
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(state: UiState, vm: BookViewModel, pageId: String) {
    val book = state.book
    val index = book.pages.indexOfFirst { it.id == pageId }
    val page = book.pages.getOrNull(index)
    if (page == null) { vm.back(); return }

    var confirmDelete by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo ${index + 1} / ${book.pages.size}") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    IconButton(onClick = { vm.movePage(page.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, "Avancer dans le livre")
                    }
                    IconButton(onClick = { vm.movePage(page.id, 1) }, enabled = index < book.pages.size - 1) {
                        Icon(Icons.Default.ArrowDownward, "Reculer dans le livre")
                    }
                    IconButton(onClick = { showShare = true }) { Icon(Icons.Default.Share, "Partager cette photo") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Retirer du livre") }
                },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = File(page.photoPath),
                contentDescription = page.place,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)),
            )
            val info = listOfNotNull(
                page.takenAt?.let { Annotator.formatDate(it) },
                if (page.hasGps) "📍 position GPS connue" else "pas de position GPS",
                page.wikiTitle.takeIf { it.isNotBlank() }?.let { "Wikipédia : $it" },
                when (page.textSource) {
                    "claude" -> "Texte : Claude"
                    "wikipedia" -> "Texte : Wikipédia"
                    else -> null
                },
            ).joinToString(" · ")
            Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            page.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            BusyBanner(state.busyMessage)
            Text(
                if (page.status == AnnotationStatus.DONE) "Réécrire la légende avec :" else "Rédiger la légende avec :",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vm.annotateOne(page.id, CaptionSource.CLAUDE) },
                    enabled = state.busyMessage == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Claude")
                }
                FilledTonalButton(
                    onClick = { vm.annotateOne(page.id, CaptionSource.WIKIPEDIA) },
                    enabled = state.busyMessage == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Public, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Wikipédia")
                }
            }

            OutlinedTextField(
                value = page.place,
                onValueChange = { vm.updatePlace(page.id, it) },
                label = { Text("Lieu") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = page.summary,
                onValueChange = { vm.updateSummary(page.id, it) },
                label = { Text("Histoire / géographie") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(page.summary)) }) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copier")
                }
                TextButton(
                    onClick = { vm.shortenCaption(page.id) },
                    enabled = state.busyMessage == null && page.summary.isNotBlank(),
                ) {
                    Icon(Icons.Default.Compress, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Raccourcir à 3 lignes")
                }
            }

            Row {
                Text("Participants", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (book.participants.isNotEmpty()) {
                    TextButton(onClick = { vm.setAllParticipants(page.id) }) { Text("Tout le monde") }
                }
            }
            if (book.participants.isEmpty()) {
                TextButton(onClick = { vm.open(Screen.Settings) }) { Text("Ajouter les participants dans les réglages") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    book.participants.forEach { p ->
                        FilterChip(
                            selected = p.id in page.participantIds,
                            onClick = { vm.toggleParticipant(page.id, p.id) },
                            label = { Text(p.name) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Retirer cette photo du livre ?") },
            text = { Text("La photo reste dans la galerie du téléphone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePage(page.id) }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
    if (showShare) {
        ShareDialog(onDismiss = { showShare = false }) { target ->
            showShare = false
            vm.share(target, listOf(page.id))
        }
    }
}
