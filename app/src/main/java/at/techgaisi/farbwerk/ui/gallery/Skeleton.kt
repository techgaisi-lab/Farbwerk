package at.techgaisi.farbwerk.ui.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Grundfarbe und Glanzfarbe der Platzhalter (dunkles Theme). */
private val SkeletonBase = Color(0xFF1E1E21)
private val SkeletonHighlight = Color(0xFF2E2E33)

/**
 * Schimmer-Effekt für Lade-Platzhalter: ein weicher, diagonal wandernder Glanz über der Grundfarbe.
 * Alle Platzhalter laufen im gleichen Takt, weil die Animation an der Bildschirmposition hängt.
 */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    val width = 1400f
    val x = -width + progress * (width * 3f)
    background(
        Brush.linearGradient(
            colors = listOf(SkeletonBase, SkeletonHighlight, SkeletonBase),
            start = Offset(x, x * 0.35f),
            end = Offset(x + width, (x + width) * 0.35f),
        )
    )
}

/**
 * Skelett der Galerie, solange die Projektliste geladen wird: gleiches Raster wie die echte Galerie,
 * je Kachel eine Miniatur-Fläche sowie zwei Textbalken (Name, Maße).
 */
@Composable
internal fun GallerySkeleton(headerHeight: Dp) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscapeTablet = maxWidth > maxHeight && maxWidth >= 700.dp
        val colGap = if (landscapeTablet) 46.dp else 28.dp
        val columns = if (landscapeTablet) GridCells.Fixed(max(4, ((maxWidth.value - 56f + 46f) / 276f).toInt()))
        else GridCells.Adaptive(150.dp)
        LazyVerticalGrid(
            columns = columns,
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = headerHeight + 10.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(colGap),
            verticalArrangement = Arrangement.spacedBy(34.dp),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            items(12) { i -> SkeletonTile(portrait = i % 3 == 1) }
        }
    }
}

@Composable
private fun SkeletonTile(portrait: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxWidth(if (portrait) 0.75f else 1f)
                    .aspectRatio(if (portrait) 0.75f else 1.33f)
                    .clip(RoundedCornerShape(2.dp))
                    .shimmer()
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(96.dp).height(11.dp).clip(RoundedCornerShape(3.dp)).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(64.dp).height(9.dp).clip(RoundedCornerShape(3.dp)).shimmer())
    }
}
