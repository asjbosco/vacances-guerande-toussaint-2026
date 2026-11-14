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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.voyage.livre.annotate.Annotator
import fr.voyage.livre.data.AnnotationStatus
import fr.voyage.livre.data.CaptionFormat
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

    fun goTo(i: Int) {
        val target = book.pages.getOrNull(i) ?: return
        vm.rememberPage(target.id)
        vm.open(Screen.Editor(target.id))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo ${index + 1} / ${book.pages.size}") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour à la liste") }
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
        bottomBar = {
            // Passer directement à la photo précédente ou suivante.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { goTo(index - 1) }, enabled = index > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, null)
                    Text("Précédente")
                }
                FilledTonalButton(onClick = { goTo(index + 1) }, enabled = index < book.pages.size - 1, modifier = Modifier.weight(1f)) {
                    Text("Suivante")
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, null)
                }
            }
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = File(page.photoPath),
                contentDescription = page.place,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(12.dp)),
            )

            // Aperçu de la légende telle qu'elle apparaîtra dans le livre.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(page.place.ifBlank { "Lieu" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (page.summary.isNotBlank()) Text(page.summary, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    val footer = CaptionFormat.footer(page, state.people)
                    if (footer.isNotBlank()) Text(footer, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
            }

            if (!page.hasGps) {
                Text(
                    "📍 Cette photo n'a pas de position GPS. Indiquez ci-dessous le lieu ou le nom du cliché " +
                        "(ex. « Remparts de Guérande »), puis appuyez sur « Rédiger la légende ».",
                    color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium,
                )
            }
            page.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = page.place,
                onValueChange = { vm.updatePlace(page.id, it) },
                label = { Text(if (page.hasGps) "Lieu (en gras)" else "Lieu ou nom du cliché (en gras)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = page.summary,
                onValueChange = { vm.updateSummary(page.id, it) },
                label = { Text("Texte – 2 lignes, style télégraphique") },
                supportingText = {
                    val n = page.summary.length
                    Text(
                        "$n / ${Annotator.MAX_SUMMARY} caractères",
                        color = if (n > Annotator.MAX_SUMMARY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3,
            )

            BusyBanner(state.busyMessage)
            FilledTonalButton(onClick = { vm.annotateOne(page.id) }, enabled = state.busyMessage == null) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(if (page.status == AnnotationStatus.DONE) "Réécrire la légende" else "Rédiger la légende")
            }
            val sourceText = if (state.captionSource == CaptionSource.CLAUDE) "Claude (Anthropic)" else "sources libres (Wikipédia)"
            val info = listOfNotNull(
                page.takenAt?.let { Annotator.formatDate(it) },
                if (page.hasGps) "position GPS connue" else null,
                page.wikiTitle.takeIf { it.isNotBlank() }?.let { "Wikipédia : $it" },
                "rédaction : $sourceText",
            ).joinToString(" · ")
            Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

            Row {
                Text("Personnes sur la photo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (book.travelerIds.isNotEmpty()) {
                    TextButton(onClick = { vm.setAllParticipants(page.id) }) { Text("Tous les voyageurs") }
                }
            }
            if (state.people.isEmpty()) {
                TextButton(onClick = { vm.open(Screen.Settings) }) { Text("Ajouter vos proches dans les réglages") }
            } else {
                // Voyageurs d'abord, puis les autres proches.
                val ordered = state.people.sortedBy { if (it.id in book.travelerIds) 0 else 1 }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { p ->
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
