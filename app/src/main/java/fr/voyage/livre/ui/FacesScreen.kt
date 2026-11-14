package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/** « Qui est-ce ? » : un groupe de visages inconnus à la fois. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FacesScreen(state: UiState, vm: BookViewModel) {
    val cluster = state.faceClusters.firstOrNull()
    var newName by rememberSaveable(cluster?.faceIds?.firstOrNull()) { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (cluster != null) "Qui est-ce ?" else "Visages") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.facesTicked > 0) {
                Text(
                    "✅ Participants cochés automatiquement sur ${state.facesTicked} photo(s). " +
                        "Vérifiez-les en ouvrant chaque photo : décochez simplement les erreurs.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (cluster == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Tous les visages sont nommés ou mis de côté.\n" +
                            "Les prochaines photos ajoutées seront reconnues automatiquement.",
                        textAlign = TextAlign.Center,
                    )
                }
                Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text("Retour au livre") }
                return@Column
            }

            Text(
                "Ce visage apparaît sur ${cluster.faceIds.size} photo(s). " +
                    "Encore ${state.faceClusters.size} personne(s) à nommer.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cluster.thumbPaths.take(8).forEach { path ->
                    AsyncImage(
                        model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(CircleShape),
                    )
                }
            }

            HorizontalDivider()
            Text("C'est…", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.people.forEach { p ->
                    AssistChip(onClick = { vm.nameCluster(cluster, p.id) }, label = { Text(p.name) })
                }
            }

            Text("Quelqu'un d'autre", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    newName, { newName = it }, label = { Text("Prénom ou nom") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.nameClusterNewPerson(cluster, newName) }, enabled = newName.isNotBlank()) {
                    Text("Ajouter")
                }
            }
            Text(
                "La personne rejoint vos proches : elle sera reconnue sur les prochaines photos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider()
            OutlinedButton(onClick = { vm.ignoreCluster(cluster) }, modifier = Modifier.fillMaxWidth()) {
                Text("Je ne sais pas / pas un visage — passer")
            }
        }
    }
}
