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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.voyage.livre.data.Participant

/** Réglages communs à toutes les histoires : proches, clé API. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: BookViewModel) {
    var newName by rememberSaveable { mutableStateOf("") }
    var newDescription by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var toRemove by remember { mutableStateOf<Participant?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Famille et proches", style = MaterialTheme.typography.titleMedium)
            Text(
                "Liste commune à toutes vos histoires. Les visages nommés sont reconnus d'une histoire à l'autre.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.people.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { toRemove = p }) { Icon(Icons.Default.Close, "Retirer ${p.name}") }
                    }
                }
            }
            OutlinedTextField(newName, { newName = it }, label = { Text("Prénom (ou nom)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                newDescription, { newDescription = it }, label = { Text("Surnom ou description (facultatif)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.addPerson(newName, newDescription); newName = ""; newDescription = "" },
                enabled = newName.isNotBlank(),
            ) { Text("Ajouter cette personne") }

            HorizontalDivider()
            Text("D'où viennent les légendes ?", style = MaterialTheme.typography.titleMedium)
            Text(
                "La question vous est posée à chaque rédaction. Dans les deux cas, la légende part de la " +
                    "position GPS de la photo ; sans GPS, du lieu que vous indiquez.\n" +
                    "• Sources libres : texte repris et raccourci de Wikipédia, gratuit, rien n'est envoyé à part le lieu.\n" +
                    "• Claude (Anthropic) : la photo et les articles du lieu sont envoyés à Claude, qui rédige le texte. " +
                    "Clé API nécessaire (console.anthropic.com, rubrique API Keys), ~1 centime par photo.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (state.apiKeySet) "✅ Une clé API Claude est enregistrée sur ce téléphone." else "Aucune clé API Claude enregistrée.",
                style = MaterialTheme.typography.bodyMedium,
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
                Button(onClick = { vm.saveApiKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank()) { Text("Enregistrer la clé") }
                if (state.apiKeySet) OutlinedButton(onClick = { vm.saveApiKey("") }) { Text("Effacer") }
            }
        }
    }

    toRemove?.let { p ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Retirer ${p.name} ?") },
            text = { Text("${p.name} disparaît de la liste et des photos où la personne était cochée.") },
            confirmButton = { TextButton(onClick = { toRemove = null; vm.removePerson(p.id) }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Annuler") } },
        )
    }
}
