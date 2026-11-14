package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: BookViewModel) {
    val book = state.book
    var title by rememberSaveable { mutableStateOf(book.title) }
    var destination by rememberSaveable { mutableStateOf(book.destination) }
    var start by rememberSaveable { mutableStateOf(book.startDate) }
    var end by rememberSaveable { mutableStateOf(book.endDate) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newDescription by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Le voyage", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, label = { Text("Titre du livre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                destination, { destination = it }, label = { Text("Région visitée") },
                supportingText = { Text("Aide à reconnaître les lieux des photos sans GPS") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    start, { start = it }, label = { Text("Du (jj/mm/aaaa)") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    end, { end = it }, label = { Text("Au (jj/mm/aaaa)") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = { vm.saveBookInfo(title, destination, start, end) }) { Text("Enregistrer") }

            HorizontalDivider()
            Text("Participants", style = MaterialTheme.typography.titleMedium)
            book.participants.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.removeParticipant(p.id) }) { Icon(Icons.Default.Close, "Retirer ${p.name}") }
                    }
                }
            }
            OutlinedTextField(newName, { newName = it }, label = { Text("Prénom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                newDescription, { newDescription = it }, label = { Text("Surnom ou description (facultatif)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.addParticipant(newName, newDescription); newName = ""; newDescription = "" },
                enabled = newName.isNotBlank(),
            ) { Text("Ajouter ce participant") }

            HorizontalDivider()
            Text("Claude (rédaction des légendes)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.apiKeySet) "✅ Une clé API est enregistrée sur ce téléphone."
                else "Sans clé, les textes sont repris tels quels de Wikipédia. " +
                    "Créez une clé sur console.anthropic.com (rubrique API Keys).",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                apiKey, { apiKey = it.trim() },
                label = { Text(if (state.apiKeySet) "Remplacer la clé API" else "Clé API (sk-ant-…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveApiKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank()) {
                    Text("Enregistrer la clé")
                }
                if (state.apiKeySet) {
                    OutlinedButton(onClick = { vm.saveApiKey("") }) { Text("Effacer") }
                }
            }
        }
    }
}
