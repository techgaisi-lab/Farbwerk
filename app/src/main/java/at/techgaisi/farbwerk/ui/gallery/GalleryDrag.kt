package at.techgaisi.farbwerk.ui.gallery

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Ziehen in der Galerie: Halten & Ziehen hebt eine Kachel an. Über der Mitte einer anderen Kachel leuchtet diese
 * blau → Loslassen stapelt (bzw. legt in den Stapel). Zwischen Kacheln wird die Einfügestelle markiert → Umsortieren.
 * Über die Kopfzeile ziehen (im geöffneten Stapel) = aus dem Stapel entfernen.
 * Halten ohne Bewegung öffnet das Kontextmenü der Kachel.
 */
class GalleryDragState {
    var dragKey by mutableStateOf<String?>(null)
        private set
    /** Verschiebung der gezogenen Kachel (px). */
    var offset by mutableStateOf(Offset.Zero)
        private set
    var stackTarget by mutableStateOf<String?>(null)
        private set
    /** Schlüssel, vor dem eingefügt wird ("" = am Ende); null = keine Einfügestelle. */
    var insertBefore by mutableStateOf<String?>(null)
        private set
    var overHeader by mutableStateOf(false)
        private set
    var menuKey by mutableStateOf<String?>(null)

    internal var gridOrigin = Offset.Zero
    internal val tileOrigins = HashMap<String, Offset>()
    private var startPointer = Offset.Zero   // Finger im Grid-Raum beim Start
    private var moved = 0f

    fun start(key: String, local: Offset) {
        val origin = tileOrigins[key] ?: return
        dragKey = key; offset = Offset.Zero; moved = 0f
        startPointer = origin + local - gridOrigin
        stackTarget = null; insertBefore = null; overHeader = false
    }

    fun drag(delta: Offset, grid: LazyGridState, canStackOnto: (String) -> Boolean) {
        val key = dragKey ?: return
        offset += delta
        moved += hypot(delta.x, delta.y)
        val p = startPointer + offset
        overHeader = p.y < 0f
        var target: String? = null
        var before: String? = null
        val items = grid.layoutInfo.visibleItemsInfo
        for (info in items) {
            val k = info.key as? String ?: continue
            val r = Rect(info.offset.x.toFloat(), info.offset.y.toFloat(),
                (info.offset.x + info.size.width).toFloat(), (info.offset.y + info.size.height).toFloat())
            if (!r.contains(p)) continue
            if (k == key) break
            val inner = Rect(r.left + r.width * 0.22f, r.top + r.height * 0.12f, r.right - r.width * 0.22f, r.bottom - r.height * 0.3f)
            if (inner.contains(p) && canStackOnto(k)) target = k
            else {
                val idx = items.indexOf(info)
                before = if (p.x < r.center.x) k else (items.getOrNull(idx + 1)?.key as? String ?: "")
            }
            break
        }
        if (target == null && before == null && !overHeader) {
            // hinter dem letzten Element
            val last = items.lastOrNull()
            if (last != null && (p.y > last.offset.y + last.size.height || (p.y > last.offset.y && p.x > last.offset.x + last.size.width))) before = ""
        }
        stackTarget = target
        insertBefore = if (before == key) null else before
    }

    /** Ende des Ziehens. Liefert true, wenn es nur ein Halten war (→ Menü). */
    fun end(onDrop: (key: String, stackTarget: String?, insertBefore: String?, overHeader: Boolean) -> Unit): Boolean {
        val key = dragKey ?: return false
        val wasHold = moved < 12f
        if (!wasHold) onDrop(key, stackTarget, insertBefore, overHeader)
        dragKey = null; offset = Offset.Zero; stackTarget = null; insertBefore = null; overHeader = false
        if (wasHold) menuKey = key
        return wasHold
    }

    fun cancel() { dragKey = null; offset = Offset.Zero; stackTarget = null; insertBefore = null; overHeader = false }
}

/** Kachel-Modifier: merkt die Position und startet das Ziehen nach langem Halten. */
fun Modifier.galleryDraggable(
    drag: GalleryDragState,
    key: String,
    grid: LazyGridState,
    enabled: Boolean,
    canStackOnto: (String) -> Boolean,
    onDrop: (key: String, stackTarget: String?, insertBefore: String?, overHeader: Boolean) -> Unit,
): Modifier = this
    .onGloballyPositioned { drag.tileOrigins[key] = it.positionInRoot() }
    .then(if (!enabled) Modifier else Modifier.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { drag.start(key, it) },
            onDrag = { ch, d -> ch.consume(); drag.drag(d, grid, canStackOnto) },
            onDragEnd = { drag.end(onDrop) },
            onDragCancel = { drag.end(onDrop) },
        )
    })

/** Zwei Finger auf der Kachel aufziehen → [onPinchOut] (Vollbild-Vorschau). Einzelne Finger bleiben unberührt. */
fun Modifier.pinchOutToPreview(onPinchOut: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var startDist = -1f
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val d = (pressed[0].position - pressed[1].position).getDistance()
                if (startDist < 0f) startDist = d
                else if (d > startDist * 1.35f) {
                    ev.changes.forEach { it.consume() }
                    onPinchOut()
                    break
                }
            }
        }
    }
}

/**
 * Vollbild-Vorschau: Wischen blättert, Tippen an den Rändern blättert, Tippen in der Mitte blendet die Leiste
 * ein/aus, Zusammenkneifen schließt, Doppeltippen öffnet das Werk.
 */
@Composable
fun GalleryPreview(state: EditorState, items: List<ProjectInfo>, startIndex: Int, onClose: () -> Unit, onOpen: (ProjectInfo) -> Unit) {
    if (items.isEmpty()) { onClose(); return }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom < 0.8f) onClose() }
                }
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val info = items[page]
                val bmp by produceState<Bitmap?>(null, info.id, info.modified) {
                    value = withContext(Dispatchers.IO) { state.store.thumbnail(info.id) }
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(page) {
                        detectTapGestures(
                            onDoubleTap = { onOpen(info) },
                            onTap = { p ->
                                val w = size.width
                                when {
                                    p.x < w * 0.15f -> scope.launch { pager.animateScrollToPage((page - 1).coerceAtLeast(0)) }
                                    p.x > w * 0.85f -> scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(items.lastIndex)) }
                                    else -> chrome = !chrome
                                }
                            },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    bmp?.let { Image(it.asImageBitmap(), info.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp)) }
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                val info = items[pager.currentPage]
                Column(Modifier.statusBarsPadding().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(info.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${pager.currentPage + 1} von ${items.size} · ${info.width} × ${info.height} px", color = Color(0xFFB0B0B8), fontSize = 12.sp)
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                Text("‹", color = Color(0xCCFFFFFF), fontSize = 44.sp, modifier = Modifier.padding(12.dp))
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("›", color = Color(0xCCFFFFFF), fontSize = 44.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
