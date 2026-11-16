package at.techgaisi.farbwerk.ui.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.animation.AnimationController
import at.techgaisi.farbwerk.animation.AnimationSettings
import at.techgaisi.farbwerk.animation.AssistMode
import at.techgaisi.farbwerk.animation.Frame
import at.techgaisi.farbwerk.animation.PlaybackMode
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.ui.actions.Segmented
import at.techgaisi.farbwerk.ui.actions.SliderRow
import at.techgaisi.farbwerk.ui.actions.SwitchRow
import at.techgaisi.farbwerk.ui.actions.consumeTouches
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class TimelinePopup { NONE, SETTINGS, FRAME }

/**
 * Zeitleiste des Animations- bzw. Seitenassistenten über die volle Breite unten. Jede Ebene bzw. Gruppe ist ein
 * Bild (von links = unterste Ebene). Tippen springt, erneutes Tippen öffnet die Bildoptionen.
 */
@Composable
fun AnimationTimeline(state: EditorState) {
    val doc = state.document ?: return
    val s = AnimationController.settings(doc)
    @Suppress("UNUSED_VARIABLE") val v = AnimationController.version
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    val ui = LocalUi.current
    var popup by remember { mutableStateOf(TimelinePopup.NONE) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            s != null && !state.fullscreen,
            enter = slideInVertically(tween(220)) { it } + fadeIn(),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val settings = s ?: AnimationSettings()
            val pages = settings.mode == AssistMode.PAGES
            val frames = AnimationController.frames(doc)
            val cur = if (AnimationController.playing) AnimationController.playFrame else AnimationController.currentIndex(doc, frames)
            Column(Modifier.fillMaxWidth()) {
                // Popover über der Leiste
                if (popup == TimelinePopup.SETTINGS) SettingsCard(state, settings) { popup = TimelinePopup.NONE }
                if (popup == TimelinePopup.FRAME && cur in frames.indices) {
                    FrameOptionsCard(state, settings, frames, cur) { popup = TimelinePopup.NONE }
                }
                Column(
                    Modifier.fillMaxWidth().background(ui.bar).consumeTouches().navigationBarsPadding()
                        .padding(top = 4.dp, bottom = 6.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!pages) {
                            BarButton(if (AnimationController.playing) "Pause" else "Abspielen", AnimationController.playing) {
                                popup = TimelinePopup.NONE; AnimationController.togglePlay(state)
                            }
                        }
                        BarButton("Einstellungen", popup == TimelinePopup.SETTINGS) {
                            AnimationController.stop()
                            popup = if (popup == TimelinePopup.SETTINGS) TimelinePopup.NONE else TimelinePopup.SETTINGS
                        }
                        BarButton(if (pages) "Neue Seite" else "Bild hinzufügen", false) {
                            popup = TimelinePopup.NONE; AnimationController.addFrame(state)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (pages) "Seite ${cur + 1} von ${frames.size}" else "Bild ${cur + 1} / ${frames.size} · ${settings.fps} fps",
                            color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp)
                        )
                        BarButton("↶", false, enabled = state.undo.canUndo) { AnimationController.stop(); state.undo.undo() }
                        BarButton("↷", false, enabled = state.undo.canRedo) { AnimationController.stop(); state.undo.redo() }
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(cur, frames.size) {
                        if (cur in frames.indices) {
                            val vis = listState.layoutInfo.visibleItemsInfo
                            if (vis.none { it.index == cur }) listState.animateScrollToItem(max(0, cur - 2))
                        }
                    }
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().height(if (pages) 92.dp else 76.dp).padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(frames, key = { _, f -> f.key }) { i, f ->
                            val hold = AnimationController.holdOf(doc, f)
                            val tag = when {
                                i == 0 && settings.firstIsBackground && frames.size > 1 -> "Hintergrund"
                                i == frames.lastIndex && settings.lastIsForeground && frames.size > 1 && !pages -> "Vordergrund"
                                else -> null
                            }
                            FrameCell(doc, f, i, i == cur, hold, tag, pages) {
                                if (i == cur && !AnimationController.playing) {
                                    popup = if (popup == TimelinePopup.FRAME) TimelinePopup.NONE else TimelinePopup.FRAME
                                } else {
                                    popup = TimelinePopup.NONE
                                    AnimationController.selectFrame(state, i)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        color = when { !enabled -> ui.textMuted.copy(alpha = 0.4f); active -> ui.accent; else -> ui.text },
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

/** Bild in der Zeitleiste: Miniatur, aktuelles Bild blau unterstrichen, Haltebilder grau. */
@Composable
private fun FrameCell(doc: ArtDocument, f: Frame, index: Int, current: Boolean, hold: Int, tag: String?, pages: Boolean,
                      onClick: () -> Unit) {
    val ui = LocalUi.current
    val gen = f.layers.sumOf { it.pixelVersion }
    val cellH = if (pages) 64.dp else 50.dp
    val ratio = doc.width / doc.height.toFloat()
    val cellW = (cellH.value * ratio).coerceIn(34f, 110f).dp
    val thumb by produceState<Bitmap?>(null, f.key, gen, f.layers.size) {
        value = withContext(Dispatchers.Default) { renderThumb(doc, f, 120) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(cellW, cellH).clip(RoundedCornerShape(4.dp)).background(Color.White)
                    .border(if (current) 2.dp else 0.5.dp, if (current) ui.accent else ui.divider, RoundedCornerShape(4.dp))
            ) {
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (tag != null) {
                    Text(tag, color = Color.White, fontSize = 8.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).padding(1.dp))
                }
            }
            // Haltebilder
            if (hold > 0) {
                Box(Modifier.padding(start = 2.dp).width((min(hold, 6) * 8).dp).height(cellH * 0.8f)
                    .clip(RoundedCornerShape(3.dp)).background(Color(0xFF5A5A60)), contentAlignment = Alignment.Center) {
                    if (hold > 1) Text("$hold", color = Color.White, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.width(cellW * 0.7f).height(3.dp).clip(CircleShape).background(if (current) ui.accent else Color.Transparent))
        Text(if (pages) "${index + 1}" else "${index + 1}", color = if (current) ui.accent else ui.textMuted, fontSize = 10.sp)
    }
}

/** Miniatur eines Bildes (alle sichtbaren Ebenen, ohne Mischmodi). */
private fun renderThumb(doc: ArtDocument, f: Frame, maxSide: Int): Bitmap? = try {
    val s = min(1f, maxSide / max(doc.width, doc.height).toFloat())
    val b = Bitmap.createBitmap(max(1, (doc.width * s).roundToInt()), max(1, (doc.height * s).roundToInt()), Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    c.scale(b.width / doc.width.toFloat(), b.height / doc.height.toFloat())
    val p = Paint(Paint.FILTER_BITMAP_FLAG)
    for (l in f.layers) {
        if (!l.visible) continue
        p.alpha = (l.opacity * 255).toInt()
        l.drawPixels(c, p)
    }
    b
} catch (_: Throwable) { null }

@Composable
private fun PopupCard(content: @Composable () -> Unit) {
    val ui = LocalUi.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.BottomStart) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(ui.panel).border(0.5.dp, ui.divider, RoundedCornerShape(14.dp)).consumeTouches()
                .padding(vertical = 8.dp)
        ) { content() }
    }
}

private val onionColors = listOf(
    0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
    0xFF30B0C7.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFF8E8E93.toInt(),
)

@Composable
private fun SettingsCard(state: EditorState, s: AnimationSettings, onClose: () -> Unit) {
    val ui = LocalUi.current
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val t = tick
    fun changed() { tick++; AnimationController.settingsChanged(state) }
    PopupCard {
        Text("Einstellungen", color = ui.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        if (s.mode == AssistMode.ANIMATION) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Segmented(PlaybackMode.entries.map { it to it.label }, s.playback, Modifier.fillMaxWidth()) { s.playback = it; changed() }
            }
            SliderRow("Bilder pro Sekunde", s.fps.toFloat(), 1f..60f, { it.roundToInt().toString() }, steps = 58,
                onChange = { s.fps = it.roundToInt(); changed() })
            SliderRow("Zwiebelschicht-Bilder", s.onionFrames.toFloat(), 0f..12f,
                { if (it < 0.5f) "Keine" else it.roundToInt().toString() }, steps = 11,
                onChange = { s.onionFrames = it.roundToInt(); changed() })
            SliderRow("Zwiebelschicht-Deckkraft", s.onionOpacity, 0f..1f, { "${(it * 100).roundToInt()} %" },
                onChange = { s.onionOpacity = it; changed() })
            ColorChoice("Vorherige Bilder", s.onionPrevColor) { s.onionPrevColor = it; changed() }
            ColorChoice("Folgende Bilder", s.onionNextColor) { s.onionNextColor = it; changed() }
        } else {
            SwitchRow("Erste Seite als Hintergrund", s.firstIsBackground) { s.firstIsBackground = it; changed() }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Assistent ausschalten", color = Color(0xFFFF453A), fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClose(); AnimationController.disable(state) }
                    .padding(horizontal = 10.dp, vertical = 8.dp))
            Text("Fertig", color = ui.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun ColorChoice(label: String, value: Int, onPick: (Int) -> Unit) {
    val ui = LocalUi.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, color = ui.text, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            onionColors.forEach { c ->
                val sel = (value or (0xFF shl 24)) == c
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color(c))
                    .border(if (sel) 2.dp else 0.5.dp, if (sel) Color.White else ui.divider, CircleShape)
                    .clickable { onPick(c) })
            }
        }
    }
}

@Composable
private fun FrameOptionsCard(state: EditorState, s: AnimationSettings, frames: List<Frame>, index: Int, onClose: () -> Unit) {
    val ui = LocalUi.current
    val doc = state.document ?: return
    val f = frames[index]
    val pages = s.mode == AssistMode.PAGES
    var hold by remember(f.key) { mutableStateOf(AnimationController.holdOf(doc, f).toFloat()) }
    PopupCard {
        Text(f.name(doc), color = ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        if (!pages) {
            SliderRow("Haltedauer", hold, 0f..120f, { if (it < 0.5f) "Keine" else "${it.roundToInt()} Bilder" },
                onChange = { hold = it.roundToInt().toFloat(); AnimationController.setHold(state, index, it.roundToInt()) })
        }
        if (index == 0 && frames.size > 1) {
            SwitchRow("Hintergrund", s.firstIsBackground, subtitle = "Liegt unter allen ${if (pages) "Seiten" else "Bildern"}") {
                s.firstIsBackground = it; AnimationController.settingsChanged(state)
            }
        }
        if (!pages && index == frames.lastIndex && frames.size > 1) {
            SwitchRow("Vordergrund", s.lastIsForeground, subtitle = "Liegt über allen Bildern") {
                s.lastIsForeground = it; AnimationController.settingsChanged(state)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionButton("Duplizieren", ui.accent, Modifier.weight(1f)) { onClose(); AnimationController.duplicateFrame(state, index) }
            OptionButton("Löschen", Color(0xFFFF453A), Modifier.weight(1f)) { onClose(); AnimationController.deleteFrame(state, index) }
        }
    }
}

@Composable
private fun OptionButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
