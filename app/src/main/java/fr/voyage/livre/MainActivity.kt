package fr.voyage.livre

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.voyage.livre.ui.BookViewModel
import fr.voyage.livre.ui.EditorScreen
import fr.voyage.livre.ui.FacesScreen
import fr.voyage.livre.ui.LibraryScreen
import fr.voyage.livre.ui.StoryScreen
import fr.voyage.livre.ui.BookInfoScreen
import fr.voyage.livre.ui.PickerScreen
import fr.voyage.livre.ui.Screen
import fr.voyage.livre.ui.SettingsScreen
import fr.voyage.livre.ui.ViewerScreen

class MainActivity : ComponentActivity() {

    private val viewModel: BookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LivreTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.screen != Screen.Library) { viewModel.back() }
                when (val s = state.screen) {
                    Screen.Library -> LibraryScreen(state, viewModel)
                    Screen.Story -> StoryScreen(state, viewModel)
                    Screen.BookInfo -> BookInfoScreen(state, viewModel)
                    Screen.Picker -> PickerScreen(state, viewModel)
                    is Screen.Editor -> key(s.pageId) { EditorScreen(state, viewModel, s.pageId) }
                    is Screen.Viewer -> ViewerScreen(state, viewModel, s.startIndex)
                    Screen.Settings -> SettingsScreen(state, viewModel)
                    Screen.Faces -> FacesScreen(state, viewModel)
                }
                state.crashReport?.let { report -> CrashDialog(report) { viewModel.dismissCrashReport() } }
            }
        }
    }
}

@Composable
private fun LivreTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Color(0xFF1F5F7A),
        secondary = Color(0xFFB5651D),
        tertiary = Color(0xFF5B7F3A),
        background = Color(0xFFFBF7F0),
        surface = Color(0xFFFBF7F0),
    )
    val dark = darkColorScheme(
        primary = Color(0xFF8CC8E0),
        secondary = Color(0xFFF2B07A),
        tertiary = Color(0xFFB4D69A),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}

/** Montre le rapport d'erreur pour pouvoir le copier et l'envoyer. */
@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Un problème est survenu") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Copiez ce rapport et envoyez-le pour que le problème soit corrigé.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)); onDismiss() }) { Text("Copier et fermer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
