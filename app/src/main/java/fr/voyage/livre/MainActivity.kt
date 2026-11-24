package fr.voyage.livre

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.voyage.livre.ui.BookViewModel
import fr.voyage.livre.ui.CropScreen
import fr.voyage.livre.ui.EditorScreen
import fr.voyage.livre.ui.HomeScreen
import fr.voyage.livre.ui.PdfPreviewScreen
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
                BackHandler(enabled = state.screen != Screen.Home) { viewModel.back() }
                when (val s = state.screen) {
                    Screen.Home -> HomeScreen(state, viewModel)
                    Screen.Picker -> PickerScreen(state, viewModel)
                    is Screen.Editor -> EditorScreen(state, viewModel, s.pageId)
                    is Screen.Crop -> CropScreen(state, viewModel, s.pageId)
                    is Screen.Viewer -> ViewerScreen(state, viewModel, s.startIndex)
                    Screen.PdfPreview -> PdfPreviewScreen(state, viewModel)
                    Screen.Settings -> SettingsScreen(state, viewModel)
                }
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
