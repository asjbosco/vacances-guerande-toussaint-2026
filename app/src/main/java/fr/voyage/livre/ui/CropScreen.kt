package fr.voyage.livre.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private const val MIN_SIZE = 0.12f      // taille mini du cadre (fraction)
private const val HANDLE_F = 0.06f      // zone de préhension des coins (fraction)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(state: UiState, vm: BookViewModel, pageId: String) {
    val page = state.book.pages.firstOrNull { it.id == pageId }
    if (page == null) { vm.back(); return }

    // Rapport largeur/hauteur de la photo (orientation déjà appliquée à l'import).
    val aspect by produceState<Float?>(initialValue = null, pageId, page.photoPath) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.photoPath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
        }
    }

    var crop by remember { mutableStateOf(Rect(0.08f, 0.08f, 0.92f, 0.92f)) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rogner la photo") },
                navigationIcon = {
                    IconButton(onClick = { vm.open(Screen.Editor(pageId)) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
            )
        },
        snackbarHost = { MessageHost(state, vm) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Déplace le cadre et tire ses coins pour choisir la zone à garder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val a = aspect
                if (a == null) {
                    CircularProgressIndicator()
                } else {
                    // Taille d'affichage de la photo, ajustée à l'espace disponible.
                    val boxW = if (maxWidth / a <= maxHeight) maxWidth else maxHeight * a
                    val boxH = if (maxWidth / a <= maxHeight) maxWidth / a else maxHeight
                    Box(Modifier.width(boxW).height(boxH)) {
                        AsyncImage(
                            model = File(page.photoPath),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Canvas(
                            Modifier.fillMaxSize()
                                .onSizeChanged { sizePx = it }
                                .pointerInput(sizePx) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            if (sizePx.width != 0 && sizePx.height != 0) {
                                                active = pickHandle(crop, pos.x / sizePx.width, pos.y / sizePx.height)
                                            }
                                        },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            if (sizePx.width != 0 && sizePx.height != 0) {
                                                crop = updateCrop(crop, active, drag.x / sizePx.width, drag.y / sizePx.height)
                                            }
                                        },
                                        onDragEnd = { active = -1 },
                                        onDragCancel = { active = -1 },
                                    )
                                },
                        ) {
                            val w = size.width
                            val h = size.height
                            val l = crop.left * w
                            val t = crop.top * h
                            val r = crop.right * w
                            val b = crop.bottom * h
                            val dim = Color.Black.copy(alpha = 0.5f)
                            drawRect(dim, Offset(0f, 0f), Size(w, t))
                            drawRect(dim, Offset(0f, b), Size(w, h - b))
                            drawRect(dim, Offset(0f, t), Size(l, b - t))
                            drawRect(dim, Offset(r, t), Size(w - r, b - t))
                            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
                            val hr = 9.dp.toPx()
                            listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach {
                                drawCircle(Color.White, hr, it)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.cropPhoto(pageId, crop.left, crop.top, crop.right, crop.bottom) },
                    enabled = aspect != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Crop, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Rogner")
                }
                OutlinedButton(onClick = { vm.open(Screen.Editor(pageId)) }, modifier = Modifier.weight(1f)) {
                    Text("Annuler")
                }
            }
        }
    }
}

/** Coin le plus proche (0=HG,1=HD,2=BG,3=BD), 4 = déplacement si à l'intérieur, -1 sinon. */
private fun pickHandle(c: Rect, fx: Float, fy: Float): Int {
    val corners = listOf(
        0 to Offset(c.left, c.top),
        1 to Offset(c.right, c.top),
        2 to Offset(c.left, c.bottom),
        3 to Offset(c.right, c.bottom),
    )
    val near = corners.firstOrNull { (_, o) -> abs(o.x - fx) < HANDLE_F && abs(o.y - fy) < HANDLE_F }
    if (near != null) return near.first
    return if (fx > c.left && fx < c.right && fy > c.top && fy < c.bottom) 4 else -1
}

private fun updateCrop(c: Rect, active: Int, dfx: Float, dfy: Float): Rect {
    var l = c.left; var t = c.top; var r = c.right; var b = c.bottom
    when (active) {
        0 -> { l += dfx; t += dfy }
        1 -> { r += dfx; t += dfy }
        2 -> { l += dfx; b += dfy }
        3 -> { r += dfx; b += dfy }
        4 -> {
            val dx = dfx.coerceIn(-l, 1f - r)
            val dy = dfy.coerceIn(-t, 1f - b)
            l += dx; r += dx; t += dy; b += dy
        }
        else -> return c
    }
    l = l.coerceIn(0f, r - MIN_SIZE)
    t = t.coerceIn(0f, b - MIN_SIZE)
    r = r.coerceIn(l + MIN_SIZE, 1f)
    b = b.coerceIn(t + MIN_SIZE, 1f)
    return Rect(l, t, r, b)
}
