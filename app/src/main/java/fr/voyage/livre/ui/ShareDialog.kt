package fr.voyage.livre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.voyage.livre.share.PdfQuality
import fr.voyage.livre.share.ShareTarget

@Composable
fun ShareDialog(onDismiss: () -> Unit, onPdf: ((PdfQuality) -> Unit)? = null, onChoose: (ShareTarget) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Chaque photo est envoyée avec sa légende incrustée en dessous. " +
                        "Sur WhatsApp, choisissez « HD » avant l'envoi pour garder la qualité.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { onChoose(ShareTarget.WHATSAPP) }, modifier = Modifier.fillMaxWidth()) {
                    Text("WhatsApp")
                }
                Button(onClick = { onChoose(ShareTarget.FACEBOOK) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Facebook (profil ou Page)")
                }
                Button(onClick = { onChoose(ShareTarget.QUICK_SHARE) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Quick Share (téléphone à côté)")
                }
                OutlinedButton(onClick = { onChoose(ShareTarget.OTHER) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Autre application…")
                }
                if (onPdf != null) {
                    HorizontalDivider()
                    Text(
                        "Ou tout le livre en un seul PDF : couverture, photos et légendes. " +
                            "Envoyé comme document, WhatsApp ne le recompresse pas.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = { onPdf(PdfQuality.HIGH) }, modifier = Modifier.fillMaxWidth()) {
                        Text("PDF haute qualité (impression)")
                    }
                    OutlinedButton(onClick = { onPdf(PdfQuality.LIGHT) }, modifier = Modifier.fillMaxWidth()) {
                        Text("PDF léger (envoi rapide)")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
