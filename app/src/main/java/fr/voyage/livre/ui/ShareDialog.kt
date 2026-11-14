package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.voyage.livre.share.ShareTarget

@Composable
fun ShareDialog(onDismiss: () -> Unit, onChoose: (ShareTarget) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Chaque photo est envoyée avec sa légende incrustée en dessous.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { onChoose(ShareTarget.WHATSAPP) }, modifier = Modifier.fillMaxWidth()) {
                    Text("WhatsApp")
                }
                Button(onClick = { onChoose(ShareTarget.FACEBOOK) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Facebook (profil ou Page)")
                }
                OutlinedButton(onClick = { onChoose(ShareTarget.OTHER) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Autre application…")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
