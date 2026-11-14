package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Titre, région, voyageurs et réglage des photos voisines de l'histoire ouverte. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookInfoScreen(state: UiState, vm: BookViewModel) {
    val book = state.book
    var title by rememberSaveable { mutableStateOf(book.title) }
    var region by rememberSaveable { mutableStateOf(book.destination) }
    var radius by rememberSaveable { mutableStateOf(book.groupRadiusMeters.toString()) }
    var travelers by remember { mutableStateOf(book.travelerIds) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Infos de l'histoire") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Titre du livre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                region, { region = it }, label = { Text("Région visitée (facultatif)") },
                supportingText = { Text("Aide à trouver les articles Wikipédia des photos sans GPS") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Qui était du voyage ?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ces personnes figurent sur la couverture. La liste des proches se gère dans les réglages ⚙️.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.people.forEach { p ->
                    FilterChip(
                        selected = p.id in travelers,
                        onClick = { travelers = if (p.id in travelers) travelers - p.id else travelers + p.id },
                        label = { Text(p.name) },
                    )
                }
            }
            TextButton(onClick = { vm.open(Screen.Settings) }) { Text("Ajouter un proche…") }

            OutlinedTextField(
                radius, { radius = it.filter(Char::isDigit) },
                label = { Text("Photos voisines : distance (mètres)") },
                supportingText = { Text("En dessous de cette distance, l'histoire du lieu n'est racontée qu'une fois") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.saveBookInfo(title, region, travelers, radius) }) { Text("Enregistrer") }
        }
    }
}
