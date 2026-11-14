package fr.voyage.livre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.voyage.livre.data.CaptionFormat
import java.io.File

/** Le livre se feuillette de gauche à droite : une couverture puis une page par photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(state: UiState, vm: BookViewModel, startIndex: Int) {
    val book = state.book
    val pager = rememberPagerState(initialPage = startIndex) { book.pages.size + 1 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (pager.currentPage == 0) "Couverture" else "Page ${pager.currentPage} / ${book.pages.size}")
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.padding(padding).fillMaxSize()) { i ->
            if (i == 0) Cover(state) else BookPageView(state, i - 1)
        }
    }
}

@Composable
private fun Cover(state: UiState) {
    val book = state.book
    Box(Modifier.fillMaxSize()) {
        book.pages.firstOrNull()?.let {
            AsyncImage(
                model = File(it.photoPath), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                book.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 28.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary,
            )
            if (book.destination.isNotBlank()) Text(book.destination, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
            if (book.dateRange.isNotBlank()) Text(book.dateRange, style = MaterialTheme.typography.bodySmall)
            if (state.travelers.isNotEmpty()) {
                Text(
                    state.travelers.joinToString(", ") { it.name },
                    fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BookPageView(state: UiState, index: Int) {
    val page = state.book.pages[index]
    val footer = CaptionFormat.footer(page, state.people)
    Column(Modifier.fillMaxSize()) {
        AsyncImage(
            model = File(page.photoPath), contentDescription = page.place,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        // Lieu en gras, texte sur 2 lignes, puis la date et les personnes.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                page.place.ifBlank { "—" }, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 22.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (page.summary.isNotBlank()) {
                Text(page.summary, fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (footer.isNotBlank()) {
                Text(footer, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
